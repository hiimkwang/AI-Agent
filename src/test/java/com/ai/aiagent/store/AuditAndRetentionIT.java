package com.ai.aiagent.store;

import com.ai.aiagent.audit.AuditEvent;
import com.ai.aiagent.audit.AuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiem tra nhat ky kiem toan va viec don du lieu qua han tren DB that.
 *
 * Hai thu nay chi co gia tri khi thuc su chay dung tren Postgres: cau xoa dung
 * {@code (? || ' days')::interval} - mot cach viet de sai am tham (xoa 0 dong ma
 * khong bao loi), va do dung la kieu loi khong bao gio bi phat hien bang mat.
 */
@Import(AuditRepository.class)
class AuditAndRetentionIT extends PostgresTestBase {

    @Autowired
    AuditRepository audit;

    @BeforeEach
    void setUp() {
        truncateAll();
    }

    @Test
    @DisplayName("Ghi va doc lai duoc mot dong nhat ky")
    void insertAndSearch() {
        audit.insert(event("DELETE /admin/documents/{id}", "quang@bsc.com.vn", 200, true));

        List<Map<String, Object>> found = audit.search(null, null, null, 30, 50, 0);

        assertThat(found).hasSize(1);
        assertThat(found.get(0)).containsEntry("actor", "quang@bsc.com.vn");
        assertThat(found.get(0)).containsEntry("action", "DELETE /admin/documents/{id}");
        assertThat(found.get(0)).containsEntry("succeeded", true);
    }

    @Test
    @DisplayName("Loc duoc rieng cac thao tac BI TU CHOI")
    void filterDeniedOnly() {
        audit.insert(event("POST /admin/collections", "a@bsc.com.vn", 200, true));
        audit.insert(event("DELETE /admin/bots/{id}", "b@bsc.com.vn", 403, false));

        List<Map<String, Object>> denied = audit.search(null, null, true, 30, 50, 0);

        assertThat(denied).hasSize(1);
        assertThat(denied.get(0)).containsEntry("status", 403);
    }

    @Test
    @DisplayName("Loc theo nguoi thuc hien - cau hoi thu hai cua kiem toan")
    void filterByActor() {
        audit.insert(event("POST /settings", "quang@bsc.com.vn", 200, true));
        audit.insert(event("POST /settings", "khac@bsc.com.vn", 200, true));

        assertThat(audit.search("quang", null, null, 30, 50, 0)).hasSize(1);
    }

    @Test
    @DisplayName("Tong hop dem dung theo nguoi va theo hanh dong")
    void summaryCounts() {
        audit.insert(event("POST /settings", "quang@bsc.com.vn", 200, true));
        audit.insert(event("POST /settings", "quang@bsc.com.vn", 200, true));
        audit.insert(event("DELETE /admin/documents/{id}", "quang@bsc.com.vn", 403, false));

        Map<String, Object> summary = audit.summary(30);

        assertThat(summary).containsEntry("total", 3L);
        assertThat(summary).containsEntry("denied", 1L);
    }

    @Test
    @DisplayName("Don nhat ky qua han xoa dong cu va GIU dong moi")
    void purgeRemovesOnlyOldRows() {
        audit.insert(event("POST /settings", "quang@bsc.com.vn", 200, true));
        audit.insert(event("POST /settings", "quang@bsc.com.vn", 200, true));
        // Day mot dong ve qua khu 400 ngay.
        jdbc.update("UPDATE rag_audit_log SET created_at = now() - interval '400 days' WHERE id = 1");

        int deleted = audit.purgeOlderThanDays(365);

        assertThat(deleted).isEqualTo(1);
        assertThat(audit.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Don hoi thoai qua han xoa ca tin nhan cua no")
    void purgingConversationsCascadesToMessages() {
        jdbc.update("""
                INSERT INTO rag_conversations (id, user_id, created_at, last_active_at)
                VALUES ('c-cu', 'u1', now() - interval '400 days', now() - interval '400 days')
                """);
        jdbc.update("""
                INSERT INTO rag_conversations (id, user_id, created_at, last_active_at)
                VALUES ('c-moi', 'u1', now(), now())
                """);
        jdbc.update("INSERT INTO rag_messages (conversation_id, role, content) VALUES ('c-cu', 'user', 'hoi')");
        jdbc.update("INSERT INTO rag_messages (conversation_id, role, content) VALUES ('c-moi', 'user', 'hoi')");

        ConversationRepository conversations = new ConversationRepository(jdbc);
        int deleted = conversations.purgeInactiveOlderThanDays(180);

        assertThat(deleted).isEqualTo(1);
        Integer messages = jdbc.queryForObject("SELECT count(*) FROM rag_messages", Integer.class);
        assertThat(messages)
                .as("tin nhan cua hoi thoai da xoa phai bi xoa theo, khong duoc mo coi")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Don job chi dong toi job DA KET THUC")
    void purgeJobsSkipsRunningOnes() {
        jdbc.update("""
                INSERT INTO rag_ingest_jobs (id, state, started_at, finished_at)
                VALUES ('j-cu', 'DONE', now() - interval '200 days', now() - interval '200 days')
                """);
        jdbc.update("""
                INSERT INTO rag_ingest_jobs (id, state, started_at)
                VALUES ('j-dang-chay', 'RUNNING', now() - interval '200 days')
                """);

        int deleted = new JobRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper())
                .purgeFinishedOlderThanDays(90);

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM rag_ingest_jobs", String.class))
                .isEqualTo("RUNNING");
    }

    private AuditEvent event(String action, String actor, int status, boolean ok) {
        String[] parts = action.split(" ", 2);
        return new AuditEvent("oid-" + actor, actor, "ADMIN,USER", "entra", action,
                parts[0], parts[1], null, null, status, ok, 12, "10.0.0.1", "test", null);
    }
}
