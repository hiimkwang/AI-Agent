package com.ai.aiagent.store;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.store.StoreModels.ChunkToInsert;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
@Slf4j
public class ChunkRepository {

    public record SearchFilter(
            Set<String> categories,
            Set<String> roles,
            boolean excludeExpired
    ) {
        public static SearchFilter none() {
            return new SearchFilter(Set.of(), Set.of(), false);
        }

        public boolean hasCategoryFilter() {
            return categories != null && !categories.isEmpty();
        }

        public boolean hasRoleFilter() {
            return roles != null && !roles.isEmpty();
        }

        public boolean isRestrictive() {
            return hasCategoryFilter() || hasRoleFilter() || excludeExpired;
        }
    }

    private static final String SELECT_COLUMNS = """
            c.id, c.document_id, c.doc_id, c.file_name, c.category, c.chunk_index,
            c.heading_path, c.content, c.context, c.parent_content,
            d.title AS doc_title, d.doc_number, d.doc_version,
            d.effective_date, d.status
            """;

    private final JdbcTemplate jdbc;
    private final RagProperties props;
    private String table;

    public ChunkRepository(JdbcTemplate jdbc, RagProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostConstruct
    void validateTableName() {
        String configured = props.getStore().getChunkTable();
        if (configured == null || !configured.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalStateException(
                    "rag.store.chunk-table khong phai ten bang hop le: " + configured);
        }
        this.table = configured;
    }

    public int deleteByDocumentId(long documentId) {
        return jdbc.update("DELETE FROM " + table + " WHERE document_id = ?", documentId);
    }

    public int deleteByDocKey(String docKey) {
        return jdbc.update("DELETE FROM " + table + " WHERE doc_id = ?", docKey);
    }

    /**
     * Follow a document that moved to another category. The category is denormalised onto every
     * chunk because retrieval filters on it, so leaving the chunks behind would keep the document
     * invisible under its old collection while the document row says otherwise.
     */
    public int updateCategory(long documentId, String category, String docKey) {
        return jdbc.update(
                "UPDATE " + table + " SET category = ?, doc_id = ? WHERE document_id = ?",
                category, docKey, documentId);
    }

    public void insertBatch(List<ChunkToInsert> chunks) {
        if (chunks.isEmpty()) return;
        String sql = "INSERT INTO " + table + " "
                + "(document_id, doc_id, file_name, category, chunk_index, heading_path, "
                + " content, context, parent_content, content_sha256, char_count, embedding) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)";

        int batchSize = Math.max(50, props.getStore().getInsertBatchSize());
        for (int from = 0; from < chunks.size(); from += batchSize) {
            List<ChunkToInsert> slice = chunks.subList(from, Math.min(chunks.size(), from + batchSize));
            jdbc.batchUpdate(sql, slice, slice.size(), (ps, c) -> {
                if (c.documentId() == null) {
                    ps.setNull(1, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(1, c.documentId());
                }
                ps.setString(2, c.docKey());
                ps.setString(3, c.fileName());
                ps.setString(4, c.category());
                ps.setInt(5, c.chunkIndex());
                ps.setString(6, c.headingPath());
                ps.setString(7, c.content());
                ps.setString(8, c.context());
                ps.setString(9, c.parentContent());
                ps.setString(10, c.contentSha256());
                ps.setInt(11, c.content() == null ? 0 : c.content().length());
                ps.setString(12, Vectors.toLiteral(c.embedding()));
            });
        }
        log.debug("Wrote {} chunk(s) into {}.", chunks.size(), table);
    }

    public List<RetrievedChunk> vectorSearch(float[] queryVector, int topK, SearchFilter filter) {
        String vector = Vectors.toLiteral(queryVector);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(", 1 - (c.embedding <=> ?::vector) AS score FROM ").append(table)
                .append(" c LEFT JOIN rag_documents d ON d.id = c.document_id ");
        args.add(vector);

        appendWhere(sql, args, filter);

        sql.append(" ORDER BY c.embedding <=> ?::vector LIMIT ?");
        args.add(vector);
        int limit = effectiveLimit(topK, filter);
        args.add(limit);

        List<RetrievedChunk> rows = jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
        rows.forEach(r -> r.setMatchedBy("VECTOR"));
        return rows.size() > topK ? new ArrayList<>(rows.subList(0, topK)) : rows;
    }

    public List<RetrievedChunk> trialVectorSearch(String trialTable, float[] queryVector,
                                                  int topK, SearchFilter filter) {
        requireValidTableName(trialTable);
        String vector = Vectors.toLiteral(queryVector);
        List<Object> args = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(", 1 - (t.embedding <=> ?::vector) AS score FROM ").append(trialTable)
                .append(" t JOIN ").append(table).append(" c ON c.id = t.chunk_id ")
                .append(" LEFT JOIN rag_documents d ON d.id = c.document_id ");
        args.add(vector);

        appendWhere(sql, args, filter);

        sql.append(" ORDER BY t.embedding <=> ?::vector LIMIT ?");
        args.add(vector);
        args.add(effectiveLimit(topK, filter));

        List<RetrievedChunk> rows = jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
        rows.forEach(r -> r.setMatchedBy("VECTOR"));
        return rows.size() > topK ? new ArrayList<>(rows.subList(0, topK)) : rows;
    }

    public static void requireValidTableName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Ten bang khong hop le: " + name);
        }
    }

