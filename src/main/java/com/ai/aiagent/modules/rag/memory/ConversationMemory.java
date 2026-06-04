package com.ai.aiagent.modules.rag.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lưu lịch sử hội thoại theo conversationId (in-memory) để hỗ trợ hỏi nối tiếp (multi-turn).
 *
 * Lưu ý: đây là bộ nhớ tạm trong RAM, mất khi restart và không chia sẻ giữa nhiều instance.
 * Đủ dùng cho 1 server; nếu scale nhiều node nên thay bằng Redis/DB.
 */
@Component
public class ConversationMemory {

    /** Số cặp hỏi-đáp gần nhất giữ lại cho mỗi hội thoại. */
    private static final int MAX_TURNS = 6;

    public record Turn(String role, String text) {}

    private final Map<String, Deque<Turn>> store = new ConcurrentHashMap<>();

    public List<Turn> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        Deque<Turn> turns = store.get(conversationId);
        return turns == null ? List.of() : new ArrayList<>(turns);
    }

    public void addUser(String conversationId, String text) {
        add(conversationId, new Turn("user", text));
    }

    public void addAssistant(String conversationId, String text) {
        add(conversationId, new Turn("assistant", text));
    }

    private void add(String conversationId, Turn turn) {
        if (conversationId == null || conversationId.isBlank()) return;
        Deque<Turn> turns = store.computeIfAbsent(conversationId, k -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(turn);
            while (turns.size() > MAX_TURNS * 2) {
                turns.removeFirst();
            }
        }
    }

    /** Định dạng lịch sử thành text để nhét vào prompt. */
    public String formatHistory(String conversationId) {
        List<Turn> history = history(conversationId);
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Turn t : history) {
            sb.append("user".equals(t.role()) ? "Người dùng: " : "Trợ lý: ")
              .append(t.text()).append("\n");
        }
        return sb.toString().trim();
    }

    public void clear(String conversationId) {
        if (conversationId != null) store.remove(conversationId);
    }
}
