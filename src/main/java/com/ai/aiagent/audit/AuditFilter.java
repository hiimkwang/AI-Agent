package com.ai.aiagent.audit;

import com.ai.aiagent.common.RequestPaths;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.config.SecurityProperties;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.security.CurrentScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Slf4j
// Auditing happens here rather than in each controller: this filter sits before
// ExceptionTranslationFilter, so it also records denied (401/403) attempts, and a
// new admin endpoint needs no extra wiring.
public class AuditFilter extends OncePerRequestFilter {

    private static final List<String> AUDITED_PREFIXES = List.of(
            "/api/v1/rag/admin",
            "/api/v1/rag/settings",
            "/api/v1/rag/eval");

    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("^\\d+$");

    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)\"([^\"]*(?:key|secret|password|token|credential)[^\"]*|[^\"]*pat)\"\\s*:\\s*\"[^\"]*\"");

    private final AuditService audit;
    private final RagProperties props;
    private final SecurityProperties securityProps;

    public AuditFilter(AuditService audit, RagProperties props,
                       SecurityProperties securityProps) {
        this.audit = audit;
        this.props = props;
        this.securityProps = securityProps;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.getAudit().isEnabled()) return true;
        String path = RequestPaths.within(request);
        if (AUDITED_PREFIXES.stream().noneMatch(path::startsWith)) return true;
        return !props.getAudit().isIncludeRead() && isReadOnly(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        HttpServletRequest wrapped = wrapIfBodyReadable(request);
        try {
            chain.doFilter(wrapped, response);
        } finally {
            try {
                audit.record(build(wrapped, response, start));
            } catch (RuntimeException e) {
                log.error("Could not build the audit event: {}", e.getMessage());
            }
        }
    }

    private AuditEvent build(HttpServletRequest request, HttpServletResponse response, long start) {
        AccessScope scope = CurrentScope.get();
        int status = response.getStatus();
        String path = RequestPaths.within(request);

        return new AuditEvent(
                scope.clientId(),
                scope.displayId(),
                String.join(",", scope.roles()),
                sourceOf(scope, request),
                request.getMethod() + " " + normalize(path),
                request.getMethod(),
                path,
                trim(request.getQueryString(), 500),
                payloadOf(request),
                status,
                status < 400,
                (int) ((System.nanoTime() - start) / 1_000_000),
                clientIp(request),
                trim(request.getHeader("User-Agent"), 300),
                org.slf4j.MDC.get("traceId"));
    }

    private String sourceOf(AccessScope scope, HttpServletRequest request) {
        if (scope.upn() != null && !scope.upn().isBlank()) return "entra";
        if ("anonymous".equals(scope.clientId()) || "internal".equals(scope.clientId())) {
            return request.getHeader(securityProps.getHeaderName()) != null
                    ? "api-key" : "anonymous";
        }
        return "api-key";
    }

    private HttpServletRequest wrapIfBodyReadable(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return request;
        }
        if (request.getContentLengthLong() > props.getAudit().getMaxPayloadChars() * 4L) {
            return request;
        }
        return new ContentCachingRequestWrapper(request);
    }

    private String payloadOf(HttpServletRequest request) {
        if (!(request instanceof ContentCachingRequestWrapper cached)) return null;
        byte[] body = cached.getContentAsByteArray();
        if (body.length == 0) return null;
        String text = new String(body, StandardCharsets.UTF_8);
        return trim(redact(text), props.getAudit().getMaxPayloadChars());
    }

    static String redact(String json) {
        if (json == null || json.isBlank()) return json;
        return SECRET_FIELD.matcher(json).replaceAll("\"$1\":\"***\"");
    }

    static String normalize(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append('/').append(NUMERIC_SEGMENT.matcher(part).matches() ? "{id}" : part);
        }
        String out = sb.toString();
        return out.startsWith("/api/v1/rag") ? out.substring("/api/v1/rag".length()) : out;
    }

    private String clientIp(HttpServletRequest request) {
        if (props.getAudit().isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return trim(forwarded.split(",")[0].strip(), 60);
            }
        }
        return request.getRemoteAddr();
    }

    private static boolean isReadOnly(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
