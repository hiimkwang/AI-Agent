package com.ai.aiagent.security;

import com.ai.aiagent.config.EntraProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/api/v1/rag")
public class AuthController {

    private final EntraProperties entraProps;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final ObjectProvider<GraphDirectoryClient> graph;

    public AuthController(EntraProperties entraProps,
                          ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                          ObjectProvider<GraphDirectoryClient> graph) {
        this.entraProps = entraProps;
        this.clientRegistrations = clientRegistrations;
        this.graph = graph;
    }

    private Map<String, String> groupNames(AccessScope scope) {
        GraphDirectoryClient client = graph.getIfAvailable();
        if (client == null || scope.entraGroups().isEmpty()) return Map.of();
        return client.groupNames(scope.entraGroups());
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        boolean sso = entraProps.isEnabled() && clientRegistrations.getIfAvailable() != null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ssoEnabled", sso);
        if (sso) {
            out.put("loginUrl", entraProps.authorizationUri());
            out.put("logoutUrl", "/logout");
        }

        AccessScope scope = authenticatedScope();
        if (scope == null) {
            out.put("authenticated", false);
            return out;
        }

        out.put("authenticated", true);
        out.put("id", scope.clientId());
        out.put("upn", scope.upn());
        out.put("displayName", scope.displayId());
        out.put("roles", new TreeSet<>(scope.roles()));
        out.put("admin", scope.isAdmin());
        out.put("allDepartments", scope.allDepartments());
        out.put("departments", new TreeSet<>(scope.departments()));
        out.put("entraGroups", List.copyOf(scope.entraGroups()));
        // Ids alone are unusable in a UI - nobody knows which GUID is which department.
        out.put("entraGroupNames", groupNames(scope));
        out.put("via", scope.upn() == null ? "api-key" : "entra");
        return out;
    }

    private AccessScope authenticatedScope() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) return null;
        return a.getPrincipal() instanceof AccessScope scope ? scope : null;
    }
}
