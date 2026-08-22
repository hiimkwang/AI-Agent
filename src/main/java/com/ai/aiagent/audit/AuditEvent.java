package com.ai.aiagent.audit;

public record AuditEvent(
        String actorId,
        String actorUpn,
        String actorRoles,
        String actorSource,
        String action,
        String method,
        String path,
        String queryString,
        String payload,
        int status,
        boolean succeeded,
        int latencyMs,
        String clientIp,
        String userAgent,
        String traceId
) {
}
