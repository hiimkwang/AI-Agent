package com.ai.aiagent.chat;

import com.ai.aiagent.chat.ChatDtos.ChatRequest;
import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.ai.aiagent.chat.ChatDtos.ChatStreamListener;
import com.ai.aiagent.llm.LlmClientFactory;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.store.ConversationRepository;
import com.ai.aiagent.store.FeedbackRepository;
import com.ai.aiagent.store.StoreModels.Citation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag")
@Slf4j
public class RagChatController {

    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;

    private final RagChatService chatService;
    private final ConversationRepository conversations;
    private final FeedbackRepository feedback;
    private final LlmClientFactory clients;
    private final TaskExecutor sseExecutor;

    public RagChatController(RagChatService chatService, ConversationRepository conversations,
                             FeedbackRepository feedback, LlmClientFactory clients,
                             @org.springframework.beans.factory.annotation.Qualifier("sseExecutor")
                             TaskExecutor sseExecutor) {
        this.chatService = chatService;
        this.conversations = conversations;
        this.feedback = feedback;
        this.clients = clients;
        this.sseExecutor = sseExecutor;
    }

    /** Hoi-dap dong bo - tra ve mot lan. */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.answer(request, CurrentScope.get());
    }

    /**
     * Hoi-dap dang STREAM (SSE).
     *
     * Su kien: {@code status} (tien do), {@code citations} (nguon, gui truoc khi sinh
     * chu), {@code token} (tung doan chu), {@code done}, {@code error}.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AccessScope scope = CurrentScope.get();

        emitter.onTimeout(() -> {
            log.warn("SSE timeout cho client {}", scope.clientId());
            emitter.complete();
        });

        sseExecutor.execute(() -> chatService.streamAnswer(request, scope, new ChatStreamListener() {
            @Override
            public void onStatus(String stage, String detail) {
                send(emitter, "status", Map.of("stage", stage, "detail", detail));
            }

            @Override
            public void onCitations(List<Citation> citations) {
                send(emitter, "citations", citations);
            }

            @Override
            public void onToken(String token) {
                send(emitter, "token", Map.of("t", token));
            }

            @Override
            public void onDone(ChatResponse response) {
                send(emitter, "done", response);
                emitter.complete();
            }

            @Override
            public void onError(String message) {
                send(emitter, "error", Map.of("error", message));
                emitter.complete();
            }
        }));
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client dong ket noi giua luc stream - binh thuong, khong phai loi
            log.debug("SSE khong gui duoc su kien '{}': {}", event, e.getMessage());
        }
    }

    // ------------------------------------------------------- Hoi thoai

    @GetMapping("/conversations")
    public Map<String, Object> conversations(@RequestParam(defaultValue = "50") int limit) {
        AccessScope scope = CurrentScope.get();
        // Nguoi dung thuong chi thay hoi thoai cua chinh minh; ADMIN thay tat ca
        String userId = scope.isAdmin() ? null : scope.clientId();
        return Map.of("conversations", conversations.listConversations(userId, limit));
    }

    @GetMapping("/conversations/{conversationId}")
    public Map<String, Object> conversation(@PathVariable String conversationId) {
        return Map.of(
                "conversationId", conversationId,
                "messages", conversations.messages(conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(@PathVariable String conversationId) {
        int deleted = conversations.deleteConversation(conversationId);
        return Map.of("deleted", deleted > 0,
                "message", deleted > 0 ? "Đã xoá hội thoại." : "Không tìm thấy hội thoại.");
    }

    // -------------------------------------------------------- Feedback

    /** Ghi nhan 👍/👎 - nen tang de biet chatbot dang sai o dau. */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> feedback(@RequestBody FeedbackRequest request) {
        if (request.messageId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Thieu messageId."));
        }
        AccessScope scope = CurrentScope.get();
        feedback.save(request.messageId(), request.conversationId(), scope.clientId(),
                request.rating() == null ? 1 : request.rating(), request.comment());
        return ResponseEntity.ok(Map.of("message", "Đã ghi nhận phản hồi. Cảm ơn bạn."));
    }

    public record FeedbackRequest(Long messageId, String conversationId, Integer rating, String comment) {
    }

    // ---------------------------------------------------------- Models

    /** Danh sach provider + model, kem co san hay khong (co API key hay chua). */
    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of("providers", clients.catalog());
    }
}
