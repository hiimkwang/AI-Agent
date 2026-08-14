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

/**
 * Webhook cho Microsoft Teams (Outgoing Webhook).
 *
 * Ba loi cua ban cu duoc sua o day:
 *
 *  1) KHONG XAC THUC. Bat ky ai biet URL cung goi duoc, va moi request tieu 3-4 loi
 *     goi LLM. Gio bat buoc kiem tra chu ky HMAC-SHA256 tren BODY THO.
 *  2) NOI CHUOI JSON BANG TAY. {@code formatTeamsResponse} chi escape dau ngoac kep va
 *     \n, nen cau tra loi chua dau \ hoac tab la sinh JSON sai cu phap va Teams khong
 *     hien gi. Gio dung Jackson.
 *  3) MAT MULTI-TURN. {@code retrieveAndAnswer} truyen {@code conversationId = null} du
 *     he thong da co san bo nho hoi thoai, nen moi cau tren Teams la mot phien moi.
 *     Gio dung {@code conversation.id} cua Teams lam conversationId.
 */
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

    /**
     * @param rawBody body THO - phai la byte nguyen ven, khong duoc parse roi serialize
     *                lai, neu khong chu ky HMAC se khong khop
     */
    @PostMapping(value = "/teams-webhook", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(@RequestBody(required = false) byte[] rawBody,
                                          @RequestHeader(value = HttpHeaders.AUTHORIZATION,
                                                  required = false) String authorization) {
        if (!properties.isEnabled()) {
            log.debug("Teams webhook bi goi nhung dang tat (rag.teams.enabled=false).");
            return ResponseEntity.status(404).body(text("Webhook chua duoc bat."));
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;

        if (!verifier.verify(body, authorization)) {
            // Khong tiet lo ly do cu the de khong giup do chu ky
            return ResponseEntity.status(401).body(text("Chu ky khong hop le."));
        }

        Parsed parsed = parse(body);
        if (parsed.question().isBlank()) {
            return ResponseEntity.ok(text("Nội dung tin nhắn trống."));
        }

        try {
            ChatRequest request = new ChatRequest();
            request.setQuestion(parsed.question());
            // Giu ngu canh hoi thoai theo dung cuoc tro chuyen tren Teams
            request.setConversationId(parsed.conversationId());

            AccessScope scope = new AccessScope("teams", Set.of("USER"), Set.of(), true);
            ChatResponse response = chatService.answer(request, scope);

            return ResponseEntity.ok(text(format(response)));
        } catch (Exception e) {
            log.error("Loi khi xu ly tin nhan Teams", e);
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
            // Teams chen mention dang "<at>Bot</at>" vao dau tin nhan
            text = text.replaceAll("<at>.*?</at>", "").strip();

            String conversationId = root.path("conversation").path("id").asText(null);
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "teams-" + root.path("from").path("id").asText("unknown");
            }
            return new Parsed(text, conversationId);
        } catch (Exception e) {
            log.warn("Khong doc duoc payload Teams: {}", e.getMessage());
            return new Parsed("", "teams-unknown");
        }
    }

    /** Dinh dang phan hoi + gan nguon, dung Jackson nen khong the sai cu phap JSON. */
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
            // Khong the xay ra voi Map<String,String>, nhung khong duoc nem loi o day
            return "{\"type\":\"message\",\"text\":\"Loi dinh dang phan hoi.\"}";
        }
    }
}
