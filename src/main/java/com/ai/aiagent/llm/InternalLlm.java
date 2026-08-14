package com.ai.aiagent.llm;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Model "re" cho cac tac vu NOI BO cua pipeline: viet lai cau hoi, sinh HyDE,
 * sinh ngu canh chunk, rerank, cham diem eval.
 *
 * Tach hoan toan khoi model tra loi nguoi dung (nguoi dung tu chon) de khong lan
 * chi phi va de doi rieng tung ben.
 */
@Component
@Slf4j
public class InternalLlm {

    private final LlmClientFactory factory;
    private final RagProperties props;
    /** Tong chi phi cac tac vu noi bo tu luc khoi dong, hien tren trang metrics. */
    private final AtomicReference<LlmUsage> accumulated = new AtomicReference<>(LlmUsage.EMPTY);

    public InternalLlm(LlmClientFactory factory, RagProperties props) {
        this.factory = factory;
        this.props = props;
    }

    public LlmClient client() {
        return factory.get(
                LlmProvider.fromString(props.getInternal().getProvider()),
                props.getInternal().getModel());
    }

    /**
     * Goi model noi bo, tra ve van ban. Loi duoc nem ra de caller tu quyet dinh
     * fallback (moi buoc phu tro deu phai chay tiep duoc khi buoc nay that bai).
     */
    public String generate(String prompt) {
        return generate(null, prompt);
    }

    public String generate(String system, String prompt) {
        LlmResponse response = client().complete(new LlmRequest(system, prompt, null));
        accumulated.updateAndGet(u -> u.plus(response.usage()));
        return response.text();
    }

    /** Goi va tra ve ca so token/chi phi de cong vao tong cua cau tra loi. */
    public LlmResponse call(String system, String prompt, Integer maxOutputTokens) {
        LlmResponse response = client().complete(new LlmRequest(system, prompt, maxOutputTokens));
        accumulated.updateAndGet(u -> u.plus(response.usage()));
        return response;
    }

    public LlmUsage accumulatedUsage() {
        return accumulated.get();
    }

    public String describe() {
        return props.getInternal().getProvider() + "/" + props.getInternal().getModel();
    }
}
