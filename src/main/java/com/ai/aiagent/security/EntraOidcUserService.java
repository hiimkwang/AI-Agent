package com.ai.aiagent.security;

import com.ai.aiagent.config.EntraProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class EntraOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final EntraProperties props;
    private final EntraScopeService scopes;

    public EntraOidcUserService(EntraProperties props, EntraScopeService scopes) {
        this.props = props;
        this.scopes = scopes;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = delegate.loadUser(request);

        String tenant = claim(user, "tid");
        String objectId = claim(user, "oid");
        String upn = firstNonBlank(claim(user, "preferred_username"), claim(user, "email"),
                user.getEmail());

        requireTenant(tenant);
        requireAllowedDomain(upn);
        if (objectId == null) {
            throw denied("Token khong co claim 'oid'. Kiem tra cau hinh app registration.");
        }

        scopes.recordLogin(objectId, upn, user.getFullName());
        AccessScope scope = scopes.scopeOf(objectId, upn, appRoles(user));
        log.info("Entra sign-in: {} (oid={}, roles={}, departments={})",
                upn, objectId, scope.roles(),
                scope.allDepartments() ? "*" : scope.departments());

        return new DefaultOidcUser(authorities(scope), request.getIdToken(), user.getUserInfo(),
                nameAttributeKey(user));
    }

    static List<String> appRoles(OidcUser user) {
        Object raw = user.getClaims().get("roles");
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) out.add(String.valueOf(o));
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    static Set<GrantedAuthority> authorities(AccessScope scope) {
        Set<GrantedAuthority> out = new LinkedHashSet<>();
        for (String role : scope.roles()) {
            out.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return out;
    }

    private void requireTenant(String tenant) {
        String expected = props.getTenantId();
        if (expected == null || expected.isBlank()) return;
        if (tenant == null || !tenant.equalsIgnoreCase(expected.strip())) {
            log.warn("Sign-in rejected: tenant '{}' is not the company tenant.", tenant);
            throw denied("Tài khoản không thuộc tổ chức được phép truy cập.");
        }
    }

    private void requireAllowedDomain(String upn) {
        List<String> allowed = props.getAllowedEmailDomains();
        if (allowed == null || allowed.isEmpty()) return;
        String value = upn == null ? "" : upn.toLowerCase(Locale.ROOT);
        boolean ok = allowed.stream()
                .filter(d -> d != null && !d.isBlank())
                .anyMatch(d -> value.endsWith("@" + d.strip().toLowerCase(Locale.ROOT)));
        if (!ok) {
            log.warn("Sign-in rejected: '{}' is not in the allowed domains {}.", upn, allowed);
            throw denied("Chỉ tài khoản " + String.join(", ", allowed) + " được phép đăng nhập.");
        }
    }

    private static String nameAttributeKey(OidcUser user) {
        if (user.getClaims().containsKey("preferred_username")) return "preferred_username";
        if (user.getClaims().containsKey("email")) return "email";
        return "sub";
    }

    private static OAuth2AuthenticationException denied(String message) {
        return new OAuth2AuthenticationException(new OAuth2Error("access_denied", message, null),
                message);
    }

    private static String claim(OidcUser user, String name) {
        Object v = user.getClaims().get(name);
        String s = v == null ? null : String.valueOf(v).strip();
        return s == null || s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }
}
