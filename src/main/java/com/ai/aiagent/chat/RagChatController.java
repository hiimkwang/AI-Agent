package com.ai.aiagent.chat;

import com.ai.aiagent.common.NotFoundException;
import com.ai.aiagent.chat.ChatDtos.ChatRequest;
import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.ai.aiagent.chat.ChatDtos.ChatStreamListener;
import com.ai.aiagent.llm.LlmClientFactory;
import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.store.ConversationRepository;
import com.ai.aiagent.store.DocumentRepository;
import com.ai.aiagent.store.FeedbackRepository;
import com.ai.aiagent.store.StoreModels.Citation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1/rag")
@Slf4j
public class RagChatController {

    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;

    private final RagChatService chatService;
    private final ConversationRepository conversations;
    private final FeedbackRepository feedback;
    private final LlmClientFactory clients;
    private final DocumentRepository documents;
    private final AnswerCacheService cache;
    private final TaskExecutor sseExecutor;

    public RagChatController(RagChatService chatService, ConversationRepository conversations,
                             FeedbackRepository feedback, LlmClientFactory clients,
                             DocumentRepository documents, AnswerCacheService cache,
                             @org.springframework.beans.factory.annotation.Qualifier("sseExecutor")
                             TaskExecutor sseExecutor) {
        this.chatService = chatService;
        this.conversations = conversations;
        this.feedback = feedback;
        this.clients = clients;
        this.documents = documents;
        this.cache = cache;
        this.sseExecutor = sseExecutor;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        AccessScope scope = CurrentScope.get();
        return forClient(chatService.answer(request, scope), scope);
    }

    /*
     * Chi phi, token, ten model va so lieu truy xuat la thong tin van hanh. Cat o
     * ngay cua ra chung cua CA hai duong (dong bo + stream) thay vi o tung ham
     * finish*, de duong nao them sau nay cung khong lo mat.
     */
    private ChatResponse forClient(ChatResponse r, AccessScope scope) {
        if (r == null || scope.isAdmin()) return r;
        return new ChatResponse(r.answer(), r.citations(), r.abstained(), r.abstainReason(),
                null, null, LlmUsage.EMPTY, r.latencyMs(), r.cacheHit(), r.messageId(),
                r.conversationId(), null, r.suggestions());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AccessScope scope = CurrentScope.get();

        /* Nguoi dung bam "dung" = truc tiep ngat ket noi. Co ba duong biet dieu do, va
           duong dang tin nhat la loi khi GHI (onError/onCompletion co the khong kip ban).
           Co nay lan xuong tan client LLM de dung sinh, khong chi dung hien thi. */
        AtomicBoolean stopped = new AtomicBoolean(false);

        emitter.onTimeout(() -> {
            log.debug("SSE stream timed out for client {}", scope.clientId());
            stopped.set(true);
            emitter.complete();
        });
        emitter.onError(e -> stopped.set(true));

        sseExecutor.execute(() -> chatService.streamAnswer(request, scope, new ChatStreamListener() {
            @Override
            public boolean cancelled() {
                return stopped.get();
            }

            @Override
            public void onStatus(String stage, String detail) {
                send(emitter, stopped, "status", Map.of("stage", stage, "detail", detail));
            }

            @Override
            public void onCitations(List<Citation> citations) {
                send(emitter, stopped, "citations", citations);
            }

            @Override
            public void onToken(String token) {
                send(emitter, stopped, "token", Map.of("t", token));
            }

            @Override
            public void onDone(ChatResponse response) {
                send(emitter, stopped, "done", forClient(response, scope));
                emitter.complete();
            }

            @Override
            public void onError(String message) {
                send(emitter, stopped, "error", Map.of("error", message));
                emitter.complete();
            }
        }));
        return emitter;
    }

    private void send(SseEmitter emitter, AtomicBoolean stopped, String event, Object data) {
        if (stopped.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Ghi that bai = phia kia da di. Danh dau de vong sinh token dung lai.
            stopped.set(true);
            log.debug("SSE event '{}' could not be sent, client is gone: {}", event, e.getMessage());
        }
    }

    /** {@code mine=true} keeps an admin's own chat sidebar from listing everyone's history. */
    @GetMapping("/conversations")
    public Map<String, Object> conversations(@RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(defaultValue = "false") boolean mine) {
        AccessScope scope = CurrentScope.get();
        String userId = (mine || !scope.isAdmin()) ? scope.clientId() : null;
        return Map.of("conversations", conversations.listConversations(userId, limit));
    }

