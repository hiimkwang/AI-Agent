package com.ai.aiagent.modules.rag.retrieval;

import com.ai.aiagent.modules.rag.llm.InternalLlm;
import com.ai.aiagent.modules.rag.memory.ConversationMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Viết lại câu hỏi cho việc truy xuất.
 *
 * Hai mục tiêu:
 *  - Multi-turn: gộp lịch sử hội thoại để câu hỏi nối tiếp ("còn cái kia thì sao?")
 *    trở thành câu hỏi ĐỘC LẬP, đầy đủ, tự hiểu được mà không cần ngữ cảnh.
 *  - Làm rõ: bổ sung từ khóa/thuật ngữ giúp tìm kiếm tốt hơn.
 */
@Service
@Slf4j
public class QueryRewriter {

    private final InternalLlm internalLlm;
    private final ConversationMemory memory;

    @Value("${rag.query-rewrite.enabled}")
    private boolean enabled;

    public QueryRewriter(InternalLlm internalLlm, ConversationMemory memory) {
        this.internalLlm = internalLlm;
        this.memory = memory;
    }

    /**
     * @return câu hỏi đã viết lại để dùng cho truy xuất (giữ nguyên nếu tắt/không có lịch sử/lỗi).
     */
    public String rewrite(String conversationId, String question) {
        if (!enabled) return question;

        String history = memory.formatHistory(conversationId);
        if (history.isBlank()) {
            return question; // không có ngữ cảnh trước đó -> không cần viết lại
        }

        try {
            String prompt = """
                    Dưới đây là lịch sử hội thoại và câu hỏi mới nhất của người dùng.
                    Hãy viết lại CÂU HỎI MỚI thành một câu hỏi ĐỘC LẬP, đầy đủ ngữ cảnh, tự hiểu được
                    mà không cần đọc lịch sử (thay các đại từ "nó/cái đó/vậy..." bằng đối tượng cụ thể).
                    Giữ nguyên ngôn ngữ tiếng Việt. CHỈ trả về câu hỏi đã viết lại, không giải thích.

                    LỊCH SỬ HỘI THOẠI:
                    %s

                    CÂU HỎI MỚI: %s

                    CÂU HỎI ĐỘC LẬP:
                    """.formatted(history, question);

            String rewritten = internalLlm.model().generate(prompt);
            if (rewritten == null || rewritten.isBlank()) return question;
            rewritten = rewritten.trim();
            log.info("Query rewrite: '{}' -> '{}'", question, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("Query rewrite lỗi ({}) -> dùng câu hỏi gốc.", e.getMessage());
            return question;
        }
    }
}
