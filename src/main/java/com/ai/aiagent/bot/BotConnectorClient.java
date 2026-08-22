package com.ai.aiagent.bot;

import com.ai.aiagent.common.HttpTimeouts;
import com.ai.aiagent.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class BotConnectorClient {

    private final BotProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public BotConnectorClient(BotProperties props, ObjectMapper mapper, RestClient.Builder builder) {
        this.props = props;
        this.mapper = mapper;
        this.http = builder
                .requestFactory(HttpTimeouts.factory(props.getConnectorTimeoutSeconds()))
                .build();
    }

    public void sendTyping(BotActivity activity) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "typing");
        send(activity, payload, "typing");
    }

    public void sendText(BotActivity activity, String text) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "message");
        payload.put("textFormat", "markdown");
        payload.put("text", text);
        send(activity, payload, "text");
    }

    public void sendCard(BotActivity activity, JsonNode card, String fallbackText) {
        ObjectNode attachment = mapper.createObjectNode();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.set("content", card);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "message");
        // NOT "text": an activity carrying both text and a card makes Teams render the answer
        // twice - once as a plain bubble, once inside the card. "summary" is what Teams uses for
        // the toast/notification preview and is never drawn in the conversation.
        if (fallbackText != null && !fallbackText.isBlank()) {
            payload.put("summary", fallbackText);
        }
        payload.set("attachments", mapper.createArrayNode().add(attachment));
        send(activity, payload, "card");
    }

    private void send(BotActivity activity, ObjectNode payload, String kind) {
        if (activity.serviceUrl() == null || activity.conversationId() == null) {
            log.warn("Missing serviceUrl/conversationId, cannot send {}.", kind);
            return;
        }
        if (activity.id() != null) {
            payload.put("replyToId", activity.id());
        }
        String base = activity.serviceUrl().endsWith("/")
                ? activity.serviceUrl() : activity.serviceUrl() + "/";

        try {
            http.post()
                    .uri(base + "v3/conversations/{conversationId}/activities",
                            activity.conversationId())
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // serviceUrl is in the message on purpose: without it there is no way to tell a
            // rejected token from the wrong regional endpoint, and no way to retry by hand.
            log.warn("Sending {} failed (serviceUrl={}): {}: {}", kind, base,
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private String token() {
        String current = cachedToken;
        if (current != null && Instant.now().isBefore(tokenExpiresAt)) {
            return current;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
                return cachedToken;
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", props.getAppId());
            form.add("client_secret", props.getAppPassword());
            form.add("scope", "https://api.botframework.com/.default");

            JsonNode body = http.post()
                    .uri("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
                            props.outboundTokenTenant())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            String token = body == null ? null : body.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Khong xin duoc token de goi Bot Framework. "
                        + "Kiem tra rag.bot.app-id / app-password va app-type.");
            }
            long expiresIn = body.path("expires_in").asLong(3600);
            cachedToken = token;
            tokenExpiresAt = Instant.now().plus(Duration.ofSeconds(Math.max(60, expiresIn - 60)));
            return token;
        }
    }

    Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appId", props.getAppId());
        out.put("appType", props.getAppType().name());
        out.put("tokenTenant", props.outboundTokenTenant());
        out.put("issuers", List.copyOf(props.effectiveIssuers()));
        out.put("metadataUrl", props.effectiveMetadataUrl());
        // Never the secret itself, only whether one is present.
        out.put("appPasswordConfigured", !props.getAppPassword().isBlank());
        out.put("configured", props.isConfigured());
        return out;
    }

    /**
     * Asks for an outbound token and reports only whether it worked. This is the check
     * that separates "bot never received the message" from "bot answered but could not
     * send", which otherwise look identical from the outside.
     */
    Map<String, Object> tokenProbe() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            token();
            out.put("ok", true);
            out.put("expiresAt", tokenExpiresAt.toString());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", abbreviate(e.getMessage()));
            out.put("hint", "Kiem tra BOT_APP_PASSWORD (secret het han?) va BOT_APP_TYPE "
                    + "/ BOT_TENANT_ID.");
        }
        return out;
    }

    private static String abbreviate(String message) {
        if (message == null) return "khong ro nguyen nhan";
        String flat = message.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
