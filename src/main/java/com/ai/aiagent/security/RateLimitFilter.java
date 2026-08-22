package com.ai.aiagent.security;

import com.ai.aiagent.common.RequestPaths;
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
        String p = RequestPaths.within(request);
        return !p.startsWith("/api/") || p.startsWith("/api/messages");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        SecurityProperties.RateLimit cfg = properties.getRateLimit();
        if (!cfg.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = RequestPaths.within(request);
        Bucket b = bucketFor(path, request.getMethod(), cfg);
        int limit = b.limit();
        String bucket = b.kind() + ":"
                + ("webhook".equals(b.kind()) ? clientIp(request) : principalId());

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

    record Bucket(String kind, int limit) {
    }

    /**
     * Chon gio dem. Tach ro thanh hai loai:
     *
     * - "chat": chi endpoint SINH cau tra loi. Day moi la thu goi model va ton tien,
     *   nen dang han muc chat (mac dinh 30/phut).
     * - "other": moi lenh doc con lai ma giao dien can de ve trang. Truoc day chung
     *   bi dem chung voi chat, ma mot lan tai trang la ~5 request, nen chi can F5
     *   sau lan trong mot phut la 429 du nguoi dung chua hoi gi ca.
     *
     * Tach ra ham rieng, khong phu thuoc servlet, de test duoc.
     */
    static Bucket bucketFor(String path, String method, SecurityProperties.RateLimit cfg) {
        if (path.startsWith("/api/v1/rag/teams-webhook")) {
            return new Bucket("webhook", cfg.getWebhookPerMinute());
        }
        if (path.startsWith("/api/v1/rag/admin")) {
            return new Bucket("admin", cfg.getAdminPerMinute());
        }
        boolean generating = "POST".equalsIgnoreCase(method)
                && (path.equals("/api/v1/rag/chat") || path.equals("/api/v1/rag/chat/stream"));
        return generating
                ? new Bucket("chat", cfg.getChatPerMinute())
                : new Bucket("other", cfg.getOtherPerMinute());
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
