package com.ai.aiagent.security;

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

/**
 * Bien phien dang nhap Entra thanh {@link AccessScope} cho tung request.
 *
 * TAI SAO CAN FILTER NAY: sau khi dang nhap OIDC, principal trong SecurityContext la
 * {@code OidcUser}, con toan bo tang duoi ({@code CurrentScope.get()},
 * {@code HybridRetriever}, {@code AnswerCacheService}) chi biet {@link AccessScope}.
 * Filter nay lam cau noi, nho vay khong mot controller nao phai biet request den tu
 * trinh duyet hay tu API key.
 *
 * TAI SAO TINH LAI MOI REQUEST thay vi lay quyen chup luc dang nhap: phien trinh duyet
 * song hang gio. Neu quyen dong bang theo phien thi nguoi bi go quyen van dung duoc den
 * khi ho tu dang xuat. Tinh lai la re vi phan dat (goi Graph) da co cache 15 phut trong
 * {@link EntraScopeService}.
 *
 * KHONG ghi de nguoc vao session: tu Spring Security 6, {@code SecurityContextHolderFilter}
 * chi doc chu khong luu lai, nen thay principal trong holder chi anh huong request hien tai.
 */
@Component
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class EntraScopeFilter extends OncePerRequestFilter {

    private final EntraScopeService scopes;

    public EntraScopeFilter(EntraScopeService scopes) {
        this.scopes = scopes;
    }

    /**
     * Bo qua cac duong cua chinh luong OAuth2.
     *
     * Dac biet {@code /logout}: {@code OidcClientInitiatedLogoutSuccessHandler} can dung
     * {@link OAuth2AuthenticationToken} de tim ra ClientRegistration ma goi endpoint dang
     * xuat cua Entra. Thay principal o day se lam dang xuat chi con la xoa cookie phia
     * minh, con phien ben Entra thi van con.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
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
                // Khong suy duoc pham vi thi COI NHU CHUA XAC THUC, khong di tiep voi
                // quyen cu - de tang duoi tra 401/403 thay vi mo nham du lieu.
                log.warn("Khong dung duoc AccessScope tu phien Entra: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).strip();
    }
}
