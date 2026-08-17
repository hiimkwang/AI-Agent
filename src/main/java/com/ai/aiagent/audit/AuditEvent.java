package com.ai.aiagent.audit;

/**
 * Mot dong nhat ky thao tac.
 *
 * @param action    dang {@code "DELETE /admin/documents/{id}"} - duong dan DA CHUAN HOA
 *                  (so bi thay bang {@code {id}}) de con gom nhom duoc trong bao cao;
 *                  duong dan that nam o {@code path}
 * @param payload   than request da cat ngan va DA CHE secret - xem {@link AuditFilter}
 * @param succeeded {@code status < 400}. Tach rieng khoi {@code status} de cau
 *                  "liet ke thao tac bi tu choi" khong phai tinh toan tren moi dong
 */
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
