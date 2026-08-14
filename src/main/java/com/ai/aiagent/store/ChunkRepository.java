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

/**
 * Truy van bang chunk.
 *
 * Nhung diem da sua so voi ban cu:
 *   - Full-text dung {@code to_tsquery} voi cac tu ghep bang OR (xem
 *     {@link TsQueryBuilder}) va xep hang bang {@code ts_rank_cd}, thay cho
 *     {@code plainto_tsquery} ghep AND lam nhanh nay gan nhu khong bao gio khop.
 *   - Config text search la {@code 'vi'} (co unaccent) nen go khong dau van tim ra.
 *   - Khi CO bo loc (category/ACL), tu dong LAY DU RA (over-fetch) roi cat lai:
 *     pgvector loc SAU khi duyet HNSW, neu khong over-fetch se bi thieu ket qua.
 *   - Khong con ghi cot {@code tsv} tu Java: da co trigger trong DB dam nhiem,
 *     nen {@code tsv} khong the lech voi {@code content}.
 *   - {@code insertBatch} chia lo co dinh thay vi mot lo khong lo.
 */
@Repository
@Slf4j
public class ChunkRepository {

    /** Bo loc ap dung cho moi truy van tim kiem. */
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
        // Ten bang lay tu config (khong phai input nguoi dung) nhung van kiem tra
        // dinh dang truoc khi noi vao SQL.
        if (configured == null || !configured.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalStateException(
                    "rag.store.chunk-table khong phai ten bang hop le: " + configured);
        }
        this.table = configured;
    }

    // ------------------------------------------------------------- Ghi

    /** Xoa toan bo chunk cua mot tai lieu (ghi de khi nap lai). */
    public int deleteByDocumentId(long documentId) {
        return jdbc.update("DELETE FROM " + table + " WHERE document_id = ?", documentId);
    }

    public int deleteByDocKey(String docKey) {
        return jdbc.update("DELETE FROM " + table + " WHERE doc_id = ?", docKey);
    }

    /**
     * Ghi chunk theo lo co dinh. Khong ghi cot {@code tsv}: trigger
     * {@code trg_rag_chunks_tsv} tu sinh.
     */
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
        log.debug("Da ghi {} chunk vao {}.", chunks.size(), table);
    }

    // ------------------------------------------------------- Tim kiem

    /** Tim theo VECTOR (ngu nghia). */
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

    /** Tim theo FULL-TEXT (tu khoa), OR giua cac tu, xep hang bang ts_rank_cd. */
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
            // tsquery khong hop le (tu la ky tu dac biet...) -> bo qua nhanh full-text
            log.warn("Full-text search loi, bo qua nhanh nay: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Khi co bo loc, lay du ra roi cat lai o tang Java.
     *
     * Ly do: pgvector duyet HNSW lay ~ef_search ung vien roi moi ap WHERE, nen voi
     * category hep ket qua tra ve co the it hon topK, tham chi rong. Tang LIMIT
     * cung lam pgvector tu tang ef_search tuong ung.
     */
    private int effectiveLimit(int topK, SearchFilter filter) {
        if (filter == null || !filter.isRestrictive()) return topK;
        int multiplier = Math.max(1, props.getRetrieval().getFilterOverfetchMultiplier());
        return Math.min(props.getRetrieval().getMaxOverfetch(), topK * multiplier);
    }

    private void appendWhere(StringBuilder sql, List<Object> args, SearchFilter filter) {
        StringBuilder conditions = new StringBuilder();
        appendConditions(conditions, args, filter, true);
        if (conditions.length() > 0) {
            sql.append(" WHERE ").append(conditions.substring(5)); // bo " AND " dau tien
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
            // Tai lieu khong khai bao role => moi nguoi da xac thuc deu doc duoc.
            // Chunk cu chua gan document_id (d.id IS NULL) cung duoc coi la khong ACL.
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

    // ------------------------------------------------------------ Thong ke

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0 : c;
    }

    public long countByDocument(long documentId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE document_id = ?", Long.class, documentId);
        return c == null ? 0 : c;
    }

    /** So chieu that cua cot embedding trong DB, de doi chieu voi cau hinh. */
    public Integer actualEmbeddingDimensions() {
        try {
            // pgvector luu so chieu truc tiep trong atttypmod (khong tru 4 nhu varchar)
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
