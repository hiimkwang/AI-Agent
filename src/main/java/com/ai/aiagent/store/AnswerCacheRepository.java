package com.ai.aiagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cache cau tra loi, hai tang:
 *   - EXACT: khop chinh xac theo hash cua (cau hoi da chuan hoa + pham vi truy cap)
 *   - SEMANTIC: khop theo cosine giua vector cau hoi, nguong ~0.97
 *
 * Truoc day khong co cache nao ca, nen mot cau hoi duoc muoi nguoi hoi la muoi lan
 * tra du 3-4 loi goi LLM. Cau hoi noi bo lap lai rat nhieu nen day la cho tiet kiem
 * lon nhat.
 *
 * Cache key LUON gom pham vi truy cap ({@code scope_key}) de khong bao gio tra cau
 * tra loi cua phong ban khac cho nguoi khong co quyen.
 */
@Repository
@Slf4j
public class AnswerCacheRepository {

    /** @param kind EXACT hoac SEMANTIC - de bao cho nguoi dung biet vi sao tra loi nhanh. */
    public record Hit(long id, String answer, String citationsJson, String provider,
                      String model, String kind, double similarity) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AnswerCacheRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Hit> findExact(String cacheKey) {
        List<Hit> found = jdbc.query("""
                SELECT id, answer, citations::text AS citations, provider, model
                  FROM rag_answer_cache
                 WHERE cache_key = ?
                   AND (expires_at IS NULL OR expires_at > now())
                """, (rs, n) -> new Hit(rs.getLong("id"), rs.getString("answer"),
                rs.getString("citations"), rs.getString("provider"),
                rs.getString("model"), "EXACT", 1.0), cacheKey);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Tim cau hoi tuong duong ve ngu nghia trong cung pham vi truy cap. */
    public Optional<Hit> findSemantic(String scopeKey, float[] embedding, double threshold) {
        String vector = Vectors.toLiteral(embedding);
        List<Hit> found = jdbc.query("""
                SELECT id, answer, citations::text AS citations, provider, model,
                       1 - (embedding <=> ?::vector) AS similarity
                  FROM rag_answer_cache
                 WHERE scope_key = ?
                   AND embedding IS NOT NULL
                   AND (expires_at IS NULL OR expires_at > now())
                 ORDER BY embedding <=> ?::vector
                 LIMIT 1
                """, (rs, n) -> new Hit(rs.getLong("id"), rs.getString("answer"),
                        rs.getString("citations"), rs.getString("provider"),
                        rs.getString("model"), "SEMANTIC", rs.getDouble("similarity")),
                vector, scopeKey, vector);

        if (found.isEmpty()) return Optional.empty();
        Hit hit = found.get(0);
        return hit.similarity() >= threshold ? Optional.of(hit) : Optional.empty();
    }

    public void put(String cacheKey, String scopeKey, String question, String answer,
                    Object citations, String provider, String model,
                    float[] embedding, int ttlMinutes) {
        String citationsJson;
        try {
            citationsJson = citations == null ? null : mapper.writeValueAsString(citations);
        } catch (Exception e) {
            citationsJson = null;
        }
        try {
            jdbc.update("""
                    INSERT INTO rag_answer_cache
                        (cache_key, scope_key, question, answer, citations, provider, model,
                         embedding, expires_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?::vector,
                            now() + (? || ' minutes')::interval)
                    ON CONFLICT (cache_key) DO UPDATE SET
                        answer     = EXCLUDED.answer,
                        citations  = EXCLUDED.citations,
                        provider   = EXCLUDED.provider,
                        model      = EXCLUDED.model,
                        embedding  = EXCLUDED.embedding,
                        expires_at = EXCLUDED.expires_at
                    """, cacheKey, scopeKey, question, answer, citationsJson, provider, model,
                    embedding == null ? null : Vectors.toLiteral(embedding),
                    String.valueOf(Math.max(1, ttlMinutes)));
        } catch (org.springframework.dao.DataAccessException e) {
            // Cache loi khong duoc lam sap cau tra loi
            log.warn("Khong ghi duoc cache cau tra loi: {}", e.getMessage());
        }
    }

    public void recordHit(long id) {
        jdbc.update("UPDATE rag_answer_cache SET hits = hits + 1, last_hit_at = now() WHERE id = ?", id);
    }

    public int purgeExpired() {
        return jdbc.update("DELETE FROM rag_answer_cache WHERE expires_at IS NOT NULL AND expires_at <= now()");
    }

    /** Giu bang cache khong phinh vo han: xoa ban ghi cu nhat khi vuot gioi han. */
    public int trimTo(int maxEntries) {
        return jdbc.update("""
                DELETE FROM rag_answer_cache
                 WHERE id IN (
                     SELECT id FROM rag_answer_cache
                      ORDER BY coalesce(last_hit_at, created_at) DESC
                      OFFSET ?
                 )
                """, Math.max(100, maxEntries));
    }

    public int clear() {
        return jdbc.update("DELETE FROM rag_answer_cache");
    }

    /** Xoa cache lien quan mot tai lieu - goi sau khi nap lai hoac xoa tai lieu. */
    public int invalidateAll() {
        return clear();
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT count(*) AS entries,
                       coalesce(sum(hits), 0) AS hits,
                       count(*) FILTER (WHERE expires_at IS NOT NULL AND expires_at <= now()) AS expired
                  FROM rag_answer_cache
                """, rs -> {
            out.put("entries", rs.getLong("entries"));
            out.put("hits", rs.getLong("hits"));
            out.put("expired", rs.getLong("expired"));
        });
        return out;
    }
}
