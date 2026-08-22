package com.ai.aiagent.store;

import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import com.ai.aiagent.store.StoreModels.Citation;
import com.ai.aiagent.store.StoreModels.Turn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureConversation(String conversationId, String userId, String category,
                                   Long botId) {
        jdbc.update("""
                INSERT INTO rag_conversations (id, user_id, category, bot_id, created_at, last_active_at)
                VALUES (?, ?, ?, ?, now(), now())
                ON CONFLICT (id) DO UPDATE
                   SET last_active_at = now(),
                       bot_id = COALESCE(rag_conversations.bot_id, EXCLUDED.bot_id)
                """, conversationId, userId, category, botId);
    }

    public void updateTitleIfEmpty(String conversationId, String title) {
        if (title == null || title.isBlank()) return;
        String trimmed = title.length() > 120 ? title.substring(0, 120) : title;
        jdbc.update("UPDATE rag_conversations SET title = ? WHERE id = ? AND title IS NULL",
                trimmed, conversationId);
    }

    public long appendUserMessage(String conversationId, String content, String rewrittenQuery,
                                  String botSlug) {
        return insertMessage(conversationId, "user", content, rewrittenQuery,
                null, null, null, null, null, false, null, botSlug);
    }

    public long appendAssistantMessage(String conversationId, String content, String provider,
                                       String model, LlmUsage usage, Integer latencyMs,
                                       boolean abstained, String cacheHit, String botSlug) {
        return insertMessage(conversationId, "assistant", content, null, provider, model,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.costUsd(),
                abstained, cacheHit, botSlug);
    }

    private long insertMessage(String conversationId, String role, String content,
                               String rewrittenQuery, String provider, String model,
                               Integer inputTokens, Integer outputTokens, Double costUsd,
                               boolean abstained, String cacheHit, String botSlug) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // Key column named explicitly: with RETURN_GENERATED_KEYS Postgres returns
            // every column and KeyHolder.getKey() then throws.
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_messages
                        (conversation_id, role, content, rewritten_query, provider, model,
                         input_tokens, output_tokens, cost_usd, latency_ms, abstained, cache_hit,
                         bot_slug)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, conversationId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setString(4, rewrittenQuery);
            ps.setString(5, provider);
            ps.setString(6, model);
            setNullableInt(ps, 7, inputTokens);
            setNullableInt(ps, 8, outputTokens);
            if (costUsd == null) ps.setNull(9, java.sql.Types.NUMERIC);
            else ps.setBigDecimal(9, java.math.BigDecimal.valueOf(costUsd));
            ps.setNull(10, java.sql.Types.INTEGER);
            ps.setBoolean(11, abstained);
            ps.setString(12, cacheHit);
            ps.setString(13, botSlug);
            return ps;
        }, keys);

        Number id = keys.getKey();
        jdbc.update("UPDATE rag_conversations SET last_active_at = now() WHERE id = ?", conversationId);
        return id == null ? -1 : id.longValue();
    }

    public void updateLatency(long messageId, int latencyMs) {
        jdbc.update("UPDATE rag_messages SET latency_ms = ? WHERE id = ?", latencyMs, messageId);
    }

    public void saveCitations(long messageId, List<Citation> citations) {
        if (citations == null || citations.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO rag_message_citations
                    (message_id, chunk_id, document_id, file_name, heading_path, snippet, score, rank)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, citations, citations.size(), (ps, c) -> {
            ps.setLong(1, messageId);
            ps.setLong(2, c.chunkId());
            if (c.documentId() == null) ps.setNull(3, java.sql.Types.BIGINT);
            else ps.setLong(3, c.documentId());
            ps.setString(4, c.fileName());
            ps.setString(5, c.headingPath());
            ps.setString(6, c.snippet());
            ps.setDouble(7, c.score());
            ps.setInt(8, c.rank());
        });
    }

    public List<Turn> history(String conversationId, int maxTurns) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        int limit = Math.max(2, maxTurns * 2);
        List<Turn> reversed = jdbc.query("""
                SELECT role, content FROM rag_messages
                 WHERE conversation_id = ?
                 ORDER BY id DESC
                 LIMIT ?
                """, (rs, n) -> new Turn(rs.getString("role"), rs.getString("content")),
                conversationId, limit);
        List<Turn> out = new ArrayList<>(reversed);
        java.util.Collections.reverse(out);
        return out;
    }

    public List<Map<String, Object>> messages(String conversationId) {
        List<Map<String, Object>> messages = jdbc.query("""
                SELECT id, role, content, provider, model, input_tokens, output_tokens,
                       cost_usd, latency_ms, abstained, cache_hit, bot_slug, created_at
                  FROM rag_messages
                 WHERE conversation_id = ?
                 ORDER BY id
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("role", rs.getString("role"));
            m.put("content", rs.getString("content"));
            m.put("provider", rs.getString("provider"));
            m.put("model", rs.getString("model"));
            m.put("inputTokens", rs.getObject("input_tokens"));
            m.put("outputTokens", rs.getObject("output_tokens"));
            m.put("costUsd", rs.getObject("cost_usd"));
            m.put("latencyMs", rs.getObject("latency_ms"));
            m.put("abstained", rs.getBoolean("abstained"));
            m.put("cacheHit", rs.getString("cache_hit"));
            m.put("bot", rs.getString("bot_slug"));
            m.put("createdAt", rs.getTimestamp("created_at"));
            return m;
        }, conversationId);

        for (Map<String, Object> message : messages) {
            long id = (Long) message.get("id");
            message.put("citations", citations(id));
        }
        return messages;
    }

    public List<Map<String, Object>> citations(long messageId) {
        return jdbc.query("""
                SELECT chunk_id, document_id, file_name, heading_path, snippet, score, rank
                  FROM rag_message_citations WHERE message_id = ? ORDER BY rank
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", rs.getLong("chunk_id"));
            m.put("documentId", rs.getObject("document_id"));
            m.put("fileName", rs.getString("file_name"));
            m.put("headingPath", rs.getString("heading_path"));
            m.put("snippet", rs.getString("snippet"));
            m.put("score", rs.getDouble("score"));
            m.put("rank", rs.getInt("rank"));
            return m;
        }, messageId);
    }

    public List<Map<String, Object>> listConversations(String userId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.title, c.category, c.user_id, c.created_at, c.last_active_at,
                       b.slug AS bot_slug,
                       (SELECT count(*) FROM rag_messages m WHERE m.conversation_id = c.id) AS message_count
                  FROM rag_conversations c
                  LEFT JOIN rag_bots b ON b.id = c.bot_id
                 WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND c.user_id = ? ");
            args.add(userId);
        }
        sql.append(" ORDER BY c.last_active_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 200));

        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("title", rs.getString("title"));
            m.put("category", rs.getString("category"));
            m.put("userId", rs.getString("user_id"));
            m.put("createdAt", rs.getTimestamp("created_at"));
            m.put("lastActiveAt", rs.getTimestamp("last_active_at"));
            m.put("bot", rs.getString("bot_slug"));
            m.put("messageCount", rs.getLong("message_count"));
            return m;
        }, args.toArray());
    }

    public int deleteConversation(String conversationId) {
        return jdbc.update("DELETE FROM rag_conversations WHERE id = ?", conversationId);
    }

    /** Owner of a conversation, or {@code null} when it does not exist. */
    /**
     * The question an assistant message answered: the last user message before it in the same
     * conversation. Null when the message is unknown or has no question in front of it.
     */
    public String questionOf(long assistantMessageId) {
        List<String> found = jdbc.queryForList("""
                SELECT u.content
                  FROM rag_messages a
                  JOIN rag_messages u ON u.conversation_id = a.conversation_id
                                     AND u.role = 'user'
                                     AND u.id < a.id
                 WHERE a.id = ?
                 ORDER BY u.id DESC
                 LIMIT 1
                """, String.class, assistantMessageId);
        return found.isEmpty() ? null : found.get(0);
    }

    public String ownerOf(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        List<String> hit = jdbc.query("SELECT coalesce(user_id, '') FROM rag_conversations WHERE id = ?",
                (rs, n) -> rs.getString(1), conversationId);
        return hit.isEmpty() ? null : hit.get(0);
    }

    public int deleteConversationsOf(String userId) {
        if (userId == null || userId.isBlank()) return 0;
        return jdbc.update("DELETE FROM rag_conversations WHERE user_id = ?", userId);
    }

    public int purgeInactiveOlderThanDays(int days) {
        return jdbc.update(
                "DELETE FROM rag_conversations WHERE last_active_at < now() - (? || ' days')::interval",
                String.valueOf(Math.max(1, days)));
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER);
        else ps.setInt(index, value);
    }
}
