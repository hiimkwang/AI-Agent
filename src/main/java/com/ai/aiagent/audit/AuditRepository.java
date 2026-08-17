package com.ai.aiagent.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Doc/ghi bang {@code rag_audit_log}. */
@Repository
@Slf4j
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEvent e) {
        jdbc.update("""
                INSERT INTO rag_audit_log
                    (actor_id, actor_upn, actor_roles, actor_source, action, method, path,
                     query_string, payload, status, succeeded, latency_ms, client_ip,
                     user_agent, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                e.actorId(), e.actorUpn(), e.actorRoles(), e.actorSource(), e.action(),
                e.method(), e.path(), e.queryString(), e.payload(), e.status(),
                e.succeeded(), e.latencyMs(), e.clientIp(), e.userAgent(), e.traceId());
    }

    /**
     * Tra ve nhat ky theo bo loc, moi nhat truoc.
     *
     * @param actor  loc theo UPN hoac actor_id (khop mot phan, khong phan biet hoa thuong)
     * @param action loc theo hanh dong (khop mot phan, vi du {@code "DELETE"} hay
     *               {@code "/admin/collections"})
     * @param onlyDenied chi lay thao tac bi tu choi/that bai
     */
    public List<Map<String, Object>> search(String actor, String action, Boolean onlyDenied,
                                            int days, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, actor_id, actor_upn, actor_roles, actor_source, action, method,
                       path, query_string, payload, status, succeeded, latency_ms, client_ip,
                       trace_id, created_at
                  FROM rag_audit_log
                 WHERE created_at >= now() - (? || ' days')::interval
                """);
        List<Object> args = new ArrayList<>();
        args.add(String.valueOf(Math.max(1, days)));

        if (actor != null && !actor.isBlank()) {
            sql.append(" AND (actor_upn ILIKE ? OR actor_id ILIKE ?) ");
            args.add("%" + actor.strip() + "%");
            args.add("%" + actor.strip() + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action ILIKE ? ");
            args.add("%" + action.strip() + "%");
        }
        if (Boolean.TRUE.equals(onlyDenied)) {
            sql.append(" AND NOT succeeded ");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));

        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("actorId", rs.getString("actor_id"));
            m.put("actor", rs.getString("actor_upn"));
            m.put("roles", rs.getString("actor_roles"));
            m.put("source", rs.getString("actor_source"));
            m.put("action", rs.getString("action"));
            m.put("method", rs.getString("method"));
            m.put("path", rs.getString("path"));
            m.put("query", rs.getString("query_string"));
            m.put("payload", rs.getString("payload"));
            m.put("status", rs.getInt("status"));
            m.put("succeeded", rs.getBoolean("succeeded"));
            m.put("latencyMs", rs.getObject("latency_ms"));
            m.put("clientIp", rs.getString("client_ip"));
            m.put("traceId", rs.getString("trace_id"));
            m.put("createdAt", rs.getTimestamp("created_at"));
            return m;
        }, args.toArray());
    }

    /** Tong hop cho man quan tri: ai thao tac nhieu nhat, hanh dong nao pho bien nhat. */
    public Map<String, Object> summary(int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("total", jdbc.queryForObject("""
                SELECT count(*) FROM rag_audit_log
                 WHERE created_at >= now() - (? || ' days')::interval
                """, Long.class, String.valueOf(Math.max(1, days))));
        out.put("denied", jdbc.queryForObject("""
                SELECT count(*) FROM rag_audit_log
                 WHERE NOT succeeded AND created_at >= now() - (? || ' days')::interval
                """, Long.class, String.valueOf(Math.max(1, days))));
        out.put("byActor", jdbc.queryForList("""
                SELECT actor_upn AS actor, count(*) AS n FROM rag_audit_log
                 WHERE created_at >= now() - (? || ' days')::interval
                 GROUP BY actor_upn ORDER BY n DESC LIMIT 20
                """, String.valueOf(Math.max(1, days))));
        out.put("byAction", jdbc.queryForList("""
                SELECT action, count(*) AS n FROM rag_audit_log
                 WHERE created_at >= now() - (? || ' days')::interval
                 GROUP BY action ORDER BY n DESC LIMIT 20
                """, String.valueOf(Math.max(1, days))));
        return out;
    }

    /** @return so dong da xoa. Dung cho {@code RetentionService}. */
    public int purgeOlderThanDays(int days) {
        return jdbc.update(
                "DELETE FROM rag_audit_log WHERE created_at < now() - (? || ' days')::interval",
                String.valueOf(Math.max(1, days)));
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_audit_log", Long.class);
        return n == null ? 0 : n;
    }
}
