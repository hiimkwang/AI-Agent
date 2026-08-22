package com.ai.aiagent.llm;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class InternalLlm {

    private final LlmClientFactory factory;
    private final RagProperties props;
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

    public String generate(String prompt) {
        return generate(null, prompt);
    }

    public String generate(String system, String prompt) {
        LlmResponse response = client().complete(new LlmRequest(system, prompt, null));
        accumulated.updateAndGet(u -> u.plus(response.usage()));
        return response.text();
    }

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
