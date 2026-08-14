package com.ai.aiagent.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feedback nguoi dung (thumbs up/down).
 *
 * Day la vong phan hoi truoc day hoan toan khong co: khong biet chatbot dang sai o
 * dau thi moi cai tien deu la phong doan. Cau tra loi bi danh gia xau kem theo
 * trich dan da luu, nen truy nguoc duoc chunk nao gay ra loi.
 */
@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long messageId, String conversationId, String userId, int rating, String comment) {
        int normalized = rating >= 0 ? 1 : -1;
        jdbc.update("""
                INSERT INTO rag_feedback (message_id, conversation_id, user_id, rating, comment)
                VALUES (?, ?, ?, ?, ?)
                """, messageId, conversationId, userId, normalized, comment);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT count(*) FILTER (WHERE rating > 0) AS up,
                       count(*) FILTER (WHERE rating < 0) AS down,
                       count(*) AS total
                  FROM rag_feedback
                """, rs -> {
            long up = rs.getLong("up");
            long down = rs.getLong("down");
            out.put("up", up);
            out.put("down", down);
            out.put("total", rs.getLong("total"));
            out.put("satisfactionRate", up + down == 0 ? null
                    : Math.round(up * 1000.0 / (up + down)) / 10.0);
        });
        return out;
    }

    /** Cau tra loi bi danh gia xau gan nhat - danh sach viec can xem lai. */
    public List<Map<String, Object>> recentNegative(int limit) {
        // Lay conversation_id tu BAN GHI TIN NHAN (m), khong lay tu ban ghi feedback:
        // client co the khong gui conversationId, khi do cau hoi tuong ung se bi rong.
        return jdbc.query("""
                SELECT f.id, f.message_id, m.conversation_id, f.comment, f.created_at,
                       m.content AS answer, m.abstained,
                       (SELECT content FROM rag_messages q
                         WHERE q.conversation_id = m.conversation_id AND q.id < m.id
                           AND q.role = 'user' ORDER BY q.id DESC LIMIT 1) AS question
                  FROM rag_feedback f
                  JOIN rag_messages m ON m.id = f.message_id
                 WHERE f.rating < 0
                 ORDER BY f.created_at DESC
                 LIMIT ?
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("messageId", rs.getLong("message_id"));
            m.put("conversationId", rs.getString("conversation_id"));
            m.put("question", rs.getString("question"));
            m.put("answer", rs.getString("answer"));
            m.put("abstained", rs.getBoolean("abstained"));
            m.put("comment", rs.getString("comment"));
            m.put("createdAt", rs.getTimestamp("created_at"));
            return m;
        }, Math.min(Math.max(limit, 1), 200));
    }
}
