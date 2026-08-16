package com.ai.aiagent.security;

import com.ai.aiagent.config.EntraProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Doc thong tin nguoi dung va thanh vien nhom tu Microsoft Graph, che do APP-ONLY
 * (client credentials).
 *
 * TAI SAO APP-ONLY chu khong dung claim {@code groups} trong token:
 *
 *  1) Bot Teams (P2) chi co {@code aadObjectId} cua nguoi gui, KHONG co token cua
 *     nguoi dung - khong co claim nao ma doc. Da buoc phai co duong app-only thi
 *     dung chung cho ca web, de web va bot phan quyen bang DUNG MOT logic.
 *  2) Claim {@code groups} bi Entra thay bang {@code _claim_names}/{@code _claim_sources}
 *     khi nguoi dung thuoc qua ~200 nhom, luc do van phai goi Graph. Viet mot duong
 *     luon dung tot hon hai duong ma mot duong chi dung 95% truong hop.
 *
 * Quyen can admin consent: {@code User.Read.All} + {@code GroupMember.Read.All}.
 *
 * Bean nay chi ton tai khi {@code rag.entra.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class GraphDirectoryClient {

    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    /**
     * @param department phong ban theo ho so Entra - chi de hien thi/audit, KHONG dung
     *                   phan quyen (phan quyen dua tren nhom, xem {@link EntraScopeService})
     */
    public record Profile(String upn, String displayName, String department, String jobTitle) {
    }

    private final EntraProperties props;
    private final RestClient http;

    /** Token app-only, dung chung cho moi nguoi dung nen cache o cap client. */
    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public GraphDirectoryClient(EntraProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    public boolean isReady() {
        return props.isGraphEnabled() && props.hasGraphCredentials();
    }

    /**
     * Nhom TRANSITIVE cua nguoi dung (gom ca nhom long trong nhom).
     *
     * Dung {@code getMemberGroups} thay vi {@code transitiveMemberOf} vi no chi tra ve
     * danh sach objectId - nhe hon nhieu va dung du dung cho phan quyen.
     *
     * @return objectId chu thuong; rong khi Graph loi (goi ham phai coi "rong" la
     *         "khong co quyen gi", KHONG duoc coi la "co moi quyen")
     */
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
            // Fail CLOSED: khong biet nguoi nay thuoc nhom nao thi khong mo quyen nao.
            log.warn("Graph: khong lay duoc nhom cua {}: {}: {}", userObjectId,
                    e.getClass().getSimpleName(), e.getMessage());
            return Set.of();
        }
    }

    /** Ho so nguoi dung. Null khi khong lay duoc - goi ham phai fallback sang claim trong token. */
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
            log.warn("Graph: khong lay duoc ho so cua {}: {}", userObjectId, e.getMessage());
            return null;
        }
    }

    /**
     * Ten hien thi cua nhom - CHI de hien trong giao dien quan tri, khong bao gio dung
     * de phan quyen (ten nhom doi duoc, objectId thi khong).
     */
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
            log.debug("Graph: khong lay duoc ten nhom {}: {}", groupObjectId, e.getMessage());
            return null;
        }
    }

    // ============================================================ Token app-only

    /**
     * Access token app-only, cache den truoc han 60 giay.
     *
     * @throws IllegalStateException khi khong lay duoc token - de goi ham log warn va
     *         tra ve "khong co quyen", chu khong am tham bo qua ACL
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
            log.debug("Graph: lay token app-only moi, han {}.", tokenExpiresAt);
            return token;
        }
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    /** Chuan hoa danh sach objectId ve chu thuong de so khop khong phu thuoc chu hoa. */
    static Set<String> normalizeIds(List<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String id : ids) {
            if (id != null && !id.isBlank()) out.add(id.strip().toLowerCase());
        }
        return out;
    }
}
