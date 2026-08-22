package com.ai.aiagent.security;

import com.ai.aiagent.common.HttpTimeouts;
import com.ai.aiagent.config.EntraProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class GraphDirectoryClient {

    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    public record Profile(String upn, String displayName, String department, String jobTitle) {
    }

    private final EntraProperties props;
    private final RestClient http;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // Group names change rarely; a permission screen asks for the same ones repeatedly.
    private final Cache<String, String> nameCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    public GraphDirectoryClient(EntraProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder
                .requestFactory(HttpTimeouts.factory(props.getGraphTimeoutSeconds()))
                .build();
    }

    public boolean isReady() {
        return props.isGraphEnabled() && props.hasGraphCredentials();
    }

    public Set<String> memberGroups(String userObjectId) {
        if (!isReady() || userObjectId == null || userObjectId.isBlank()) return Set.of();
        try {
            JsonNode body = http.post()
                    .uri(GRAPH + "/users/{id}/getMemberGroups", userObjectId)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"securityEnabledOnly\":false}")
                    .retrieve()
                    .body(JsonNode.class);

            Set<String> out = new LinkedHashSet<>();
            if (body != null) {
                for (JsonNode id : body.path("value")) {
                    String v = id.asText("").strip().toLowerCase();
                    if (!v.isEmpty()) out.add(v);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not read the groups of {}: {}: {}", userObjectId,
                    e.getClass().getSimpleName(), e.getMessage());
            // Fail closed: unknown group membership grants nothing, not everything.
            return Set.of();
        }
    }

    public Profile profile(String userObjectId) {
        if (!isReady() || userObjectId == null || userObjectId.isBlank()) return null;
        try {
            JsonNode u = http.get()
                    .uri(GRAPH + "/users/{id}?$select=id,userPrincipalName,displayName,department,jobTitle",
                            userObjectId)
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .body(JsonNode.class);
            if (u == null) return null;
            return new Profile(
                    text(u, "userPrincipalName"),
                    text(u, "displayName"),
                    text(u, "department"),
                    text(u, "jobTitle"));
        } catch (Exception e) {
            log.warn("Could not read the profile of {}: {}", userObjectId, e.getMessage());
            return null;
        }
    }

    public String groupName(String groupObjectId) {
        if (!isReady()) return null;
        try {
            JsonNode g = http.get()
                    .uri(GRAPH + "/groups/{id}?$select=displayName", groupObjectId)
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .body(JsonNode.class);
            return g == null ? null : text(g, "displayName");
        } catch (Exception e) {
            log.debug("Could not read the name of group {}: {}", groupObjectId, e.getMessage());
            return null;
        }
    }

    /**
     * Display names for group object ids, cached because a permission screen asks for the
     * same 15-20 groups on every page load. Ids Graph cannot resolve are simply absent -
     * the caller falls back to showing the raw id rather than hiding the group.
     */
    public Map<String, String> groupNames(Collection<String> groupObjectIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!isReady() || groupObjectIds == null) return out;
        for (String raw : groupObjectIds) {
            if (raw == null || raw.isBlank()) continue;
            String id = raw.strip().toLowerCase();
            String name = nameCache.get(id, this::groupName);
            if (name != null && !name.isBlank()) out.put(id, name);
        }
        return out;
    }

    /** Object id for a UPN, so an admin can grant by email instead of GUID. */
    public String objectIdOfUpn(String upn) {
        if (!isReady() || upn == null || upn.isBlank()) return null;
        try {
            JsonNode u = http.get()
                    .uri(GRAPH + "/users/{upn}?$select=id", upn.strip())
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .body(JsonNode.class);
            return u == null ? null : text(u, "id");
        } catch (Exception e) {
            log.warn("Could not find the user '{}': {}", upn, e.getMessage());
            return null;
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
            form.add("client_id", props.getClientId());
            form.add("client_secret", props.getClientSecret());
            form.add("scope", "https://graph.microsoft.com/.default");

            JsonNode body = http.post()
                    .uri("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
                            props.getTenantId())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            String token = body == null ? null : body.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Entra khong tra ve access_token cho Graph.");
            }
            long expiresIn = body.path("expires_in").asLong(3600);
            cachedToken = token;
            tokenExpiresAt = Instant.now().plus(Duration.ofSeconds(Math.max(60, expiresIn - 60)));
            log.debug("Fetched a new app-only Graph token, expires at {}.", tokenExpiresAt);
            return token;
        }
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    static Set<String> normalizeIds(List<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String id : ids) {
            if (id != null && !id.isBlank()) out.add(id.strip().toLowerCase());
        }
        return out;
    }
}
