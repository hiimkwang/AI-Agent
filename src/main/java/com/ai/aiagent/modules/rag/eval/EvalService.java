package com.ai.aiagent.modules.rag.eval;

import com.ai.aiagent.modules.rag.eval.EvalDtos.*;
import com.ai.aiagent.modules.rag.llm.InternalLlm;
import com.ai.aiagent.modules.rag.llm.LlmProvider;
import com.ai.aiagent.modules.rag.service.RagChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ đánh giá chất lượng RAG kiểu RAGAS rút gọn, dùng LLM làm "giám khảo":
 *  - faithfulness   : câu trả lời có bám sát tài liệu (không bịa) không?
 *  - answerRelevance: câu trả lời có đúng trọng tâm câu hỏi không?
 *  - contextRecall  : nguồn mong đợi (nếu khai báo) có được truy xuất đúng không?
 *
 * Mục đích: đo lường được thì mới tinh chỉnh tham số (chunk-size, top-k, min-score...) có cơ sở,
 * thay vì chỉnh theo cảm tính.
 */
@Service
@Slf4j
public class EvalService {

    private final RagChatService chatService;
    private final InternalLlm internalLlm;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvalService(RagChatService chatService, InternalLlm internalLlm) {
        this.chatService = chatService;
        this.internalLlm = internalLlm;
    }

    public EvalReport run(EvalRequest request) {
        List<EvalCase> cases = request.getCases() == null ? List.of() : request.getCases();
        LlmProvider provider = LlmProvider.fromString(request.getProvider());

        List<EvalCaseResult> results = new ArrayList<>();
        double sumFaith = 0, sumRel = 0;
        int recallDenom = 0, recallHit = 0;

        for (EvalCase c : cases) {
            RagChatService.ChatResult res =
                    chatService.answer(c.getQuestion(), provider, request.getModel(), null);

            double[] judged = judge(c.getQuestion(), res.answer());
            double faith = judged[0];
            double rel = judged[1];

            Boolean sourceHit = null;
            if (c.getExpectedSource() != null && !c.getExpectedSource().isBlank()) {
                recallDenom++;
                sourceHit = res.sources().stream()
                        .anyMatch(s -> s != null && s.toLowerCase()
                                .contains(c.getExpectedSource().toLowerCase()));
                if (sourceHit) recallHit++;
            }

            sumFaith += faith;
            sumRel += rel;
            results.add(new EvalCaseResult(c.getQuestion(), res.answer(), res.sources(),
                    faith, rel, sourceHit));
        }

        int n = Math.max(1, results.size());
        Double contextRecall = recallDenom > 0 ? (double) recallHit / recallDenom : null;

        return new EvalReport(
                results.size(),
                round(sumFaith / n),
                round(sumRel / n),
                contextRecall == null ? null : round(contextRecall),
                results
        );
    }

    /** Trả về [faithfulness, answerRelevance] trong [0,1] do LLM chấm. */
    private double[] judge(String question, String answer) {
        try {
            String prompt = """
                    Bạn là giám khảo đánh giá câu trả lời của một trợ lý RAG. Chấm 2 tiêu chí, mỗi tiêu chí
                    từ 0.0 đến 1.0:
                    - faithfulness: câu trả lời có hợp lý, không bịa đặt, không mâu thuẫn nội tại không.
                      (Nếu trả lời "không tìm thấy thông tin" thì coi như faithful = 1.0 vì không bịa.)
                    - relevance: câu trả lời có đúng trọng tâm và giải quyết câu hỏi không.

                    CHỈ trả về JSON: {"faithfulness": x, "relevance": y}

                    CÂU HỎI: %s

                    CÂU TRẢ LỜI: %s
                    """.formatted(question, answer);

            String resp = internalLlm.model().generate(prompt);
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode json = mapper.readTree(resp.substring(start, end + 1));
                double f = clamp(json.path("faithfulness").asDouble(0));
                double r = clamp(json.path("relevance").asDouble(0));
                return new double[]{f, r};
            }
        } catch (Exception e) {
            log.warn("Judge lỗi cho câu '{}': {}", question, e.getMessage());
        }
        return new double[]{0, 0};
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
