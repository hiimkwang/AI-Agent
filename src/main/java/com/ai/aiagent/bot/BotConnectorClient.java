package com.ai.aiagent.bot;

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
import java.util.List;
import java.util.Map;

/**
 * Gui Activity NGUOC LAI cho Teams.
 *
 * Bot Framework la giao thuc hai chieu bat doi xung: Teams goi ta o
 * {@code POST /api/messages} va ta phai tra 200 that nhanh, roi gui cau tra loi that su
 * bang mot loi goi RIENG toi {@code serviceUrl}. Do la ly do lop nay ton tai - va cung
 * la ly do bot lam duoc nhung viec Outgoing Webhook cu khong lam duoc: bao "dang go",
 * gui nhieu tin nhan cho mot cau hoi, gui the Adaptive Card.
 *
 * {@code serviceUrl} lay tu chinh activity chu KHONG hard-code: no khac nhau theo dam
 * may (thuong mai/chinh phu) va theo vung. Da duoc {@link BotAuthenticator} doi chieu
 * voi claim trong token nen an toan de dung.
 */
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
        this.http = builder.build();
    }

    /** Hien "dang gõ..." de nguoi dung biet bot da nhan cau hoi. */
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

    /**
     * Gui the Adaptive Card. Kem luon {@code text} lam ban du phong: Teams dung chuoi do
     * cho thong bao day va cho cac client khong ve duoc the.
     */
    public void sendCard(BotActivity activity, JsonNode card, String fallbackText) {
        ObjectNode attachment = mapper.createObjectNode();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.set("content", card);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "message");
        if (fallbackText != null && !fallbackText.isBlank()) {
            payload.put("text", fallbackText);
        }
        payload.set("attachments", mapper.createArrayNode().add(attachment));
        send(activity, payload, "card");
    }

    // ============================================================ Noi bo

    private void send(BotActivity activity, ObjectNode payload, String kind) {
        if (activity.serviceUrl() == null || activity.conversationId() == null) {
            log.warn("Bot: thieu serviceUrl/conversationId, khong gui duoc {}.", kind);
            return;
        }
        // Noi tin nhan vao dung luong tra loi cua Teams
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
            // Khong nem tiep: mot tin nhan khong gui duoc thi log lai, khong duoc keo
            // sap ca luong xu ly (vi du typing loi khong duoc lam mat cau tra loi).
            log.warn("Bot: gui {} that bai: {}: {}", kind,
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Token de goi Bot Framework Connector, cache den truoc han 60 giay.
     *
     * Bot multi-tenant xin token tai tenant AO {@code botframework.com}, khong phai tenant
     * cua cong ty - day la cho hay nham va bieu hien la loi 401 kho hieu khi gui tin nhan.
     */
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

    /** Chi dung trong test/chan doan: cho biet dang xin token o tenant nao. */
    Map<String, Object> describe() {
        return Map.of("appId", props.getAppId(),
                "appType", props.getAppType().name(),
                "tokenTenant", props.outboundTokenTenant(),
                "issuers", List.copyOf(props.effectiveIssuers()));
    }
}
