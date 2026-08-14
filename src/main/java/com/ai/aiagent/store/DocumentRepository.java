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

/**
 * Metadata muc tai lieu.
 *
 * Bang nay la moi: truoc day chi co bang chunk, {@code docId = fileName}, va khong
 * co cho nao luu ngay hieu luc / phong ban / so hieu / phien ban. Vi vay khong tra
 * loi duoc "quy dinh moi nhat la gi" va van ban het hieu luc van duoc trich dan
 * ngang hang voi van ban dang ap dung.
 *
 * {@code doc_key} la khoa on dinh (mac dinh {@code category/fileName}) nen hai file
 * cung ten o hai nhom khac nhau khong con ghi de len nhau nhu truoc.
 */
@Repository
@Slf4j
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tao moi hoac cap nhat theo {@code doc_key}; tra ve id. */
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

    /** Luu ban Markdown rieng (tach ra vi chuoi co the rat dai). */
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

    /** @return sha256 cua lan nap truoc, dung de bo qua file khong doi. */
    public Optional<String> findSha(String docKey) {
        List<String> found = jdbc.queryForList(
                "SELECT content_sha256 FROM rag_documents WHERE doc_key = ?", String.class, docKey);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    /** Danh sach tai lieu co phan trang va tim kiem theo ten. */
    public List<DocumentMeta> list(String category, String search, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM rag_documents WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
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
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long countAll() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM rag_documents", Long.class);
        return c == null ? 0 : c;
    }

    public int deleteById(long id) {
        // rag_chunks co ON DELETE CASCADE nen chunk se tu bien mat
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

    /** Thong ke tong quan cho trang quan tri. */
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
