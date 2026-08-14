package com.ai.aiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

/**
 * Phan quyen theo duong dan.
 *
 * Nguyen tac: mac dinh TU CHOI, chi mo dung nhung gi can.
 *   - Trang tinh (UI) va /actuator/health: cong khai
 *   - /teams-webhook: cong khai o tang Spring Security nhung duoc
 *     {@link com.ai.aiagent.security.TeamsSignatureVerifier} kiem tra HMAC
 *   - /admin/**, /eval/**, PUT /settings: chi ADMIN
 *   - con lai duoi /api: phai xac thuc
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ObjectMapper mapper;

    public SecurityConfig(ApiKeyAuthFilter apiKeyAuthFilter,
                          RateLimitFilter rateLimitFilter,
                          ObjectMapper mapper) {
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.mapper = mapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                    .frameOptions(f -> f.sameOrigin())
                    .contentTypeOptions(c -> {}))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                            "/", "/index.html", "/admin.html",
                            "/assets/**", "/*.css", "/*.js", "/favicon.ico",
                            "/error").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/api/v1/rag/teams-webhook").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/rag/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/rag/eval/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/rag/settings/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/rag/settings/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll())
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, ApiKeyAuthFilter.class)
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> write(res, 401,
                            "Thieu hoac sai API key. Gui header X-API-Key."))
                    .accessDeniedHandler((req, res, ex) -> write(res, 403,
                            "Khong co quyen thuc hien thao tac nay.")));
        return http.build();
    }

    private void write(jakarta.servlet.http.HttpServletResponse res, int status, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(), Map.of("error", message, "status", status));
    }
}
