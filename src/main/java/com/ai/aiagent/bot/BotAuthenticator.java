package com.ai.aiagent.bot;

import com.ai.aiagent.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;

/**
 * Xac thuc request den {@code POST /api/messages} la that su do Bot Framework gui.
 *
 * Day la BIEN PHAP BAO MAT CHINH cua endpoint bot. Khong the loc theo IP: dai IP cua
 * Azure Bot Service rat rong va thay doi. Bu lai, token JWT do Microsoft ky la bang
 * chung manh - mien la kiem tra DU cac dieu kien:
 *
 *   1. Chu ky hop le theo khoa cong khai lay tu tai lieu OpenID cua Bot Framework
 *   2. {@code iss} nam trong danh sach cho phep
 *   3. {@code aud} dung bang Microsoft App ID cua bot
 *   4. {@code serviceurl} khop {@code activity.serviceUrl}
 *   5. Con han ({@code exp}/{@code nbf}), cho lech dong ho toi da 5 phut
 *
 * Bo dieu kien 4 la mot lo tinh vi: ke tan cong co token hop le cua BOT KHAC co the
 * dua ta gui cau tra loi ve dia chi cua ho. Vi vay khong duoc bo qua.
 */
@Component
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class BotAuthenticator {

    private final BotProperties props;
    private final RestClient http;
    private volatile JwtDecoder decoder;

    public BotAuthenticator(BotProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    /**
     * @param authorization gia tri header {@code Authorization} (dang {@code Bearer <jwt>})
     * @param serviceUrl    {@code activity.serviceUrl} lay tu body
     * @return true neu request that su den tu Bot Framework va danh cho bot NAY
     */
    public boolean verify(String authorization, String serviceUrl) {
        if (!props.isConfigured()) {
            log.warn("Bot: chua cau hinh day du (app-id/app-password) -> tu choi moi request.");
            return false;
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            log.warn("Bot: thieu header Authorization dang 'Bearer <token>'.");
            return false;
        }
        String token = authorization.substring(7).strip();

        try {
            Jwt jwt = decoder().decode(token);

            String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
            if (!issuerAllowed(issuer)) {
                log.warn("Bot: issuer '{}' khong nam trong danh sach cho phep {}.",
                        issuer, props.effectiveIssuers());
                return false;
            }
            if (!audienceMatches(jwt.getAudience())) {
                // Token that, nhung phat cho bot khac.
                log.warn("Bot: audience {} khong khop app-id cua bot.", jwt.getAudience());
                return false;
            }
            if (!serviceUrlMatches(jwt, serviceUrl)) {
                log.warn("Bot: claim serviceurl khong khop serviceUrl trong body -> tu choi.");
                return false;
            }
            return true;
        } catch (JwtException e) {
            log.warn("Bot: token khong hop le: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Bot: loi khi kiem tra token: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    // ============================================================ Noi bo

    /**
     * Khoi tao muon va giu lai. {@code NimbusJwtDecoder} tu cache khoa va tu lay lai khi
     * gap {@code kid} la, nen khong can tu quan ly vong doi khoa.
     */
    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current != null) return current;
        synchronized (this) {
            if (decoder == null) {
                String jwks = jwkSetUri();
                NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(jwks).build();
                nimbus.setJwtValidator(JwtValidators.createDefault());
                decoder = nimbus;
                log.info("Bot: dung khoa ky tu {}", jwks);
            }
            return decoder;
        }
    }

    /**
     * Doc {@code jwks_uri} tu tai lieu OpenID.
     *
     * Khong hard-code duong dan khoa: Microsoft co doi no truoc day, va tai lieu OpenID
     * chinh la cho de biet duong dan hien tai.
     */
    private String jwkSetUri() {
        String metadataUrl = props.effectiveMetadataUrl();
        JsonNode metadata = http.get().uri(metadataUrl).retrieve().body(JsonNode.class);
        String jwks = metadata == null ? null : metadata.path("jwks_uri").asText(null);
        if (jwks == null || jwks.isBlank()) {
            throw new IllegalStateException(
                    "Khong doc duoc jwks_uri tu " + metadataUrl);
        }
        return jwks;
    }

    private boolean issuerAllowed(String issuer) {
        if (issuer == null) return false;
        String trimmed = trimSlash(issuer);
        return props.effectiveIssuers().stream()
                .anyMatch(allowed -> trimSlash(allowed).equalsIgnoreCase(trimmed));
    }

    private boolean audienceMatches(List<String> audience) {
        return audience != null && audience.stream()
                .anyMatch(a -> a != null && a.strip().equalsIgnoreCase(props.getAppId().strip()));
    }

    /**
     * Token Bot Framework mang claim {@code serviceurl}. Khi co, no PHAI khop dia chi
     * trong body - nho vay ke tan cong khong the dua ta gui du lieu sang may chu cua ho.
     * Token single-tenant khong luon co claim nay; khi vang mat thi bo qua kiem tra.
     */
    private boolean serviceUrlMatches(Jwt jwt, String serviceUrl) {
        Object claim = jwt.getClaims().get("serviceurl");
        if (claim == null) return true;
        if (serviceUrl == null || serviceUrl.isBlank()) return false;
        return trimSlash(String.valueOf(claim)).equalsIgnoreCase(trimSlash(serviceUrl));
    }

    static String trimSlash(String value) {
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
