package com.ai.aiagent.security;

import com.ai.aiagent.config.SecurityProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;
    private final Map<String, AccessScope> byKey = new LinkedHashMap<>();

    public ApiKeyAuthFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void index() {
        for (SecurityProperties.Client c : properties.getClients()) {
            if (!c.isUsable()) continue;
            Set<String> roles = splitToSet(c.getRoles(), true);
            if (roles.isEmpty()) roles = Set.of("USER");
            Set<String> depts = splitToSet(c.getDepartments(), false);
            boolean all = depts.contains("*") || depts.isEmpty();
            if (all) depts = Set.of();
            byKey.put(c.getKey(), new AccessScope(c.getId(), roles, depts, all));
        }
        if (byKey.isEmpty()) {
            if (properties.isAllowAnonymous()) {
                log.warn("""
                        ============================================================
                        SECURITY WARNING: no API key is configured and
                        rag.security.allow-anonymous=true, so EVERY request gets
                        ADMIN rights. Development machines only.
                        Set RAG_ADMIN_API_KEY / RAG_USER_API_KEY before deploying.
                        ============================================================""");
            } else {
                log.error("No API key configured, so EVERY request will be rejected with 401. "
                        + "Set RAG_ADMIN_API_KEY (and RAG_USER_API_KEY).");
            }
        } else {
            log.info("Loaded {} API key(s) for: {}", byKey.size(),
                    byKey.values().stream().map(AccessScope::clientId).collect(Collectors.joining(", ")));
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            authenticate(AccessScope.internal());
            chain.doFilter(request, response);
            return;
        }

        String presented = header(request);
        AccessScope scope = resolve(presented);

        if (scope == null && byKey.isEmpty() && properties.isAllowAnonymous()) {
            scope = new AccessScope("anonymous", Set.of("ADMIN", "USER"), Set.of(), true);
        }

        if (scope != null) {
            authenticate(scope);
        } else {
            clearStaleApiKeyContext();
        }
        chain.doFilter(request, response);
    }

    // Only clears a context this filter set itself. Clearing unconditionally would
    // wipe the OIDC session loaded for requests that carry no API key.
    private void clearStaleApiKeyContext() {
        var existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || existing.getPrincipal() instanceof AccessScope) {
            SecurityContextHolder.clearContext();
        }
    }

    private String header(HttpServletRequest request) {
        String v = request.getHeader(properties.getHeaderName());
        if (StringUtils.hasText(v)) return v.trim();
        String auth = request.getHeader("Authorization");
        if (StringUtils.hasText(auth) && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private AccessScope resolve(String presented) {
        if (presented == null || presented.isEmpty()) return null;
        byte[] given = presented.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AccessScope matched = null;
        for (Map.Entry<String, AccessScope> e : byKey.entrySet()) {
            byte[] expected = e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (java.security.MessageDigest.isEqual(given, expected)) {
                matched = e.getValue();
            }
        }
        return matched;
    }

    private void authenticate(AccessScope scope) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String r : scope.roles()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
        }
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(scope, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static Set<String> splitToSet(String csv, boolean upper) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> "*".equals(s) ? s : (upper ? s.toUpperCase() : s.toLowerCase()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
