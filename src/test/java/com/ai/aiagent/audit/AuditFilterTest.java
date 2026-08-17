package com.ai.aiagent.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hai phep bien doi thuan tuy cua {@link AuditFilter}: che secret va chuan hoa duong dan.
 *
 * Phep che secret dac biet dang duoc test: nhat ky kiem toan bi doc boi nhieu nguoi hon
 * la so nguoi duoc biet secret, nen mot lo hong o day bien chinh cong cu kiem soat
 * thanh cho ro ri.
 */
class AuditFilterTest {

    @Test
    @DisplayName("Che gia tri cua moi truong co ten goi y la secret")
    void redactsSecretLookingFields() {
        String json = """
                {"provider":"OPENAI","apiKey":"sk-abc123","model":"gpt-4o-mini"}""";

        String redacted = AuditFilter.redact(json);

        assertThat(redacted).doesNotContain("sk-abc123");
        assertThat(redacted).contains("\"apiKey\":\"***\"");
        // Truong khong phai secret phai con nguyen - nhat ky che het thi vo dung.
        assertThat(redacted).contains("\"provider\":\"OPENAI\"");
        assertThat(redacted).contains("\"model\":\"gpt-4o-mini\"");
    }

    @Test
    @DisplayName("Che ca cac bien the ten thuong gap")
    void redactsCommonVariants() {
        String json = """
                {"client_secret":"s1","password":"p","accessToken":"t","PAT":"x","botAppPassword":"b"}""";

        String redacted = AuditFilter.redact(json);

        assertThat(redacted).doesNotContain("s1", "\"p\"", "\"t\"", "\"x\"", "\"b\"");
        assertThat(redacted).contains("***");
    }

    @Test
    @DisplayName("Khong che nham truong binh thuong")
    void doesNotOverRedact() {
        String json = """
                {"category":"nhan-su","topK":6,"question":"Nghỉ phép mấy ngày?"}""";

        assertThat(AuditFilter.redact(json)).isEqualTo(json);
    }

    @Test
    @DisplayName("Doan duong dan la SO duoc thay bang {id} de gom nhom duoc")
    void normalizesNumericSegments() {
        assertThat(AuditFilter.normalize("/api/v1/rag/admin/documents/42"))
                .isEqualTo("/admin/documents/{id}");
        assertThat(AuditFilter.normalize("/api/v1/rag/admin/bots/7/collections"))
                .isEqualTo("/admin/bots/{id}/collections");
        assertThat(AuditFilter.normalize("/api/v1/rag/settings"))
                .isEqualTo("/settings");
    }

    @Test
    @DisplayName("Slug co chua so KHONG bi coi la id")
    void keepsNonNumericSegments() {
        // "bp-ptpm-2024" la mot slug, khong phai khoa chinh. Thay no bang {id} se lam
        // hai hanh dong khac nhau bi gom lam mot trong bao cao.
        assertThat(AuditFilter.normalize("/api/v1/rag/admin/collections/bp-ptpm-2024"))
                .isEqualTo("/admin/collections/bp-ptpm-2024");
    }
}
