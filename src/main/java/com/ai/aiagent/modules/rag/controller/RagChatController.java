package com.ai.aiagent.modules.rag.controller;

import com.ai.aiagent.modules.rag.handler.RagTeamsWebhookHandler;
import com.ai.aiagent.modules.rag.llm.LlmProvider;
import com.ai.aiagent.modules.rag.memory.ConversationMemory;
import com.ai.aiagent.modules.rag.service.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag")
public class RagChatController {

    private final RagChatService chatService;
    private final RagTeamsWebhookHandler teamsHandler;
    private final ConversationMemory memory;

    public RagChatController(RagChatService chatService,
                             RagTeamsWebhookHandler teamsHandler,
                             ConversationMemory memory) {
        this.chatService = chatService;
        this.teamsHandler = teamsHandler;
        this.memory = memory;
    }

    /**
     * Body ví dụ:
     * {
     *   "question": "...",
     *   "provider": "GEMINI",          // tùy chọn, bỏ trống = dùng mặc định
     *   "model": "gemini-1.5-pro",     // tùy chọn
     *   "conversationId": "user-123"   // tùy chọn, để hỏi nối tiếp (multi-turn)
     * }
     */
    public static class ChatRequest {
        private String question;
        private String provider;
        private String model;
        private String conversationId;
        private String category;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Câu hỏi trống."));
        }
        try {
            LlmProvider provider = LlmProvider.fromString(request.getProvider()); // null nếu không truyền
            RagChatService.ChatResult result = chatService.answer(
                    request.getQuestion(), provider, request.getModel(),
                    request.getConversationId(), request.getCategory());
            return ResponseEntity.ok(Map.of(
                    "answer", result.answer(),
                    "sources", result.sources()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Xóa lịch sử một hội thoại. */
    @DeleteMapping("/chat/{conversationId}")
    public ResponseEntity<?> clearConversation(@PathVariable String conversationId) {
        memory.clear(conversationId);
        return ResponseEntity.ok(Map.of("message", "Đã xóa lịch sử hội thoại " + conversationId));
    }

    @PostMapping("/teams-webhook")
    public ResponseEntity<String> receiveTeamsMessage(@RequestBody String rawPayload) {
        String cleanQuestion = teamsHandler.extractUserMessage(rawPayload);
        if (cleanQuestion.isEmpty()) {
            return ResponseEntity.badRequest().body("Nội dung tin nhắn trống");
        }
        String aiAnswer = chatService.retrieveAndAnswer(cleanQuestion);
        String formattedResponse = teamsHandler.formatTeamsResponse(aiAnswer);
        return ResponseEntity.ok(formattedResponse);
    }
}
