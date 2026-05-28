package com.ai.aiagent.modules.rag.handler;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class RagTeamsWebhookHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    public String extractUserMessage(String rawTeamsJson) {
        try {
            JsonNode root = mapper.readTree(rawTeamsJson);
            // Trích xuất trường văn bản thô từ Activity Object của Bot Framework
            return root.path("text").asText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public String formatTeamsResponse(String aiAnswer) {
        // Chuyển đổi ký tự đặc biệt để chuỗi JSON không bị lỗi cú pháp khi bắn ngược lên Cloud
        String escapedAnswer = aiAnswer.replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
        return "{\"type\": \"message\", \"text\": \"" + escapedAnswer + "\"}";
    }
}