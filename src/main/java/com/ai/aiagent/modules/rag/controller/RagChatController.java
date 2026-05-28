package com.ai.aiagent.modules.rag.controller;

import com.ai.aiagent.modules.rag.handler.RagTeamsWebhookHandler;
import com.ai.aiagent.modules.rag.service.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rag")
public class RagChatController {

    private final RagChatService chatService;
    private final RagTeamsWebhookHandler teamsHandler;

    public RagChatController(RagChatService chatService, RagTeamsWebhookHandler teamsHandler) {
        this.chatService = chatService;
        this.teamsHandler = teamsHandler;
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