package com.ai.aiagent.security;

import com.ai.aiagent.config.TeamsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Xac thuc chu ky HMAC-SHA256 cua Microsoft Teams Outgoing Webhook.
 *
 * Teams gui: {@code Authorization: HMAC <base64-signature>}, trong do
 * signature = HMAC-SHA256(raw body bytes, base64Decode(secret)).
 *
 * Truoc day endpoint webhook khong xac thuc gi ca, nghia la bat ky ai biet URL
 * cung goi duoc va moi request tieu 3-4 loi goi LLM.
 */
@Component
@Slf4j
public class TeamsSignatureVerifier {

    private static final String PREFIX = "HMAC ";
    private final TeamsProperties properties;

    public TeamsSignatureVerifier(TeamsProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * @param rawBody       body THO, nguyen ven tung byte (khong duoc parse roi serialize lai)
     * @param authorization gia tri header Authorization
     */
    public boolean verify(byte[] rawBody, String authorization) {
        if (!properties.isEnabled()) {
            return false;
        }
        String secret = properties.getHmacSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Teams webhook: chua cau hinh TEAMS_HMAC_SECRET -> tu choi request.");
            return false;
        }
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            log.warn("Teams webhook: thieu header Authorization dang 'HMAC <signature>'.");
            return false;
        }
        try {
            byte[] key = Base64.getDecoder().decode(secret.trim());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody);

            byte[] provided = Base64.getDecoder()
                    .decode(authorization.substring(PREFIX.length()).trim());

            boolean ok = MessageDigest.isEqual(expected, provided);
            if (!ok) log.warn("Teams webhook: chu ky HMAC khong khop -> tu choi request.");
            return ok;
        } catch (IllegalArgumentException e) {
            log.warn("Teams webhook: secret hoac signature khong phai base64 hop le.");
            return false;
        } catch (Exception e) {
            log.warn("Teams webhook: loi khi kiem tra chu ky: {}", e.getMessage());
            return false;
        }
    }

    /** Chu ky de Teams xac thuc phan hoi cua chung ta (Teams khong bat buoc, nhung nen co). */
    public String signResponse(String body) {
        try {
            byte[] key = Base64.getDecoder().decode(properties.getHmacSecret().trim());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return PREFIX + Base64.getEncoder()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
