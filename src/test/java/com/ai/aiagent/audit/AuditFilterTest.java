package com.ai.aiagent.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFilterTest {

    @Test
    @DisplayName("Che gia tri cua moi truong co ten goi y la secret")
    void redactsSecretLookingFields() {
        String json = """
                {"provider":"OPENAI","apiKey":"sk-abc123","model":"gpt-4o-mini"}""";

        String redacted = AuditFilter.redact(json);

        assertThat(redacted).doesNotContain("sk-abc123");
        assertThat(redacted).contains("\"apiKey\":\"***\"");
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
    @DisplayName("Truong 'path' KHONG bi che - da tung bi che nham vi chua chuoi 'pat'")
    void doesNotRedactPathField() {
        String json = """
                {"path":"D:/tai-lieu/nhan-su","category":"nhan-su"}""";

        assertThat(AuditFilter.redact(json)).isEqualTo(json);
    }

    @Test
    @DisplayName("Nhung ten KET THUC bang 'pat' thi van bi che")
    void stillRedactsRealPatFields() {
        String json = """
                {"pat":"ghp_abc","devops_pat":"xyz","azurePat":"123","sourcePath":"D:/a"}""";

        String redacted = AuditFilter.redact(json);

        assertThat(redacted).doesNotContain("ghp_abc", "xyz", "123");
        assertThat(redacted).contains("\"sourcePath\":\"D:/a\"");
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
        assertThat(AuditFilter.normalize("/api/v1/rag/admin/collections/bp-ptpm-2024"))
                .isEqualTo("/admin/collections/bp-ptpm-2024");
    }
}
