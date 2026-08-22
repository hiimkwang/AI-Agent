package com.ai.aiagent.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UsageReportRepository {

    private final JdbcTemplate jdbc;

    public UsageReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> byBot(int days) {
        int window = clampDays(days);

        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT COALESCE(m.bot_slug, 'web')                         AS bot,
                       count(*)                                            AS questions,
                       count(*) FILTER (WHERE m.abstained)                 AS abstained,
                       count(*) FILTER (WHERE m.cache_hit IS NOT NULL)     AS cache_hits,
                       count(DISTINCT c.user_id)                           AS users,
                       count(DISTINCT m.conversation_id)                   AS conversations,
                       COALESCE(sum(m.input_tokens), 0)                    AS input_tokens,
                       COALESCE(sum(m.output_tokens), 0)                   AS output_tokens,
                       COALESCE(sum(m.cost_usd), 0)                        AS cost_usd,
                       avg(m.latency_ms)                                   AS avg_latency,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY m.latency_ms) AS p95_latency,
                       max(m.created_at)                                   AS last_at
                  FROM rag_messages m
                  JOIN rag_conversations c ON c.id = m.conversation_id
                 WHERE m.role = 'assistant'
                   AND m.created_at >= now() - (? || ' days')::interval
                 GROUP BY 1
                """, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            long questions = rs.getLong("questions");
            long abstained = rs.getLong("abstained");
            row.put("bot", rs.getString("bot"));
            row.put("questions", questions);
            row.put("abstained", abstained);
            row.put("abstainRate", rate(abstained, questions));
            row.put("cacheHits", rs.getLong("cache_hits"));
            row.put("cacheHitRate", rate(rs.getLong("cache_hits"), questions));
            row.put("users", rs.getLong("users"));
            row.put("conversations", rs.getLong("conversations"));
            row.put("inputTokens", rs.getLong("input_tokens"));
            row.put("outputTokens", rs.getLong("output_tokens"));
            row.put("costUsd", round(rs.getDouble("cost_usd"), 1_000_000));
            row.put("avgLatencyMs", rs.getObject("avg_latency") == null ? null
                    : Math.round(rs.getDouble("avg_latency")));
            row.put("p95LatencyMs", rs.getObject("p95_latency") == null ? null
                    : Math.round(rs.getDouble("p95_latency")));
            row.put("lastAt", rs.getTimestamp("last_at"));
            row.put("thumbsUp", 0L);
            row.put("thumbsDown", 0L);
            row.put("satisfactionRate", null);
            rows.put(rs.getString("bot"), row);
        }, String.valueOf(window));

        jdbc.query("""
                SELECT COALESCE(m.bot_slug, 'web')          AS bot,
                       count(*) FILTER (WHERE f.rating > 0) AS up,
                       count(*) FILTER (WHERE f.rating < 0) AS down
                  FROM rag_feedback f
                  JOIN rag_messages m ON m.id = f.message_id
                 WHERE f.created_at >= now() - (? || ' days')::interval
                 GROUP BY 1
                """, rs -> {
            Map<String, Object> row = rows.get(rs.getString("bot"));
            if (row == null) return;
            long up = rs.getLong("up");
            long down = rs.getLong("down");
            row.put("thumbsUp", up);
            row.put("thumbsDown", down);
            row.put("satisfactionRate", rate(up, up + down));
        }, String.valueOf(window));

        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        out.sort((a, b) -> Long.compare((Long) b.get("questions"), (Long) a.get("questions")));
        return out;
    }

    public List<Map<String, Object>> daily(int days) {
        return jdbc.query("""
                SELECT date_trunc('day', m.created_at)::date          AS day,
                       COALESCE(m.bot_slug, 'web')                    AS bot,
                       count(*)                                       AS questions,
                       count(*) FILTER (WHERE m.abstained)            AS abstained,
                       COALESCE(sum(m.cost_usd), 0)                   AS cost_usd
                  FROM rag_messages m
                 WHERE m.role = 'assistant'
                   AND m.created_at >= now() - (? || ' days')::interval
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", rs.getDate("day").toString());
            row.put("bot", rs.getString("bot"));
            row.put("questions", rs.getLong("questions"));
            row.put("abstained", rs.getLong("abstained"));
            row.put("costUsd", round(rs.getDouble("cost_usd"), 1_000_000));
            return row;
        }, String.valueOf(clampDays(days)));
    }

    public List<Map<String, Object>> topAbstained(String bot, int days, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT regexp_replace(
                           btrim(lower(q.content), E' \\t\\n?.!:'),
                           '\\s+', ' ', 'g')  AS question,
                       count(*)                AS times,
                       max(m.created_at)       AS last_at,
                       min(q.content)          AS sample
                  FROM rag_messages m
                  JOIN LATERAL (
                       SELECT content FROM rag_messages u
                        WHERE u.conversation_id = m.conversation_id
                          AND u.id < m.id AND u.role = 'user'
                        ORDER BY u.id DESC LIMIT 1
                  ) q ON true
                 WHERE m.role = 'assistant' AND m.abstained
                   AND m.created_at >= now() - (? || ' days')::interval
                """);
        List<Object> args = new ArrayList<>();
        args.add(String.valueOf(clampDays(days)));
        if (bot != null && !bot.isBlank()) {
            sql.append(" AND COALESCE(m.bot_slug, 'web') = ? ");
            args.add(bot.strip());
        }
        sql.append(" GROUP BY 1 ORDER BY times DESC, last_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 200));

        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", rs.getString("sample"));
            row.put("times", rs.getLong("times"));
            row.put("lastAt", rs.getTimestamp("last_at"));
            return row;
        }, args.toArray());
    }

    private static int clampDays(int days) {
        return Math.min(Math.max(days, 1), 365);
    }

    private static Double rate(long part, long total) {
        return total == 0 ? null : Math.round(part * 1000.0 / total) / 10.0;
    }

    private static double round(double value, int scale) {
        return Math.round(value * scale) / (double) scale;
    }
}
