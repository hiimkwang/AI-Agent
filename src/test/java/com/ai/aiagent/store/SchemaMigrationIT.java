package com.ai.aiagent.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiem tra SCHEMA that: 7 file Flyway chay duoc tren Postgres+pgvector va tao ra dung
 * nhung bat bien ma phan con lai cua he thong dua vao.
 *
 * Moi khang dinh o day tuong ung voi mot bat bien da tung bi pha trong thuc te (xem
 * CLAUDE.md muc "Bat bien"). Test nay ton tai de lan sau khong pha lai duoc nua.
 */
class SchemaMigrationIT extends PostgresTestBase {

    @Test
    @DisplayName("Toan bo migration chay xong va khong co ban nao that bai")
    void allMigrationsApplied() {
        List<String> failed = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false",
                String.class);
        assertThat(failed).isEmpty();

        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    @DisplayName("Cau hinh text search 'vi' ton tai - khong phai 'simple'")
    void vietnameseTextSearchConfigExists() {
        // Bat bien: cau hinh la 'vi' (simple + unaccent), nho vay go KHONG DAU van tim ra.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_ts_config WHERE cfgname = 'vi'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Go khong dau van khop - day la ly do ton tai cua cau hinh 'vi'")
    void unaccentedQueryStillMatches() {
        Boolean matched = jdbc.queryForObject("""
                SELECT to_tsvector('vi', 'Nghỉ phép năm của người lao động')
                       @@ to_tsquery('vi', 'nghi | phep')
                """, Boolean.class);
        assertThat(matched).isTrue();
    }

    @Test
    @DisplayName("Cot tsv do TRIGGER sinh, khong phai do code Java ghi")
    void tsvIsGeneratedByTrigger() {
        truncateAll();
        // Chen chunk KHONG dat tsv - neu trigger khong chay thi cot nay se rong va
        // toan bo nhanh full-text im lang tro nen vo dung.
        jdbc.update("""
                INSERT INTO rag_chunks (doc_id, file_name, category, chunk_index, content,
                                        char_count, embedding)
                VALUES ('t/a.md', 'a.md', 'test', 0, 'Quy định về nghỉ phép năm', 25, ?::vector)
                """, "[0.1,0.2,0.3,0.4]");

        Boolean hasTsv = jdbc.queryForObject(
                "SELECT tsv IS NOT NULL AND tsv <> ''::tsvector FROM rag_chunks LIMIT 1",
                Boolean.class);
        assertThat(hasTsv).isTrue();
    }

    @Test
    @DisplayName("So chieu vector trong DDL lay tu placeholder embeddingDim")
    void embeddingDimensionComesFromPlaceholder() {
        // Test dat placeholder = 4; neu co ai bo placeholder va hardcode 1536 thi
        // khang dinh nay do - va do la dung, vi khi ay doi model embedding se lam
        // schema va cau hinh lech nhau ma khong ai biet.
        String type = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                 WHERE c.relname = 'rag_chunks' AND a.attname = 'embedding'
                """, String.class);
        assertThat(type).isEqualTo("vector(4)");
    }

    @Test
    @DisplayName("Xoa tai lieu keo theo xoa chunk (ON DELETE CASCADE)")
    void deletingDocumentCascadesToChunks() {
        truncateAll();
        Long docId = jdbc.queryForObject("""
                INSERT INTO rag_documents (doc_key, file_name, category, content_sha256)
                VALUES ('test/a.md', 'a.md', 'test', 'sha') RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO rag_chunks (document_id, doc_id, file_name, category, chunk_index,
                                        content, char_count, embedding)
                VALUES (?, 'test/a.md', 'a.md', 'test', 0, 'noi dung', 8, ?::vector)
                """, docId, "[0.1,0.2,0.3,0.4]");

        jdbc.update("DELETE FROM rag_documents WHERE id = ?", docId);

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM rag_chunks WHERE document_id = ?", Integer.class, docId);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("doc_key la khoa duy nhat - hai file cung ten khac nhom la HAI tai lieu")
    void docKeyIsTheOverwriteKey() {
        truncateAll();
        jdbc.update("""
                INSERT INTO rag_documents (doc_key, file_name, category, content_sha256)
                VALUES ('nhan-su/noi-quy.docx', 'noi-quy.docx', 'nhan-su', 'sha1')
                """);
        jdbc.update("""
                INSERT INTO rag_documents (doc_key, file_name, category, content_sha256)
                VALUES ('ke-toan/noi-quy.docx', 'noi-quy.docx', 'ke-toan', 'sha2')
                """);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM rag_documents WHERE file_name = 'noi-quy.docx'",
                Integer.class);
        assertThat(count)
                .as("cung ten file o hai nhom phai la hai tai lieu doc lap")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Bang nhat ky kiem toan (V7) ton tai kem chi muc")
    void auditTableExists() {
        Integer table = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'rag_audit_log'",
                Integer.class);
        assertThat(table).isEqualTo(1);

        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'rag_audit_log'",
                String.class);
        assertThat(indexes).contains("idx_rag_audit_created", "idx_rag_audit_actor");
    }
}
