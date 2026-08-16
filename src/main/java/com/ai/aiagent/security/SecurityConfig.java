package com.ai.aiagent.security;

import com.ai.aiagent.config.EntraProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.Map;

/**
 * Phan quyen theo duong dan.
 *
 * Nguyen tac: mac dinh TU CHOI, chi mo dung nhung gi can.
 *
 * HAI FILTER CHAIN, tach theo CACH XAC THUC chu khong theo tinh nang:
 *
 *   [1] {@code /api/**}, {@code /actuator/**} - may goi may VA trinh duyet goi API.
 *       Khong tao session moi, nhung VAN DOC session san co (SessionCreationPolicy.NEVER)
 *       de trinh duyet da dang nhap goi API duoc. Khi chua xac thuc thi tra 401 JSON,
 *       KHONG redirect - client API nhan 302 sang trang dang nhap Microsoft se hong.
 *
 *   [2] Con lai - trang HTML. Day la noi dat {@code oauth2Login()}: co session, co
 *       redirect sang Entra.
 *
 * Neu {@code rag.entra.enabled=false} thi chain [2] giu nguyen hanh vi cu (trang tinh
 * cong khai, xac thuc bang API key) - bat SSO la mot cong tac, khong phai mot ban re.
 *
 * Ve CSRF: chi bat khi da bat Entra. Ly do la CSRF chi nguy hiem voi xac thuc bang
 * COOKIE (trinh duyet tu dong gui kem); voi header {@code X-API-Key} thi khong, vi
 * trang cua ke tan cong khong the tu dat header do. Chua co dang nhap bang cookie thi
 * bat CSRF chi lam hong cac script dang goi API ma khong doi lai duoc gi.
 */
