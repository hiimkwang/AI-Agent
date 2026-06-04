package com.ai.aiagent.modules.rag.store;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository quản lý trực tiếp bảng vector trong Postgres (pgvector) bằng JdbcTemplate.
 *
 * Tự quản lý schema (thay vì EmbeddingStore của langchain4j) để có thêm cột tsvector
 * phục vụ FULL-TEXT SEARCH -> làm được HYBRID SEARCH, và cột category để LỌC theo nhóm.
 *
 * Mỗi dòng: child chunk (content) + ngữ cảnh (context) + parent chunk (parent_content)
 * + category + vector + tsvector.
 */
@Repository
@Slf4j
public class RagVectorRepository {

    private final JdbcTemplate jdbc;

    @Value("${rag.store.table}")
    private String table;
    @Value("${rag.openai.embedding-dimensions}")
    private int dimension;

    public RagVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Khởi tạo extension + bảng + index khi ứng dụng start. */
    @PostConstruct
    public void initSchema() {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id             BIGSERIAL PRIMARY KEY,
                        doc_id         TEXT NOT NULL,
                        file_name      TEXT NOT NULL,
                        category       TEXT,
                        chunk_index    INT  NOT NULL,
                        content        TEXT NOT NULL,
                        context        TEXT,
                        parent_content TEXT,
                        embedding      vector(%d) NOT NULL,
                        tsv            tsvector,
                        created_at     TIMESTAMP DEFAULT now()
                    )
                    """.formatted(table, dimension));

            // Phòng trường hợp bảng đã tồn tại từ trước mà chưa có cột category
            jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS category TEXT".formatted(table));

            jdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_%s_embedding
                    ON %s USING hnsw (embedding vector_cosine_ops)
                    """.formatted(table, table));

            jdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_%s_tsv
                    ON %s USING gin (tsv)
                    """.formatted(table, table));

            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_doc ON %s (doc_id)"
                    .formatted(table, table));
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_category ON %s (category)"
                    .formatted(table, table));

            log.info("Khởi tạo schema vector store '{}' (dimension={}) thành công.", table, dimension);
        } catch (Exception e) {
            log.error("Lỗi khởi tạo schema vector store. Kiểm tra Postgres/pgvector đã chạy chưa.", e);
            throw e;
        }
    }

    /** Xóa toàn bộ chunk của một tài liệu (để ghi đè khi upload lại – chống trùng). */
    public int deleteByDocId(String docId) {
        int deleted = jdbc.update("DELETE FROM " + table + " WHERE doc_id = ?", docId);
        if (deleted > 0) {
            log.info("Đã xóa {} chunk cũ của tài liệu '{}' trước khi nạp lại.", deleted, docId);
        }
        return deleted;
    }

    /** Ghi một lô chunk vào DB. */
    public void insertBatch(List<RagChunk> chunks) {
        String sql = "INSERT INTO " + table + " "
                + "(doc_id, file_name, category, chunk_index, content, context, parent_content, embedding, tsv) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, to_tsvector('simple', ?))";

        jdbc.batchUpdate(sql, chunks, chunks.size(), (ps, c) -> {
            String tsvText = (c.context() == null ? "" : c.context() + " ") + c.content();
            ps.setString(1, c.docId());
            ps.setString(2, c.fileName());
            ps.setString(3, c.category());
            ps.setInt(4, c.chunkIndex());
            ps.setString(5, c.content());
            ps.setString(6, c.context());
            ps.setString(7, c.parentContent());
            ps.setString(8, toVectorLiteral(c.embedding()));
            ps.setString(9, tsvText);
        });
        log.info("Đã ghi {} chunk vào bảng {}.", chunks.size(), table);
    }

    /** Tìm theo VECTOR (ngữ nghĩa) – top-k theo cosine, có thể lọc theo category. */
    public List<RetrievedChunk> vectorSearch(float[] queryVector, int topK, String category) {
        String vec = toVectorLiteral(queryVector);
        StringBuilder sql = new StringBuilder()
                .append("SELECT id, doc_id, file_name, category, chunk_index, content, context, parent_content, ")
                .append("1 - (embedding <=> ?::vector) AS score FROM ").append(table).append(" ");
        List<Object> args = new ArrayList<>();
        args.add(vec);
        if (hasCategory(category)) {
            sql.append("WHERE category = ? ");
            args.add(category);
        }
        sql.append("ORDER BY embedding <=> ?::vector LIMIT ?");
        args.add(vec);
        args.add(topK);
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /** Tìm theo FULL-TEXT (từ khóa) – top-k theo ts_rank, có thể lọc theo category. */
    public List<RetrievedChunk> fullTextSearch(String queryText, int topK, String category) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT id, doc_id, file_name, category, chunk_index, content, context, parent_content, ")
                .append("ts_rank(tsv, plainto_tsquery('simple', ?)) AS score FROM ").append(table).append(" ")
                .append("WHERE tsv @@ plainto_tsquery('simple', ?) ");
        List<Object> args = new ArrayList<>();
        args.add(queryText);
        args.add(queryText);
        if (hasCategory(category)) {
            sql.append("AND category = ? ");
            args.add(category);
        }
        sql.append("ORDER BY score DESC LIMIT ?");
        args.add(topK);
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /** Danh sách tài liệu đã nạp + số chunk + category. */
    public List<DocumentInfo> listDocuments() {
        String sql = "SELECT file_name, MAX(category) AS category, COUNT(*) AS chunks FROM " + table
                + " GROUP BY file_name ORDER BY file_name";
        return jdbc.query(sql, (rs, n) ->
                new DocumentInfo(rs.getString("file_name"), rs.getString("category"), rs.getInt("chunks")));
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0 : c;
    }

    // ---- Helpers ----

    private boolean hasCategory(String category) {
        return category != null && !category.isBlank();
    }

    private static final RowMapper<RetrievedChunk> ROW_MAPPER = (rs, n) -> new RetrievedChunk(
            rs.getLong("id"),
            rs.getString("doc_id"),
            rs.getString("file_name"),
            rs.getString("category"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getString("context"),
            rs.getString("parent_content"),
            rs.getDouble("score")
    );

    /** Chuyển float[] -> chuỗi dạng "[0.1,0.2,...]" để cast sang kiểu vector của pgvector. */
    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public record DocumentInfo(String fileName, String category, int chunks) {}
}
