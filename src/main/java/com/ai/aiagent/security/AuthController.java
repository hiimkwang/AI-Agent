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

/**
 * "Toi la ai" - giao dien goi endpoint nay dau tien de biet nen hien nut dang nhap
 * Entra, hop nhap API key, hay ten nguoi dung.
 *
 * CO Y de cong khai (xem {@link SecurityConfig}): khi chua xac thuc no chi tra ve
 * {@code authenticated=false} kem duong dan dang nhap, khong lo thong tin nao.
 */
@RestController
@RequestMapping("/api/v1/rag")
public class AuthController {

    private final EntraProperties entraProps;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    public AuthController(EntraProperties entraProps,
                          ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.entraProps = entraProps;
        this.clientRegistrations = clientRegistrations;
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
        // Nguoi dung tu doc duoc minh dang thuoc nhom nao => tu chan doan duoc
        // "vi sao toi khong xem duoc tai lieu X" ma khong phai mo ticket.
        out.put("entraGroups", List.copyOf(scope.entraGroups()));
        // Duong dang nhap: qua Entra hay qua API key - huu ich khi chan doan
        out.put("via", scope.upn() == null ? "api-key" : "entra");
        return out;
    }

    /**
     * @return pham vi cua nguoi dung DA xac thuc, hoac null.
     *         Khong dung {@code CurrentScope.get()} o day vi no tra ve
     *         {@code AccessScope.internal()} (quyen ADMIN) khi khong co xac thuc -
     *         dung cho tac vu nen, nhung o endpoint cong khai thi se bao sai la
     *         "ban dang la admin".
     */
    private AccessScope authenticatedScope() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) return null;
        return a.getPrincipal() instanceof AccessScope scope ? scope : null;
    }
}
