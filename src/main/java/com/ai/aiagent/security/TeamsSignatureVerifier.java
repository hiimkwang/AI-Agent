package com.ai.aiagent.security;

import com.ai.aiagent.config.TeamsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

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

    public boolean verify(byte[] rawBody, String authorization) {
        if (!properties.isEnabled()) {
            return false;
        }
        String secret = properties.getHmacSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("TEAMS_HMAC_SECRET is not configured, rejecting the request.");
            return false;
        }
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            log.warn("Missing 'HMAC <signature>' Authorization header.");
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
            if (!ok) log.warn("HMAC signature mismatch, rejecting the request.");
            return ok;
        } catch (IllegalArgumentException e) {
            log.warn("The secret or the signature is not valid base64.");
            return false;
        } catch (Exception e) {
            log.warn("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

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
