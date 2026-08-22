package com.ai.aiagent.security;

import com.ai.aiagent.audit.AuditFilter;
import com.ai.aiagent.common.RequestPaths;
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
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.Map;

@Configuration
@EnableMethodSecurity
@Slf4j
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final AuditFilter auditFilter;
    private final EntraProperties entraProps;
    private final ObjectProvider<EntraScopeFilter> entraScopeFilter;
    private final ObjectProvider<EntraOidcUserService> entraUserService;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final ObjectMapper mapper;

    public SecurityConfig(ApiKeyAuthFilter apiKeyAuthFilter,
                          RateLimitFilter rateLimitFilter,
                          AuditFilter auditFilter,
                          EntraProperties entraProps,
                          ObjectProvider<EntraScopeFilter> entraScopeFilter,
                          ObjectProvider<EntraOidcUserService> entraUserService,
                          ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                          ObjectMapper mapper) {
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.auditFilter = auditFilter;
        this.entraProps = entraProps;
        this.entraScopeFilter = entraScopeFilter;
        this.entraUserService = entraUserService;
        this.clientRegistrations = clientRegistrations;
        this.mapper = mapper;
    }

    private boolean ssoActive() {
        return entraProps.isEnabled() && clientRegistrations.getIfAvailable() != null;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**", "/actuator/**")
            .cors(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // NEVER, not STATELESS: STATELESS also ignores an existing session, so a
            // browser authenticated via Entra would get 401 on every API call.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER))
            .headers(h -> h
                    .frameOptions(f -> f.sameOrigin())
                    .contentTypeOptions(c -> {}))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/health/**",
                            "/actuator/info").permitAll()
                    .requestMatchers("/api/v1/rag/teams-webhook").permitAll()
                    .requestMatchers("/api/messages").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/rag/me").permitAll()
                    // Delegated workspace: any signed-in user may call it, and every
                    // endpoint re-checks rag_grants itself. Kept out of /admin/** on
                    // purpose so a new admin endpoint never becomes reachable here.
                    .requestMatchers("/api/v1/rag/my/**").authenticated()
                    .requestMatchers("/api/v1/rag/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/rag/eval/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/rag/settings/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/rag/settings/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll())
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> write(res, 401,
                            ssoActive()
                                    ? "Chua dang nhap hoac phien da het han."
                                    : "Thieu hoac sai API key. Gui header X-API-Key.",
                            ssoActive()))
                    .accessDeniedHandler((req, res, ex) -> write(res, 403,
                            "Khong co quyen thuc hien thao tac nay.", false)));

        applyCsrf(http);
        applyAuthFilters(http);
        http.addFilterBefore(auditFilter, ExceptionTranslationFilter.class);
        return http.build();
    }

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
                    auth.requestMatchers("/admin.html").hasRole("ADMIN");
                    // Page itself is open to any signed-in user; it shows nothing unless
                    // rag_grants actually gave them something.
                    auth.requestMatchers("/", "/index.html", "/my.html").authenticated();
                } else {
                    auth.requestMatchers("/", "/index.html", "/admin.html", "/my.html").permitAll();
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
            log.info("Entra ID sign-in ENABLED (tenant={}, allowed domains={}).",
                    entraProps.getTenantId(), entraProps.getAllowedEmailDomains());
        } else {
            http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(AbstractHttpConfigurer::disable);
            if (entraProps.isEnabled()) {
                log.error("rag.entra.enabled=true but "
                        + "spring.security.oauth2.client.registration.{}.client-id is missing; "
                        + "falling back to API-key authentication.", entraProps.getRegistrationId());
            }
        }

        applyCsrf(http);
        applyAuthFilters(http);
        return http.build();
    }

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
                        request -> "/api/v1/rag/teams-webhook".equals(RequestPaths.within(request)),
                        request -> "/api/messages".equals(RequestPaths.within(request))));
    }

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
