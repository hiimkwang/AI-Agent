package com.ai.aiagent.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Diem vao cua bot Teams: {@code POST /api/messages}.
 *
 * Duong dan nay la quy uoc cua Bot Framework va phai khop voi Messaging endpoint khai
 * trong Azure Bot resource.
 *
 * Hop dong quan trong nhat cua endpoint nay la TRA LOI THAT NHANH. Bot Framework coi
 * phan hoi cham la that bai va se GUI LAI, nghia la neu xu ly RAG ngay tai day thi mot
 * cau hoi se bien thanh nhieu luot tra loi trung nhau va nhieu lan goi LLM. Vi vay:
 * xac thuc -> nhan viec -> 200, con lai chay o luong nen ({@link TeamsBotService}).
 *
 * Khong dung {@code /api/v1/rag/**} de tach bach: duong nay xac thuc bang JWT cua
 * Microsoft, khong phai API key, va {@code SecurityConfig} mo no o tang Spring Security.
 */
@RestController
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class TeamsBotController {

    private final BotAuthenticator authenticator;
    private final TeamsBotService botService;
    private final ObjectMapper mapper;

    public TeamsBotController(BotAuthenticator authenticator, TeamsBotService botService,
                              ObjectMapper mapper) {
        this.authenticator = authenticator;
        this.botService = botService;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/messages", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        JsonNode root;
        try {
            root = mapper.readTree(new String(rawBody == null ? new byte[0] : rawBody,
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Bot: payload khong phai JSON hop le: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        String serviceUrl = root.path("serviceUrl").asText(null);
        // Xac thuc TRUOC khi lam bat cu viec gi ton kem. Endpoint nay cong khai tren
        // Internet; khong co buoc nay thi ai biet URL cung quay duoc han muc LLM.
        if (!authenticator.verify(authorization, serviceUrl)) {
            return ResponseEntity.status(401).build();
        }

        BotActivity activity = BotActivity.from(root);
        botService.submit(activity);
        return ResponseEntity.ok().build();
    }
}
