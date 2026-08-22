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
