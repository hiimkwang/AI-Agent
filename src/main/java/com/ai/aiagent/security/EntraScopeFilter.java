package com.ai.aiagent.security;

import com.ai.aiagent.common.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class EntraScopeFilter extends OncePerRequestFilter {

    private final EntraScopeService scopes;

    public EntraScopeFilter(EntraScopeService scopes) {
        this.scopes = scopes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = RequestPaths.within(request);
        return p.startsWith("/oauth2/") || p.startsWith("/login") || p.startsWith("/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication current = context.getAuthentication();

        if (current instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser user) {
            try {
                String objectId = str(user.getClaims().get("oid"));
                String upn = user.getPreferredUsername() != null
                        ? user.getPreferredUsername() : user.getEmail();
                AccessScope scope = scopes.scopeOf(objectId, upn,
                        EntraOidcUserService.appRoles(user));

                UsernamePasswordAuthenticationToken replacement =
                        new UsernamePasswordAuthenticationToken(scope, null,
                                EntraOidcUserService.authorities(scope));
                replacement.setDetails(token.getDetails());

                SecurityContext fresh = SecurityContextHolder.createEmptyContext();
                fresh.setAuthentication(replacement);
                SecurityContextHolder.setContext(fresh);
            } catch (Exception e) {
                log.warn("Could not build an AccessScope from the Entra session: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).strip();
    }
}
