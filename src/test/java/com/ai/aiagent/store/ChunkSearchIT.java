package com.ai.aiagent.store;

import com.ai.aiagent.store.ChunkRepository.SearchFilter;
import com.ai.aiagent.store.StoreModels.ChunkToInsert;
import com.ai.aiagent.store.StoreModels.DocumentMeta;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkSearchIT extends PostgresTestBase {

    @Autowired
    ChunkRepository chunks;

    @Autowired
    DocumentRepository documents;

    private static final float[] NGHI_PHEP = {1.0f, 0.0f, 0.0f, 0.0f};
    private static final float[] LUONG = {0.0f, 1.0f, 0.0f, 0.0f};

    @BeforeEach
    void setUp() {
        truncateAll();
    }

    @Test
    @DisplayName("Tim theo vector tra ve dung thu tu gan nhat truoc")
    void vectorSearchOrdersByCosineDistance() {
        long hr = insertDocument("nhan-su/nghi-phep.md", "nhan-su", List.of());
        insertChunk(hr, "nhan-su/nghi-phep.md", "nhan-su", 0,
                "Người lao động được nghỉ phép năm 12 ngày", NGHI_PHEP);
        insertChunk(hr, "nhan-su/nghi-phep.md", "nhan-su", 1,
                "Bảng lương và phụ cấp", LUONG);

        List<RetrievedChunk> found = chunks.vectorSearch(NGHI_PHEP, 5, SearchFilter.none());

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getContent()).contains("nghỉ phép năm");
        assertThat(found.get(0).getRawScore())
                .as("chunk trung khop hoan toan phai co cosine ~ 1")
                .isGreaterThan(0.99);
    }

    @Test
    @DisplayName("Full-text tim duoc ca khi nguoi dung go KHONG DAU")
    void fullTextMatchesWithoutDiacritics() {
        long hr = insertDocument("nhan-su/nghi-phep.md", "nhan-su", List.of());
        insertChunk(hr, "nhan-su/nghi-phep.md", "nhan-su", 0,
                "Người lao động được nghỉ phép năm 12 ngày", NGHI_PHEP);

        List<RetrievedChunk> found = chunks.fullTextSearch("nghi phep nam", 5, SearchFilter.none());

        assertThat(found)
                .as("cau hinh text search 'vi' (simple + unaccent) phai cho go khong dau")
                .hasSize(1);
    }

    @Test
    @DisplayName("Cac tu duoc ghep bang OR, khong phai AND")
    void wordsAreJoinedWithOr() {
        long hr = insertDocument("nhan-su/nghi-phep.md", "nhan-su", List.of());
        insertChunk(hr, "nhan-su/nghi-phep.md", "nhan-su", 0,
                "Người lao động được nghỉ phép năm 12 ngày", NGHI_PHEP);

        List<RetrievedChunk> found =
                chunks.fullTextSearch("nghi phep va cong tac phi", 5, SearchFilter.none());

        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("Bo loc category chan tai lieu ngoai pham vi")
    void categoryFilterExcludesOtherDepartments() {
        long hr = insertDocument("nhan-su/a.md", "nhan-su", List.of());
        long fin = insertDocument("ke-toan/b.md", "ke-toan", List.of());
        insertChunk(hr, "nhan-su/a.md", "nhan-su", 0, "Nghỉ phép năm", NGHI_PHEP);
        insertChunk(fin, "ke-toan/b.md", "ke-toan", 0, "Nghỉ phép năm", NGHI_PHEP);

        List<RetrievedChunk> found = chunks.vectorSearch(NGHI_PHEP, 10,
                new SearchFilter(Set.of("nhan-su"), Set.of(), false));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCategory()).isEqualTo("nhan-su");
    }

    @Test
    @DisplayName("USER khong doc duoc tai lieu han che ADMIN, ADMIN thi doc duoc")
    void roleFilterHidesRestrictedDocuments() {
        long open = insertDocument("nhan-su/cong-khai.md", "nhan-su", List.of());
        long secret = insertDocument("nhan-su/han-che.md", "nhan-su", List.of("ADMIN"));
        insertChunk(open, "nhan-su/cong-khai.md", "nhan-su", 0, "Nghỉ phép năm", NGHI_PHEP);
        insertChunk(secret, "nhan-su/han-che.md", "nhan-su", 0, "Nghỉ phép năm", NGHI_PHEP);

        List<RetrievedChunk> asUser = chunks.vectorSearch(NGHI_PHEP, 10,
                new SearchFilter(Set.of(), Set.of("USER"), false));
        assertThat(asUser).hasSize(1);
        assertThat(asUser.get(0).getDocKey()).isEqualTo("nhan-su/cong-khai.md");

        List<RetrievedChunk> asAdmin = chunks.vectorSearch(NGHI_PHEP, 10,
                new SearchFilter(Set.of(), Set.of(), false));
        assertThat(asAdmin).hasSize(2);
    }

    @Test
    @DisplayName("Tai lieu het hieu luc bi loai khi bat exclude-expired")
    void expiredDocumentsAreExcluded() {
        long active = insertDocument("nhan-su/con-hieu-luc.md", "nhan-su", List.of());
        long expired = insertDocument("nhan-su/het-hieu-luc.md", "nhan-su", List.of());
        jdbc.update("UPDATE rag_documents SET expires_date = ? WHERE id = ?",
                LocalDate.now().minusDays(1), expired);

        insertChunk(active, "nhan-su/con-hieu-luc.md", "nhan-su", 0, "Nghỉ phép năm", NGHI_PHEP);
        insertChunk(expired, "nhan-su/het-hieu-luc.md", "nhan-su", 0, "Nghỉ phép năm", NGHI_PHEP);

        List<RetrievedChunk> found = chunks.vectorSearch(NGHI_PHEP, 10,
                new SearchFilter(Set.of(), Set.of(), true));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getDocKey()).isEqualTo("nhan-su/con-hieu-luc.md");
    }

    @Test
    @DisplayName("Bo loc ap dung y het nhau o ca hai nhanh tim kiem")
    void bothBranchesApplyTheSameFilter() {
        long open = insertDocument("nhan-su/cong-khai.md", "nhan-su", List.of());
        long secret = insertDocument("nhan-su/han-che.md", "nhan-su", List.of("ADMIN"));
        insertChunk(open, "nhan-su/cong-khai.md", "nhan-su", 0, "Nghỉ phép năm", NGHI_PHEP);
        insertChunk(secret, "nhan-su/han-che.md", "nhan-su", 0, "Nghỉ phép năm", NGHI_PHEP);

        SearchFilter asUser = new SearchFilter(Set.of(), Set.of("USER"), false);

        assertThat(chunks.vectorSearch(NGHI_PHEP, 10, asUser)).hasSize(1);
        assertThat(chunks.fullTextSearch("nghi phep", 10, asUser)).hasSize(1);
    }

    @Test
    @DisplayName("Nap lai cung docKey thi xoa het chunk cu truoc")
    void reingestReplacesOldChunks() {
        long id = insertDocument("nhan-su/a.md", "nhan-su", List.of());
        insertChunk(id, "nhan-su/a.md", "nhan-su", 0, "ban cu", NGHI_PHEP);
        insertChunk(id, "nhan-su/a.md", "nhan-su", 1, "ban cu 2", NGHI_PHEP);
        assertThat(chunks.count()).isEqualTo(2);

        chunks.deleteByDocKey("nhan-su/a.md");
        insertChunk(id, "nhan-su/a.md", "nhan-su", 0, "ban moi", NGHI_PHEP);

        assertThat(chunks.count()).isEqualTo(1);
        assertThat(chunks.vectorSearch(NGHI_PHEP, 5, SearchFilter.none()).get(0).getContent())
                .isEqualTo("ban moi");
    }

    @Test
    @DisplayName("So chieu vector doc duoc tu DB khop cau hinh - SchemaValidator dua vao day")
    void actualEmbeddingDimensionsIsReadable() {
        assertThat(chunks.actualEmbeddingDimensions()).isEqualTo(4);
    }

    private long insertDocument(String docKey, String category, List<String> roles) {
        return documents.upsert(new DocumentMeta(
                null, docKey, docKey.substring(docKey.indexOf('/') + 1), null, category,
                null, null, null, null, "MARKDOWN", null, null, "ACTIVE", roles,
                "sha-" + docKey, 0, 0, "test", null, null));
    }

    private void insertChunk(long documentId, String docKey, String category, int index,
                             String content, float[] embedding) {
        chunks.insertBatch(List.of(new ChunkToInsert(
                documentId, docKey, docKey.substring(docKey.indexOf('/') + 1), category,
                index, null, content, null, content, "sha-" + docKey + "-" + index, embedding)));
    }
}
