package com.ai.aiagent.common;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * Path inside the application, with the context path removed.
     * <p>
     * {@code getRequestURI()} includes the context path, so route matching built on it
     * silently stops working once the app is mounted under a prefix
     * ({@code server.servlet.context-path}). Rate limiting, the audit trail and the CSRF
     * exemptions all failed open that way.
     */
    public static String within(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return "/";
        String context = request.getContextPath();
        if (context == null || context.isEmpty() || !uri.startsWith(context)) return uri;
        String out = uri.substring(context.length());
        return out.isEmpty() ? "/" : out;
    }
}
