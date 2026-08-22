package com.ai.aiagent.bot;

import com.ai.aiagent.common.HttpTimeouts;
import com.ai.aiagent.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class BotAuthenticator {

    private final BotProperties props;
    private final RestClient http;
    private volatile JwtDecoder decoder;

    public BotAuthenticator(BotProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder
                .requestFactory(HttpTimeouts.factory(props.getConnectorTimeoutSeconds()))
                .build();
    }

    public boolean verify(String authorization, String serviceUrl) {
        if (!props.isConfigured()) {
            log.warn("Bot credentials incomplete (app-id/app-password), rejecting every request.");
            return false;
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            log.warn("Missing 'Bearer <token>' Authorization header.");
            return false;
        }
        String token = authorization.substring(7).strip();

        try {
            Jwt jwt = decoder().decode(token);

            String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
            if (!issuerAllowed(issuer)) {
                log.warn("Token issuer '{}' is not in the allowed list {}.",
                        issuer, props.effectiveIssuers());
                return false;
            }
            if (!audienceMatches(jwt.getAudience())) {
                log.warn("Token audience {} does not match the bot app id.", jwt.getAudience());
                return false;
            }
            if (!serviceUrlMatches(jwt, serviceUrl)) {
                log.warn("Token serviceurl claim does not match the serviceUrl in the body, rejecting.");
                return false;
            }
            return true;
        } catch (JwtException e) {
            log.warn("Invalid bot token: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Bot token verification failed: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current != null) return current;
        synchronized (this) {
            if (decoder == null) {
                String jwks = jwkSetUri();
                NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(jwks).build();
                Duration skew = Duration.ofSeconds(
                        Math.max(0, props.getMaxClockSkewSeconds()));
                nimbus.setJwtValidator(new JwtTimestampValidator(skew));
                decoder = nimbus;
                log.info("Using bot signing keys from {} (clock skew allowance {}s)",
                        jwks, skew.toSeconds());
            }
            return decoder;
        }
    }

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
