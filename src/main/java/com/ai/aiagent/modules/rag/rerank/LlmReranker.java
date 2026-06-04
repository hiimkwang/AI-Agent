package com.ai.aiagent.modules.rag.rerank;

import com.ai.aiagent.modules.rag.llm.InternalLlm;
import com.ai.aiagent.modules.rag.store.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rerank bằng LLM: nhờ mô hình đọc từng ứng viên và chọn ra các đoạn thực sự liên quan,
 * sắp xếp theo độ liên quan giảm dần. Khắc phục điểm yếu "điểm cao nhưng lạc đề" của
 * vector similarity. Là lựa chọn mặc định (không cần dịch vụ ngoài).
 */
@Component
@Slf4j
public class LlmReranker implements Reranker {

    private final InternalLlm internalLlm;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmReranker(InternalLlm internalLlm) {
        this.internalLlm = internalLlm;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();

        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            listing.append("[").append(i).append("] ")
                    .append(candidates.get(i).searchableText()).append("\n\n");
        }

        String prompt = """
                Bạn là bộ lọc xếp hạng độ liên quan. Cho một CÂU HỎI và danh sách ĐOẠN VĂN (đánh số).
                Chọn các đoạn THỰC SỰ chứa thông tin giúp trả lời câu hỏi, sắp xếp theo độ liên quan
                GIẢM DẦN. Chỉ trả về DUY NHẤT một mảng JSON các số thứ tự, không giải thích.
                Ví dụ: [3, 0, 5]. Nếu không đoạn nào liên quan, trả về: []

                CÂU HỎI: %s

                CÁC ĐOẠN VĂN:
                %s
                """.formatted(query, listing);

        try {
            String response = internalLlm.model().generate(prompt);
            List<Integer> order = parseIndices(response, candidates.size());
            if (order.isEmpty()) {
                log.warn("LLM rerank: không đoạn nào được chọn -> fallback giữ thứ tự gốc.");
                return fallback(candidates, topK);
            }
            List<RetrievedChunk> result = new ArrayList<>();
            for (int idx : order) {
                if (result.size() >= topK) break;
                result.add(candidates.get(idx));
            }
            log.info("LLM rerank chọn thứ tự {} -> giữ {} đoạn.", order, result.size());
            return result;
        } catch (Exception e) {
            log.warn("LLM rerank lỗi ({}) -> fallback giữ thứ tự gốc.", e.getMessage());
            return fallback(candidates, topK);
        }
    }

    private List<Integer> parseIndices(String response, int size) {
        List<Integer> indices = new ArrayList<>();
        try {
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start < 0 || end <= start) return indices;
            JsonNode arr = mapper.readTree(response.substring(start, end + 1));
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    int v = node.asInt(-1);
                    if (v >= 0 && v < size && !indices.contains(v)) indices.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        return indices;
    }

    private List<RetrievedChunk> fallback(List<RetrievedChunk> candidates, int topK) {
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }
}