@Configuration
@EnableMethodSecurity
@Slf4j
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final EntraProperties entraProps;
    /** Vang mat khi {@code rag.entra.enabled=false} - xem {@link EntraScopeFilter}. */
    private final ObjectProvider<EntraScopeFilter> entraScopeFilter;
    private final ObjectProvider<EntraOidcUserService> entraUserService;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final ObjectMapper mapper;

    public SecurityConfig(ApiKeyAuthFilter apiKeyAuthFilter,
                          RateLimitFilter rateLimitFilter,
                          EntraProperties entraProps,
                          ObjectProvider<EntraScopeFilter> entraScopeFilter,
                          ObjectProvider<EntraOidcUserService> entraUserService,
                          ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                          ObjectMapper mapper) {
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.entraProps = entraProps;
        this.entraScopeFilter = entraScopeFilter;
        this.entraUserService = entraUserService;
        this.clientRegistrations = clientRegistrations;
        this.mapper = mapper;
    }

    /** Dang nhap Entra chi duoc bat khi CA cau hinh lan client registration deu san sang. */
    private boolean ssoActive() {
        return entraProps.isEnabled() && clientRegistrations.getIfAvailable() != null;
    }

    // ================================================== [1] API

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**", "/actuator/**")
            .cors(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // NEVER, khong phai STATELESS: khong tu tao session, nhung van doc session
            // san co. STATELESS se bo qua ca phien dang nhap => trinh duyet dang nhap
            // xong van bi 401 o moi loi goi API.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER))
            .headers(h -> h
                    .frameOptions(f -> f.sameOrigin())
                    .contentTypeOptions(c -> {}))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/health/**",
                            "/actuator/info").permitAll()
                    // Bao ve bang chu ky HMAC trong TeamsSignatureVerifier, khong phai API key
                    .requestMatchers("/api/v1/rag/teams-webhook").permitAll()
                    // Bot Teams: bao ve bang JWT cua Microsoft trong BotAuthenticator.
                    // Khong the dung API key - Bot Framework khong gui header tuy y.
                    .requestMatchers("/api/messages").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    // Cong khai CO Y: giao dien phai biet "he thong co bat SSO khong,
                    // dang nhap o dau" TRUOC khi dang nhap. Khi chua xac thuc, endpoint
                    // nay chi tra ve {authenticated:false} - khong lo thong tin gi.
                    .requestMatchers("/api/v1/rag/me").permitAll()
                    .requestMatchers("/api/v1/rag/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/rag/eval/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/rag/settings/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/rag/settings/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll())
            .exceptionHandling(e -> e
                    // 401 kem duong dang nhap: giao dien tu dua nguoi dung sang Entra.
                    .authenticationEntryPoint((req, res, ex) -> write(res, 401,
                            ssoActive()
                                    ? "Chua dang nhap hoac phien da het han."
                                    : "Thieu hoac sai API key. Gui header X-API-Key.",
                            ssoActive()))
                    // 403 thi KHONG kem duong dang nhap: nguoi dung da xac thuc roi, van
                    // de ho dang nhap lai chi lam vong lap vo nghia - van de la thieu quyen.
                    .accessDeniedHandler((req, res, ex) -> write(res, 403,
                            "Khong co quyen thuc hien thao tac nay.", false)));

        applyCsrf(http);
        applyAuthFilters(http);
        return http.build();
    }

    // ================================================== [2] Trang HTML

    @Bean
    @Order(2)
    public SecurityFilterChain uiChain(HttpSecurity http) throws Exception {
        boolean sso = ssoActive();

        http
            .cors(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .headers(h -> h
                    .frameOptions(f -> f.sameOrigin())
                    .contentTypeOptions(c -> {}))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/error", "/favicon.ico", "/assets/**",
                        "/*.css", "/*.js").permitAll();
                if (sso) {
                    auth.requestMatchers("/oauth2/**", "/login/**").permitAll();
                    // Truoc day /admin.html la permitAll (chi cac API ben duoi duoc bao ve).
                    // Da co dang nhap thi chinh trang do cung phai duoc chan.
                    auth.requestMatchers("/admin.html").hasRole("ADMIN");
                    auth.requestMatchers("/", "/index.html").authenticated();
                } else {
                    auth.requestMatchers("/", "/index.html", "/admin.html").permitAll();
                }
                auth.anyRequest().denyAll();
            });

        if (sso) {
            http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .oauth2Login(o -> o.userInfoEndpoint(u ->
                        u.oidcUserService(entraUserService.getObject())))
                .logout(l -> l
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(oidcLogoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"));
            log.info("Dang nhap Entra ID: BAT (tenant={}, mien cho phep={}).",
                    entraProps.getTenantId(), entraProps.getAllowedEmailDomains());
        } else {
            http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(AbstractHttpConfigurer::disable);
            if (entraProps.isEnabled()) {
                log.error("rag.entra.enabled=true nhung thieu cau hinh "
                        + "spring.security.oauth2.client.registration.{}.client-id "
                        + "-> van chay bang API key.", entraProps.getRegistrationId());
            }
        }

        applyCsrf(http);
        applyAuthFilters(http);
        return http.build();
    }

    // ================================================== Phan dung chung

    /**
     * Thu tu filter: API key -> pham vi Entra -> gioi han tan suat.
     *
     * {@link EntraScopeFilter} phai chay TRUOC {@link RateLimitFilter} de o dem tan suat
     * duoc tinh theo nguoi dung that, va truoc {@link AuthorizationFilter} de rule
     * {@code hasRole} doc duoc quyen vua tinh lai.
     */
    private void applyAuthFilters(HttpSecurity http) {
        http.addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        EntraScopeFilter scopeFilter = entraScopeFilter.getIfAvailable();
        if (scopeFilter != null) {
            http.addFilterAfter(scopeFilter, ApiKeyAuthFilter.class);
            http.addFilterAfter(rateLimitFilter, EntraScopeFilter.class);
        } else {
            http.addFilterAfter(rateLimitFilter, ApiKeyAuthFilter.class);
        }
        http.addFilterBefore(new CsrfCookieFilter(), AuthorizationFilter.class);
    }

    /**
     * CSRF chi can khi co xac thuc bang cookie, tuc la khi bat SSO.
     *
     * Mien tru cho request mang san {@code X-API-Key} va cho webhook Teams: ca hai deu
     * khong dua vao cookie nen khong the bi CSRF, trong khi bat CSRF cho chung se lam
     * hong moi script dang goi API.
     *
     * Dung {@link CsrfTokenRequestAttributeHandler} thay handler XOR mac dinh: giao dien
     * doc token tu cookie {@code XSRF-TOKEN} roi gui lai nguyen van o header
     * {@code X-XSRF-TOKEN}, day la cach lam chuan cho trang khong co server-side render.
     */
    private void applyCsrf(HttpSecurity http) throws Exception {
        if (!ssoActive()) {
            http.csrf(AbstractHttpConfigurer::disable);
            return;
        }
        http.csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                        request -> request.getHeader("X-API-Key") != null,
                        request -> "/api/v1/rag/teams-webhook".equals(request.getRequestURI()),
                        // Microsoft khong gui token CSRF; endpoint nay xac thuc bang JWT
                        // va khong dua vao cookie nao nen khong the bi CSRF.
                        request -> "/api/messages".equals(request.getRequestURI())));
    }

    /** Dang xuat ca o phia Entra, khong chi xoa cookie phia minh. */
    private OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler() {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations.getObject());
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }

    private void write(jakarta.servlet.http.HttpServletResponse res, int status, String message,
                       boolean withLoginUrl) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Map<String, Object> body = withLoginUrl
                ? Map.of("error", message, "status", status,
                         "loginUrl", entraProps.authorizationUri())
                : Map.of("error", message, "status", status);
        mapper.writeValue(res.getWriter(), body);
    }

    /**
     * Ep sinh cookie {@code XSRF-TOKEN}.
     *
     * Tu Spring Security 6, token CSRF duoc nap luoi (lazy): khong ai doc thi cookie
     * khong bao gio duoc ghi, va giao dien khong co gi de gui lai. Filter nay cham vao
     * token de cookie luon co mat.
     */
    static final class CsrfCookieFilter extends org.springframework.web.filter.OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            Object token = request.getAttribute(
                    org.springframework.security.web.csrf.CsrfToken.class.getName());
            if (token instanceof org.springframework.security.web.csrf.CsrfToken csrf) {
                csrf.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
