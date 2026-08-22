package com.ai.aiagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class EvalRepository {

    public record EvalCase(Long id, String suite, String question, String expectedSource,
                           String expectedAnswer, String category, boolean active) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EvalRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long addCase(EvalCase c) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // Key column named explicitly: with RETURN_GENERATED_KEYS Postgres returns
            // every column and KeyHolder.getKey() then throws.
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_eval_cases
                        (suite, question, expected_source, expected_answer, category, active)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, c.suite() == null ? "default" : c.suite());
            ps.setString(2, c.question());
            ps.setString(3, c.expectedSource());
            ps.setString(4, c.expectedAnswer());
            ps.setString(5, c.category());
            ps.setBoolean(6, c.active());
            return ps;
        }, keys);
        Number id = keys.getKey();
        return id == null ? -1 : id.longValue();
    }

    public List<EvalCase> listCases(String suite, boolean onlyActive) {
        StringBuilder sql = new StringBuilder("SELECT * FROM rag_eval_cases WHERE 1 = 1 ");
        List<Object> args = new java.util.ArrayList<>();
        if (suite != null && !suite.isBlank()) {
            sql.append(" AND suite = ? ");
            args.add(suite);
        }
        if (onlyActive) sql.append(" AND active = true ");
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), (rs, n) -> new EvalCase(
                rs.getLong("id"), rs.getString("suite"), rs.getString("question"),
                rs.getString("expected_source"), rs.getString("expected_answer"),
                rs.getString("category"), rs.getBoolean("active")), args.toArray());
    }

    public int deleteCase(long id) {
        return jdbc.update("DELETE FROM rag_eval_cases WHERE id = ?", id);
    }

    public List<String> suites() {
        return jdbc.queryForList("SELECT DISTINCT suite FROM rag_eval_cases ORDER BY suite", String.class);
    }

    public long createRun(String suite, String provider, String model, int total, Object params) {
        return createRun(suite, provider, model, total, params, "ANSWER");
    }

    public long createRun(String suite, String provider, String model, int total, Object params,
                          String kind) {
        String paramsJson;
        try {
            paramsJson = params == null ? null : mapper.writeValueAsString(params);
        } catch (Exception e) {
            paramsJson = null;
        }
        final String json = paramsJson;
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // Key column named explicitly: with RETURN_GENERATED_KEYS Postgres returns
            // every column and KeyHolder.getKey() then throws.
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_eval_runs (suite, provider, model, total, params, kind)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?)
                    """, new String[]{"id"});
            ps.setString(1, suite);
            ps.setString(2, provider);
            ps.setString(3, model);
            ps.setInt(4, total);
            ps.setString(5, json);
            ps.setString(6, kind);
            return ps;
        }, keys);
        Number id = keys.getKey();
        return id == null ? -1 : id.longValue();
    }

    public void completeRetrievalRun(long runId, int measured, int skipped, double[] recall,
                                     Double mrr, Double mrrAfter, Integer avgLatencyMs) {
        jdbc.update("""
                UPDATE rag_eval_runs
                   SET judged = ?, skipped = ?, recall_at_1 = ?, recall_at_3 = ?,
                       recall_at_5 = ?, recall_at_10 = ?, mrr = ?, mrr_reranked = ?,
                       context_recall = ?, avg_latency_ms = ?
                 WHERE id = ?
                """, measured, skipped, recall[0], recall[1], recall[2], recall[3],
                mrr, mrrAfter,
                recall[3], avgLatencyMs, runId);
    }

    public void completeRun(long runId, int judged, int skipped, Double faithfulness,
                            Double relevance, Double contextRecall, Double abstainRate,
                            Integer avgLatencyMs, Double totalCostUsd) {
        jdbc.update("""
                UPDATE rag_eval_runs
                   SET judged = ?, skipped = ?, avg_faithfulness = ?, avg_answer_relevance = ?,
                       context_recall = ?, abstain_rate = ?, avg_latency_ms = ?, total_cost_usd = ?
                 WHERE id = ?
                """, judged, skipped, faithfulness, relevance, contextRecall, abstainRate,
                avgLatencyMs, totalCostUsd == null ? null : java.math.BigDecimal.valueOf(totalCostUsd),
                runId);
    }

    public void addResult(long runId, Long caseId, String question, String answer,
                          List<String> sources, Double faithfulness, Double relevance,
                          Boolean sourceHit, boolean judged, boolean abstained, Integer latencyMs) {
        String sourcesJson;
        try {
            sourcesJson = mapper.writeValueAsString(sources == null ? List.of() : sources);
        } catch (Exception e) {
            sourcesJson = "[]";
        }
        jdbc.update("""
                INSERT INTO rag_eval_results
                    (run_id, case_id, question, answer, sources, faithfulness,
                     answer_relevance, source_hit, judged, abstained, latency_ms)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, runId, caseId, question, answer, sourcesJson, faithfulness, relevance,
                sourceHit, judged, abstained, latencyMs);
    }

    public List<Map<String, Object>> listRuns(String suite, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM rag_eval_runs WHERE 1 = 1 ");
        List<Object> args = new java.util.ArrayList<>();
        if (suite != null && !suite.isBlank()) {
            sql.append(" AND suite = ? ");
            args.add(suite);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 100));

        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("suite", rs.getString("suite"));
            m.put("provider", rs.getString("provider"));
            m.put("model", rs.getString("model"));
            m.put("total", rs.getInt("total"));
            m.put("judged", rs.getInt("judged"));
            m.put("skipped", rs.getInt("skipped"));
            m.put("avgFaithfulness", rs.getObject("avg_faithfulness"));
            m.put("avgAnswerRelevance", rs.getObject("avg_answer_relevance"));
            m.put("contextRecall", rs.getObject("context_recall"));
            m.put("abstainRate", rs.getObject("abstain_rate"));
            m.put("avgLatencyMs", rs.getObject("avg_latency_ms"));
            m.put("totalCostUsd", rs.getObject("total_cost_usd"));
            m.put("createdAt", rs.getTimestamp("created_at"));
            return m;
        }, args.toArray());
    }

    public List<Map<String, Object>> results(long runId) {
        return jdbc.query("""
                SELECT question, answer, sources::text AS sources, faithfulness,
                       answer_relevance, source_hit, judged, abstained, latency_ms
                  FROM rag_eval_results WHERE run_id = ? ORDER BY id
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", rs.getString("question"));
            m.put("answer", rs.getString("answer"));
            m.put("sources", rs.getString("sources"));
            m.put("faithfulness", rs.getObject("faithfulness"));
            m.put("answerRelevance", rs.getObject("answer_relevance"));
            m.put("sourceHit", rs.getObject("source_hit"));
            m.put("judged", rs.getBoolean("judged"));
            m.put("abstained", rs.getBoolean("abstained"));
            m.put("latencyMs", rs.getObject("latency_ms"));
            return m;
        }, runId);
    }
}
