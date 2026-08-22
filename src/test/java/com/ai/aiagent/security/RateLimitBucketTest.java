package com.ai.aiagent.security;

import com.ai.aiagent.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gio dem cua RateLimitFilter.
 *
 * Loi that da xay ra: MOI lenh duoi /api/ (tru /admin) deu dem vao gio "chat" gioi han
 * 30/phut, ma mot lan tai trang hoi dap la ~5 request (/me, /settings, /models,
 * /categories, /conversations). F5 sau lan trong mot phut la 429 du chua hoi cau nao.
 */
class RateLimitBucketTest {

    private final SecurityProperties.RateLimit cfg = new SecurityProperties.RateLimit();

    @Test
    @DisplayName("Chi endpoint sinh cau tra loi moi dung han muc chat")
    void onlyGenerationUsesTheChatBudget() {
        assertThat(RateLimitFilter.bucketFor("/api/v1/rag/chat", "POST", cfg))
                .isEqualTo(new RateLimitFilter.Bucket("chat", cfg.getChatPerMinute()));
        assertThat(RateLimitFilter.bucketFor("/api/v1/rag/chat/stream", "POST", cfg))
                .isEqualTo(new RateLimitFilter.Bucket("chat", cfg.getChatPerMinute()));
    }

    @Test
    @DisplayName("Cac lenh doc de ve trang khong an vao han muc chat")
    void pageBootstrapDoesNotUseTheChatBudget() {
        for (String path : new String[]{
                "/api/v1/rag/me",
                "/api/v1/rag/settings",
                "/api/v1/rag/models",
                "/api/v1/rag/categories",
                "/api/v1/rag/conversations"}) {
            assertThat(RateLimitFilter.bucketFor(path, "GET", cfg))
                    .as("bootstrap path %s", path)
                    .isEqualTo(new RateLimitFilter.Bucket("other", cfg.getOtherPerMinute()));
        }
    }

    @Test
    @DisplayName("Han muc doc phai thoang hon chat, du cho nhieu lan tai trang")
    void readBudgetIsRoomierThanChat() {
        assertThat(cfg.getOtherPerMinute()).isGreaterThan(cfg.getChatPerMinute() * 5);
    }

    @Test
    @DisplayName("Duong quan tri va webhook giu nguyen gio rieng")
    void adminAndWebhookKeepTheirOwnBuckets() {
        assertThat(RateLimitFilter.bucketFor("/api/v1/rag/admin/documents", "GET", cfg))
                .isEqualTo(new RateLimitFilter.Bucket("admin", cfg.getAdminPerMinute()));
        assertThat(RateLimitFilter.bucketFor("/api/v1/rag/teams-webhook", "POST", cfg))
                .isEqualTo(new RateLimitFilter.Bucket("webhook", cfg.getWebhookPerMinute()));
    }

    @Test
    @DisplayName("GET vao duong chat khong tinh la sinh cau tra loi")
    void getOnChatPathIsNotGeneration() {
        assertThat(RateLimitFilter.bucketFor("/api/v1/rag/chat", "GET", cfg).kind())
                .isEqualTo("other");
    }

    @Test
    @DisplayName("Xoa hoi thoai la thao tac re, khong an han muc chat")
    void deletingAConversationIsCheap() {
        assertThat(RateLimitFilter.bucketFor("/api/v1/rag/conversations/abc", "DELETE", cfg).kind())
                .isEqualTo("other");
    }
}
