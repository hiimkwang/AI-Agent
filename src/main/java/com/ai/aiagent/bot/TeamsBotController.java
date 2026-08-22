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
            log.warn("Bot payload is not valid JSON: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        String serviceUrl = root.path("serviceUrl").asText(null);
        if (!authenticator.verify(authorization, serviceUrl)) {
            return ResponseEntity.status(401).build();
        }

        BotActivity activity = BotActivity.from(root);
        botService.submit(activity);
        return ResponseEntity.ok().build();
    }
}
