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

/**
 * Hoi thoai va tin nhan luu trong Postgres.
 *
 * Thay cho {@code ConversationMemory} chi nam trong RAM truoc day - cai do mat
 * khi restart, khong chia se duoc giua nhieu instance, va la mot cho RO RI BO NHO
 * (ConcurrentHashMap khong TTL, khong gioi han so hoi thoai, chi giai phong khi co
 * nguoi goi DELETE thu cong).
 *
 * Bang nay cung la nen cho moi cai tien ve sau: co lich su Q&A that thi moi biet
 * chatbot dang sai o dau.
 */
@Repository
@Slf4j
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param botId bot phuc vu hoi thoai; null o duong web. Chi dat khi con TRONG:
     *              hoi thoai da thuoc ve mot bot thi giu nguyen bot do, de mot lan doi
     *              rang buoc channel khong viet lai lich su thanh cua bot moi
     */
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

    /**
     * @param botSlug ghi ca vao tin nhan CUA NGUOI DUNG, khong chi tin nhan tra loi:
     *                {@code /eval/cases/harvest} thu hoach cau hoi that tu chinh cac dong
     *                nay, va thu hoach duoc theo tung bot moi danh gia duoc rieng bot do
     */
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
            // Phai chi RO ten cot khoa. Voi RETURN_GENERATED_KEYS, Postgres tra ve
            // TAT CA cac cot, khi do KeyHolder.getKey() nem loi
            // "current key entry contains multiple keys".
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

    /** Lich su gan nhat, tra ve theo dung thu tu thoi gian. */
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

    /** Toan bo tin nhan cua mot hoi thoai kem trich dan - dung cho UI. */
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

    /** Don hoi thoai cu - thay cho viec truoc day khong bao gio giai phong bo nho. */
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
