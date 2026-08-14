package com.ai.aiagent.chat;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.rerank.Reranker.RerankResult;
import com.ai.aiagent.retrieval.HybridRetriever.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Quyet dinh CO NEN TRA LOI hay khong.
 *
 * Day la thu truoc day khong ton tai. Hai ly do khien he thong gan nhu khong bao gio
 * noi duoc "toi khong biet" - cau quan trong nhat cua mot chatbot tai lieu noi bo:
 *
 *   1) RRF chi dung THU HANG va bo hoan toan diem goc, nen khong con con so tuyet doi
 *      nao de so sanh;
 *   2) bo rerank tra ve mang rong bi coi la loi va bi fallback nhoi ngu canh rac vao.
 *
 * Gio co ba duong ra quyet dinh, theo do tin cay giam dan:
 *   - khong co ung vien nao            -> tu choi
 *   - bo rerank DANG TIN               -> so diem rerank voi min-rerank-score
 *   - bo rerank KHONG dang tin (bi loi)-> so diem cosine tot nhat voi min-vector-score
 */
@Component
@Slf4j
public class RelevanceGate {

    /**
     * @param abstain co nen tu choi tra loi
     * @param reason  ly do (ghi log + tra ve API de chan doan)
     * @param message cau tra loi hien cho nguoi dung khi tu choi
     */
    public record Decision(boolean abstain, String reason, String message) {

        static Decision proceed() {
            return new Decision(false, null, null);
        }

        static Decision abstain(String reason, String message) {
            return new Decision(true, reason, message);
        }
    }

    private static final String NOT_FOUND =
            "Tôi không tìm thấy tài liệu nào liên quan đến câu hỏi này. Bạn có thể hỏi lại rõ "
                    + "hơn không — ví dụ nêu cụ thể tên quy trình, biểu mẫu, hoặc từ khoá liên "
                    + "quan? Cũng có thể tài liệu này chưa được nạp vào hệ thống.";

    private static final String NOT_RELEVANT_ENOUGH =
            "Tôi tìm được một số đoạn tài liệu nhưng chưa đủ liên quan để trả lời chắc chắn. "
                    + "Bạn có thể hỏi cụ thể hơn không — ví dụ nêu rõ tên tài liệu, phòng ban, "
                    + "hoặc tình huống bạn đang gặp? Tôi không muốn đoán để tránh trả lời sai.";

    private final RagProperties props;

    public RelevanceGate(RagProperties props) {
        this.props = props;
    }

    public Decision evaluate(RetrievalResult retrieval, RerankResult rerank) {
        if (retrieval == null || retrieval.isEmpty()) {
            return Decision.abstain("NO_CANDIDATES", NOT_FOUND);
        }
        if (!props.getRetrieval().isAbstainWhenBelowThreshold()) {
            // Tat gate: giu hanh vi "luon tra loi" (khong khuyen nghi)
            return rerank.isEmpty()
                    ? Decision.abstain("RERANK_EMPTY", NOT_FOUND)
                    : Decision.proceed();
        }

        if (rerank.reliable()) {
            if (rerank.isEmpty()) {
                // Bo rerank da chay va noi ro: khong doan nao lien quan
                log.info("Gate: bo rerank ({}) khong chon doan nao -> tu choi tra loi.",
                        rerank.rerankerName());
                return Decision.abstain("RERANK_FOUND_NOTHING_RELEVANT", NOT_FOUND);
            }
            double best = rerank.bestScore();
            double threshold = props.getRetrieval().getMinRerankScore();
            if (best >= 0 && best < threshold) {
                log.info("Gate: diem rerank cao nhat {} < nguong {} -> tu choi tra loi.",
                        String.format("%.2f", best), threshold);
                return Decision.abstain("RERANK_SCORE_BELOW_THRESHOLD", NOT_RELEVANT_ENOUGH);
            }
            return Decision.proceed();
        }

        // Bo rerank bi loi -> khong tin diem rerank, quay ve diem cosine
        double bestCosine = retrieval.bestRawScore();
        double threshold = props.getRetrieval().getMinVectorScore();
        if (bestCosine < threshold) {
            log.info("Gate: rerank khong dang tin va cosine tot nhat {} < nguong {} -> tu choi.",
                    String.format("%.3f", bestCosine), threshold);
            return Decision.abstain("VECTOR_SCORE_BELOW_THRESHOLD", NOT_RELEVANT_ENOUGH);
        }
        log.warn("Gate: bo rerank khong dang tin, tra loi dua tren diem cosine {}.",
                String.format("%.3f", bestCosine));
        return Decision.proceed();
    }
}
