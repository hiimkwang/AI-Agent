package com.ai.aiagent.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A document without a category is readable by admins only, silently: retrieval matches
 * {@code c.category} against the caller's collection slugs and NULL matches none. It shipped
 * once - an uploaded .docx with no category answered fine on the web for an admin while the
 * Teams bot said "khong tim thay" to everyone.
 */
class IngestionCategoryTest {

    @Test
    @DisplayName("Thieu nhom tai lieu thi tu choi nap, khong nap am tham")
    void blankCategoryIsRejected() {
        for (String bad : new String[]{null, "", "   ", "\t"}) {
            assertThatThrownBy(() -> IngestionService.requireCategory(bad, "carbon.docx"))
                    .as("category=%s", bad == null ? "null" : "'" + bad + "'")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("carbon.docx");
        }
    }

    @Test
    @DisplayName("Thong bao loi phai noi ro hau qua, khong chi noi 'thieu truong'")
    void messageExplainsTheConsequence() {
        assertThatThrownBy(() -> IngestionService.requireCategory(null, "x.docx"))
                .hasMessageContaining("quản trị")
                .hasMessageContaining("không bao giờ tìm thấy");
    }

    // doc_key is "category/fileName": a slash inside the category makes the key ambiguous and
    // breaks overwrite-on-reingest.
    @Test
    @DisplayName("Nhom tai lieu khong duoc chua dau gach cheo")
    void slashInCategoryIsRejected() {
        assertThatThrownBy(() -> IngestionService.requireCategory("ptpm/algo", "x.docx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/");
    }

    @Test
    @DisplayName("Nhom tai lieu hop le thi di qua")
    void validCategoryPasses() {
        assertThatCode(() -> IngestionService.requireCategory("ptpm-carbon", "x.docx"))
                .doesNotThrowAnyException();
    }
}