    public List<RetrievedChunk> fullTextSearch(String queryText, int topK, SearchFilter filter) {
        String tsQuery = TsQueryBuilder.orQuery(queryText);
        if (tsQuery == null) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(", ts_rank_cd(c.tsv, to_tsquery('vi', ?)) AS score FROM ").append(table)
                .append(" c LEFT JOIN rag_documents d ON d.id = c.document_id ")
                .append(" WHERE c.tsv @@ to_tsquery('vi', ?) ");
        args.add(tsQuery);
        args.add(tsQuery);

        appendConditions(sql, args, filter, false);

        sql.append(" ORDER BY score DESC LIMIT ?");
        args.add(effectiveLimit(topK, filter));

        try {
            List<RetrievedChunk> rows = jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
            rows.forEach(r -> r.setMatchedBy("FULLTEXT"));
            return rows.size() > topK ? new ArrayList<>(rows.subList(0, topK)) : rows;
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Full-text search failed, skipping that branch: {}", e.getMessage());
            return List.of();
        }
    }

    private int effectiveLimit(int topK, SearchFilter filter) {
        if (filter == null || !filter.isRestrictive()) return topK;
        // pgvector applies filters after the HNSW walk, so category/ACL queries must
        // over-fetch and trim in Java or they come back short.
        int multiplier = Math.max(1, props.getRetrieval().getFilterOverfetchMultiplier());
        return Math.min(props.getRetrieval().getMaxOverfetch(), topK * multiplier);
    }

    private void appendWhere(StringBuilder sql, List<Object> args, SearchFilter filter) {
        StringBuilder conditions = new StringBuilder();
        appendConditions(conditions, args, filter, true);
        if (conditions.length() > 0) {
            sql.append(" WHERE ").append(conditions.substring(5));
        }
    }

    private void appendConditions(StringBuilder sql, List<Object> args,
                                  SearchFilter filter, boolean leadingAnd) {
        if (filter == null) return;

        if (filter.hasCategoryFilter()) {
            sql.append(" AND c.category = ANY(?::text[]) ");
            args.add(Vectors.toTextArrayLiteral(filter.categories()));
        }
        if (filter.hasRoleFilter()) {
            sql.append(" AND (d.id IS NULL OR d.allowed_roles IS NULL ")
                    .append(" OR cardinality(d.allowed_roles) = 0 ")
                    .append(" OR d.allowed_roles && ?::text[]) ");
            args.add(Vectors.toTextArrayLiteral(filter.roles()));
        }
        if (filter.excludeExpired()) {
            sql.append(" AND (d.id IS NULL OR (")
                    .append("   coalesce(d.status, 'ACTIVE') = 'ACTIVE' ")
                    .append("   AND (d.expires_date IS NULL OR d.expires_date >= CURRENT_DATE) ")
                    .append("   AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)")
                    .append(")) ");
        }
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0 : c;
    }

    public long countByDocument(long documentId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE document_id = ?", Long.class, documentId);
        return c == null ? 0 : c;
    }

    public Integer actualEmbeddingDimensions() {
        try {
            return jdbc.queryForObject("""
                    SELECT a.atttypmod
                      FROM pg_attribute a
                      JOIN pg_class t ON t.oid = a.attrelid
                     WHERE t.relname = ? AND a.attname = 'embedding'
                    """, Integer.class, table);
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> distinctCategories() {
        return jdbc.queryForList(
                "SELECT DISTINCT category FROM " + table + " WHERE category IS NOT NULL ORDER BY category",
                String.class);
    }

    private static final RowMapper<RetrievedChunk> ROW_MAPPER = (rs, n) -> {
        Date effective = rs.getDate("effective_date");
        Long documentId = rs.getObject("document_id") == null ? null : rs.getLong("document_id");
        return new RetrievedChunk(
                rs.getLong("id"),
                documentId,
                rs.getString("doc_id"),
                rs.getString("file_name"),
                rs.getString("category"),
                rs.getInt("chunk_index"),
                rs.getString("heading_path"),
                rs.getString("content"),
                rs.getString("context"),
                rs.getString("parent_content"),
                rs.getString("doc_title"),
                rs.getString("doc_number"),
                rs.getString("doc_version"),
                effective == null ? null : effective.toLocalDate(),
                rs.getString("status"),
                rs.getDouble("score"));
    };
}
