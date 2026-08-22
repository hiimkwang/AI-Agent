package com.ai.aiagent.store;

import com.ai.aiagent.store.StoreModels.DocumentMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Slf4j
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long upsert(DocumentMeta meta) {
        String sql = """
                INSERT INTO rag_documents
                    (doc_key, file_name, title, category, department, doc_number, doc_version,
                     source_path, source_format, effective_date, expires_date, status,
                     allowed_roles, content_sha256, markdown, chunk_count, char_count,
                     created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (doc_key) DO UPDATE SET
                    file_name      = EXCLUDED.file_name,
                    title          = EXCLUDED.title,
                    category       = EXCLUDED.category,
                    department     = EXCLUDED.department,
                    doc_number     = EXCLUDED.doc_number,
                    doc_version    = EXCLUDED.doc_version,
                    source_path    = EXCLUDED.source_path,
                    source_format  = EXCLUDED.source_format,
                    effective_date = EXCLUDED.effective_date,
                    expires_date   = EXCLUDED.expires_date,
                    status         = EXCLUDED.status,
                    allowed_roles  = EXCLUDED.allowed_roles,
                    content_sha256 = EXCLUDED.content_sha256,
                    markdown       = EXCLUDED.markdown,
                    chunk_count    = EXCLUDED.chunk_count,
                    char_count     = EXCLUDED.char_count,
                    updated_at     = now()
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, Long.class,
                meta.docKey(), meta.fileName(), meta.title(), meta.category(), meta.department(),
                meta.docNumber(), meta.docVersion(), meta.sourcePath(), meta.sourceFormat(),
                toDate(meta.effectiveDate()), toDate(meta.expiresDate()),
                meta.status() == null ? "ACTIVE" : meta.status(),
                rolesLiteral(meta.allowedRoles()),
                meta.contentSha256(), null, meta.chunkCount(), meta.charCount(), meta.createdBy());
        if (id == null) {
            throw new IllegalStateException("Khong lay duoc id tai lieu sau khi ghi.");
        }
        return id;
    }

    public void updateMarkdown(long id, String markdown) {
        jdbc.update("UPDATE rag_documents SET markdown = ?, updated_at = now() WHERE id = ?",
                markdown, id);
    }

    public void updateChunkCount(long id, int chunkCount) {
        jdbc.update("UPDATE rag_documents SET chunk_count = ?, updated_at = now() WHERE id = ?",
                chunkCount, id);
    }

    public Optional<String> findMarkdown(long id) {
        List<String> found = jdbc.queryForList(
                "SELECT markdown FROM rag_documents WHERE id = ?", String.class, id);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    public Optional<DocumentMeta> findByDocKey(String docKey) {
        List<DocumentMeta> found = jdbc.query(
                "SELECT * FROM rag_documents WHERE doc_key = ?", ROW_MAPPER, docKey);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public Optional<DocumentMeta> findById(long id) {
        List<DocumentMeta> found = jdbc.query(
                "SELECT * FROM rag_documents WHERE id = ?", ROW_MAPPER, id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public Optional<String> findSha(String docKey) {
        List<String> found = jdbc.queryForList(
                "SELECT content_sha256 FROM rag_documents WHERE doc_key = ?", String.class, docKey);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    public List<DocumentMeta> list(String category, String search, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM rag_documents WHERE 1 = 1 ");
        sql.append(filterClause(category, search, args));
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /**
     * How many documents match the same filter {@link #list} would page through. The list
     * is capped at one page, so without this the UI cannot tell "100 documents" from
     * "100 shown of 4000" and offers no way to act on the rest.
     */
    public long count(String category, String search) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM rag_documents WHERE 1 = 1 "
                + filterClause(category, search, args);
        Long c = jdbc.queryForObject(sql, Long.class, args.toArray());
        return c == null ? 0 : c;
    }

    /** Shared so the count can never drift away from what the list actually returns. */
    private String filterClause(String category, String search, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ? ");
            args.add(category);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (file_name ILIKE ? OR coalesce(title,'') ILIKE ? "
                    + "OR coalesce(doc_number,'') ILIKE ?) ");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return sql.toString();
    }

    public long countAll() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM rag_documents", Long.class);
        return c == null ? 0 : c;
    }

    /**
     * Move a document into another category.
     *
     * <p>{@code doc_key} is {@code category/fileName} and it is the overwrite key, so the key has
     * to move with the category. Leaving it behind would make the next ingest of the same file
     * create a second row instead of overwriting this one.
     *
     * @return rows updated
     */
    public int updateCategory(long id, String category, String docKey) {
        return jdbc.update("""
                UPDATE rag_documents
                   SET category = ?, doc_key = ?, updated_at = now()
                 WHERE id = ?
                """, category, docKey, id);
    }

    /** True when another document already occupies this {@code doc_key}. */
    public boolean docKeyTakenByOther(String docKey, long selfId) {
        Long other = jdbc.query(
                "SELECT id FROM rag_documents WHERE doc_key = ? AND id <> ? LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, docKey, selfId);
        return other != null;
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM rag_documents WHERE id = ?", id);
    }

    public int deleteByDocKey(String docKey) {
        return jdbc.update("DELETE FROM rag_documents WHERE doc_key = ?", docKey);
    }

    public List<String> distinctCategories() {
        return jdbc.queryForList(
                "SELECT DISTINCT category FROM rag_documents WHERE category IS NOT NULL ORDER BY category",
                String.class);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT count(*) AS documents,
                       coalesce(sum(chunk_count), 0) AS chunks,
                       coalesce(sum(char_count), 0) AS chars,
                       count(*) FILTER (WHERE coalesce(status,'ACTIVE') <> 'ACTIVE') AS inactive,
                       count(*) FILTER (WHERE expires_date IS NOT NULL
                                          AND expires_date < CURRENT_DATE) AS expired
                  FROM rag_documents
                """, rs -> {
            out.put("documents", rs.getLong("documents"));
            out.put("chunks", rs.getLong("chunks"));
            out.put("chars", rs.getLong("chars"));
            out.put("inactive", rs.getLong("inactive"));
            out.put("expired", rs.getLong("expired"));
        });
        return out;
    }

    private static Date toDate(java.time.LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static String rolesLiteral(List<String> roles) {
        if (roles == null || roles.isEmpty()) return null;
        return Vectors.toTextArrayLiteral(roles);
    }

    private static final RowMapper<DocumentMeta> ROW_MAPPER = (rs, n) -> {
        Date effective = rs.getDate("effective_date");
        Date expires = rs.getDate("expires_date");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");

        List<String> roles = List.of();
        Array array = rs.getArray("allowed_roles");
        if (array != null) {
            Object raw = array.getArray();
            if (raw instanceof String[] values) {
                roles = List.of(values);
            }
        }
        return new DocumentMeta(
                rs.getLong("id"),
                rs.getString("doc_key"),
                rs.getString("file_name"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("department"),
                rs.getString("doc_number"),
                rs.getString("doc_version"),
                rs.getString("source_path"),
                rs.getString("source_format"),
                effective == null ? null : effective.toLocalDate(),
                expires == null ? null : expires.toLocalDate(),
                rs.getString("status"),
                roles,
                rs.getString("content_sha256"),
                rs.getInt("chunk_count"),
                rs.getInt("char_count"),
                rs.getString("created_by"),
                created == null ? null : created.toInstant(),
                updated == null ? null : updated.toInstant());
    };
}
