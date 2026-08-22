package com.ai.aiagent.chat;

import com.ai.aiagent.chat.ChatDtos.ChatRequest;
import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.ai.aiagent.config.TeamsProperties;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.security.TeamsSignatureVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/rag")
@Slf4j
public class TeamsWebhookController {

    private final RagChatService chatService;
    private final TeamsSignatureVerifier verifier;
    private final TeamsProperties properties;
    private final ObjectMapper mapper;

    public TeamsWebhookController(RagChatService chatService, TeamsSignatureVerifier verifier,
                                  TeamsProperties properties, ObjectMapper mapper) {
        this.chatService = chatService;
        this.verifier = verifier;
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostMapping(value = "/teams-webhook", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(@RequestBody(required = false) byte[] rawBody,
                                          @RequestHeader(value = HttpHeaders.AUTHORIZATION,
                                                  required = false) String authorization) {
        if (!properties.isEnabled()) {
            log.debug("Teams webhook called while disabled (rag.teams.enabled=false).");
            return ResponseEntity.status(404).body(text("Webhook chua duoc bat."));
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;

        if (!verifier.verify(body, authorization)) {
            return ResponseEntity.status(401).body(text("Chu ky khong hop le."));
        }

        Parsed parsed = parse(body);
        if (parsed.question().isBlank()) {
            return ResponseEntity.ok(text("Nội dung tin nhắn trống."));
        }

        try {
            ChatRequest request = new ChatRequest();
            request.setQuestion(parsed.question());
            request.setConversationId(parsed.conversationId());

            AccessScope scope = new AccessScope("teams", Set.of("USER"), Set.of(), true);
            ChatResponse response = chatService.answer(request, scope);

            return ResponseEntity.ok(text(format(response)));
        } catch (Exception e) {
            log.error("Failed to handle the Teams webhook message", e);
            return ResponseEntity.ok(text("Xin lỗi, đã có lỗi khi tra cứu tài liệu. "
                    + "Vui lòng thử lại sau."));
        }
    }

    private record Parsed(String question, String conversationId) {
    }

    private Parsed parse(byte[] body) {
        try {
            JsonNode root = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            String text = root.path("text").asText("").strip();
            text = text.replaceAll("<at>.*?</at>", "").strip();

            String conversationId = root.path("conversation").path("id").asText(null);
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "teams-" + root.path("from").path("id").asText("unknown");
            }
            return new Parsed(text, conversationId);
        } catch (Exception e) {
            log.warn("Could not parse the Teams webhook payload: {}", e.getMessage());
            return new Parsed("", "teams-unknown");
        }
    }

    private String format(ChatResponse response) {
        StringBuilder sb = new StringBuilder(response.answer());
        if (!response.citations().isEmpty()) {
            sb.append("\n\n**Nguồn:**");
            response.citations().forEach(c -> sb.append("\n- ").append(c.fileName())
                    .append(c.headingPath() == null || c.headingPath().isBlank()
                            ? "" : " — " + c.headingPath()));
        }
        return sb.toString();
    }

    private String text(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message");
        payload.put("text", message);
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"message\",\"text\":\"Loi dinh dang phan hoi.\"}";
        }
    }
}
