package com.ai.aiagent.chat;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.rerank.Reranker.RerankResult;
import com.ai.aiagent.retrieval.HybridRetriever.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RelevanceGate {

    public record Decision(boolean abstain, String reason, String message) {

        static Decision proceed() {
            return new Decision(false, null, null);
        }

        static Decision abstain(String reason, String message) {
            return new Decision(true, reason, message);
        }
    }

    public static final String DEFAULT_NOT_FOUND =
            "Tôi không tìm thấy tài liệu nào liên quan đến câu hỏi này. Bạn có thể hỏi lại rõ "
                    + "hơn không — ví dụ nêu cụ thể tên quy trình, biểu mẫu, hoặc từ khoá liên "
                    + "quan? Cũng có thể tài liệu này chưa được nạp vào hệ thống.";

    public static final String DEFAULT_NOT_RELEVANT_ENOUGH =
            "Tôi tìm được một số đoạn tài liệu nhưng chưa đủ liên quan để trả lời chắc chắn. "
                    + "Bạn có thể hỏi cụ thể hơn không — ví dụ nêu rõ tên tài liệu, phòng ban, "
                    + "hoặc tình huống bạn đang gặp? Tôi không muốn đoán để tránh trả lời sai.";

    private final RagProperties props;

    public RelevanceGate(RagProperties props) {
        this.props = props;
    }

    private String notFound() {
        return orDefault(props.getChat().getNotFoundMessage(), DEFAULT_NOT_FOUND);
    }

    private String notRelevantEnough() {
        return orDefault(props.getChat().getNotRelevantMessage(), DEFAULT_NOT_RELEVANT_ENOUGH);
    }

    private static String orDefault(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured.strip();
    }

    public Decision evaluate(RetrievalResult retrieval, RerankResult rerank) {
        if (retrieval == null || retrieval.isEmpty()) {
            return Decision.abstain("NO_CANDIDATES", notFound());
        }
        if (!props.getRetrieval().isAbstainWhenBelowThreshold()) {
            return rerank.isEmpty()
                    ? Decision.abstain("RERANK_EMPTY", notFound())
                    : Decision.proceed();
        }

        // "Nothing relevant" (reliable and empty) must stay distinct from "the reranker
        // failed" (degraded), which falls back to cosine scoring instead of abstaining.
        if (rerank.reliable()) {
            if (rerank.isEmpty()) {
                log.debug("Reranker {} selected no passage, abstaining.",
                        rerank.rerankerName());
                return Decision.abstain("RERANK_FOUND_NOTHING_RELEVANT", notFound());
            }
            double best = rerank.bestScore();
            double threshold = props.getRetrieval().getMinRerankScore();
            if (best >= 0 && best < threshold) {
                log.debug("Top rerank score {} below threshold {}, abstaining.",
                        String.format("%.2f", best), threshold);
                return Decision.abstain("RERANK_SCORE_BELOW_THRESHOLD", notRelevantEnough());
            }
            return Decision.proceed();
        }

        double bestCosine = retrieval.bestCosine();
        double threshold = props.getRetrieval().getMinVectorScore();
        if (bestCosine < threshold) {
            log.debug("Reranker unreliable and best cosine {} below threshold {}, abstaining.",
                    String.format("%.3f", bestCosine), threshold);
            return Decision.abstain("VECTOR_SCORE_BELOW_THRESHOLD", notRelevantEnough());
        }
        log.warn("Reranker unreliable, falling back to cosine score {} to decide.",
                String.format("%.3f", bestCosine));
        return Decision.proceed();
    }
}
