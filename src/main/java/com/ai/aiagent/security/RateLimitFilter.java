package com.ai.aiagent.security;

import com.ai.aiagent.config.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gioi han so request moi phut theo client (hoac theo IP voi webhook).
 *
 * Cua so co dinh 1 phut, dem bang AtomicInteger trong cache tu het han. Don gian
 * nhung du chan lam dung: moi cau hoi chat tieu 3-4 loi goi LLM, khong the de
 * mot client bat tan quay tien LLM.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;
    private final ObjectMapper mapper;

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(50_000)
            .build();

    public RateLimitFilter(SecurityProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        SecurityProperties.RateLimit cfg = properties.getRateLimit();
        if (!cfg.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        int limit;
        String bucket;

        if (path.startsWith("/api/v1/rag/teams-webhook")) {
            limit = cfg.getWebhookPerMinute();
            bucket = "webhook:" + clientIp(request);
        } else if (path.startsWith("/api/v1/rag/admin")) {
            limit = cfg.getAdminPerMinute();
            bucket = "admin:" + principalId();
        } else {
            limit = cfg.getChatPerMinute();
            bucket = "chat:" + principalId();
        }

        String key = bucket + ":" + (System.currentTimeMillis() / 60_000L);
        AtomicInteger counter = counters.get(key, k -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(), Map.of(
                    "error", "Vuot qua gioi han " + limit + " request/phut. Thu lai sau 1 phut.",
                    "status", 429));
            return;
        }
        chain.doFilter(request, response);
    }

    private String principalId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AccessScope s) return s.clientId();
        return "anonymous";
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
