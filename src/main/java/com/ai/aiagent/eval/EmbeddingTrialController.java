package com.ai.aiagent.eval;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.security.CurrentScope;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thu model embedding ung vien truoc khi quyet dinh nap lai toan bo kho.
 *
 * Nam duoi {@code /admin/**} nen chi ADMIN goi duoc.
 *
 * Quy trinh:
 *   1. GET  /embedding-trial              - xem cau hinh hai ben va tien do
 *   2. POST /embedding-trial/build        - nhung lai chunk dang co bang model ung vien
 *   3. POST /embedding-trial/compare      - do recall@k/MRR cua hai ben tren cung bo cau hoi
 *   4. DELETE /embedding-trial            - xoa bang thu nghiem khi da quyet dinh xong
 */
@RestController
@RequestMapping("/api/v1/rag/admin/embedding-trial")
public class EmbeddingTrialController {

    private final EmbeddingTrialService trial;
    private final RagProperties props;

    public EmbeddingTrialController(EmbeddingTrialService trial, RagProperties props) {
        this.trial = trial;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> status() {
        return trial.describe();
    }

    /**
     * @param rebuild xoa bang cu roi dung lai. BAT BUOC khi doi so chieu: kieu
     *                {@code vector(n)} cua cot khong doi duoc bang ALTER, va giu bang cu
     *                se lam moi lan chen deu loi.
     */
    @PostMapping("/build")
    public Map<String, Object> build(@RequestParam(defaultValue = "false") boolean rebuild) {
        EmbeddingTrialService.BuildStatus status = trial.startBuild(rebuild);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Đang nhúng lại toàn bộ chunk bằng model ứng viên. "
                + "Theo dõi tiến độ ở GET /api/v1/rag/admin/embedding-trial.");
        out.put("status", status);
        return out;
    }

    @PostMapping("/compare")
    public EmbeddingTrialService.Comparison compare(
            @RequestBody(required = false) CompareRequest request) {
        CompareRequest r = request == null ? new CompareRequest(null, null) : request;
        String suite = r.suite() == null || r.suite().isBlank() ? "default" : r.suite();
        int topK = r.topK() != null && r.topK() > 0
                ? r.topK() : props.getRetrieval().getVectorTopK();
        return trial.compare(suite, CurrentScope.get(), topK);
    }

    public record CompareRequest(String suite, Integer topK) {
    }

    @DeleteMapping
    public Map<String, Object> discard() {
        return trial.discard();
    }
}