    @GetMapping("/conversations/{conversationId}")
    public Map<String, Object> conversation(@PathVariable String conversationId) {
        requireOwnership(conversationId);
        List<Map<String, Object>> messages = conversations.messages(conversationId);
        if (!CurrentScope.get().isAdmin()) {
            // Mo lai hoi thoai cu khong duoc lam lo lai dung thu vua cat o tren.
            for (Map<String, Object> m : messages) {
                OPERATIONAL_FIELDS.forEach(m::remove);
            }
        }
        return Map.of("conversationId", conversationId, "messages", messages);
    }

    private static final List<String> OPERATIONAL_FIELDS = List.of(
            "provider", "model", "inputTokens", "outputTokens", "costUsd");

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(@PathVariable String conversationId) {
        requireOwnership(conversationId);
        int deleted = conversations.deleteConversation(conversationId);
        return Map.of("deleted", deleted > 0,
                "message", deleted > 0 ? "Đã xoá hội thoại." : "Không tìm thấy hội thoại.");
    }

    /** Wipes the caller's own history. Admins clear only their own, never everyone's. */
    @DeleteMapping("/conversations")
    public Map<String, Object> deleteMyConversations() {
        int deleted = conversations.deleteConversationsOf(CurrentScope.get().clientId());
        return Map.of("deleted", deleted,
                "message", "Đã xoá " + deleted + " hội thoại.");
    }

    /* A conversation id is guessable, so without this check any signed-in user could read
       or delete another user's history. Admins are exempt: they answer for the whole system. */
    private void requireOwnership(String conversationId) {
        AccessScope scope = CurrentScope.get();
        if (scope.isAdmin()) return;
        String owner = conversations.ownerOf(conversationId);
        if (owner == null) throw new NotFoundException("Khong tim thay hoi thoai.");
        if (!owner.equals(scope.clientId())) {
            throw new AccessDeniedException("Hoi thoai nay khong thuoc ve ban.");
        }
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> feedback(@RequestBody FeedbackRequest request) {
        if (request.messageId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Thieu messageId."));
        }
        AccessScope scope = CurrentScope.get();
        int rating = request.rating() == null ? 1 : request.rating();
        feedback.save(request.messageId(), request.conversationId(), scope.clientId(),
                rating, request.comment());

        // Marking an answer bad has to unstick it. Otherwise the cache keeps serving the same
        // answer for the rest of the TTL and asking again looks like the feedback was ignored.
        int evicted = 0;
        if (rating < 0) {
            try {
                String question = conversations.questionOf(request.messageId());
                evicted = cache.invalidate(question);
            } catch (Exception e) {
                log.warn("Could not evict the cached answer after negative feedback: {}",
                        e.getMessage());
            }
        }
        log.info("Feedback rating={} on message {}, {} cached answer(s) evicted.",
                rating, request.messageId(), evicted);
        return ResponseEntity.ok(Map.of("message", evicted > 0
                ? "Đã ghi nhận phản hồi. Câu trả lời cũ đã được xoá khỏi bộ nhớ đệm, lần hỏi "
                        + "sau hệ thống sẽ tìm lại từ đầu."
                : "Đã ghi nhận phản hồi. Cảm ơn bạn."));
    }

    public record FeedbackRequest(Long messageId, String conversationId, Integer rating, String comment) {
    }

    /**
     * Danh sach nhom tai lieu cho o loc o man hoi dap.
     *
     * Ban cu goi /admin/categories, ma duong /admin/** doi ROLE_ADMIN: nguoi dung thuong
     * bi 403 nen o loc trong tron va con an mot toast do moi lan tai trang. Endpoint nay
     * chi liet ke nhom ma nguoi goi DOC DUOC - hien nhom bi tu choi thi bam vao cung chi
     * nhan 403 tu AccessScope.narrowTo().
     */
    @GetMapping("/categories")
    public Map<String, Object> categories() {
        AccessScope scope = CurrentScope.get();
        List<String> all = documents.distinctCategories();
        if (scope.allDepartments()) {
            return Map.of("categories", all);
        }
        List<String> visible = all.stream()
                .filter(c -> c != null && scope.departments().contains(c.toLowerCase()))
                .toList();
        return Map.of("categories", visible);
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of("providers", clients.catalog());
    }
}
