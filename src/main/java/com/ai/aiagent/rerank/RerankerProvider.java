package com.ai.aiagent.rerank;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class RerankerProvider {

    private final LlmReranker llmReranker;
    private final CohereReranker cohereReranker;
    private final PassthroughReranker passthrough = new PassthroughReranker();
    private final RagProperties props;

    public RerankerProvider(LlmReranker llmReranker, CohereReranker cohereReranker,
                            RagProperties props) {
        this.llmReranker = llmReranker;
        this.cohereReranker = cohereReranker;
        this.props = props;
    }

    public Reranker get() {
        String configured = props.getRerank().getProvider();
        if ("NONE".equalsIgnoreCase(configured)) {
            return passthrough;
        }
        if ("COHERE".equalsIgnoreCase(configured)) {
            if (cohereReranker.isAvailable()) return cohereReranker;
            log.warn("rag.rerank.provider=COHERE but COHERE_API_KEY is missing, falling back to LLM "
                    + "rerank.");
        }
        return llmReranker;
    }

    static class PassthroughReranker implements Reranker {

        @Override
        public String name() {
            return "NONE";
        }

        @Override
        public RerankResult rerank(String query, List<RetrievedChunk> candidates, int topK) {
            List<RetrievedChunk> out = new ArrayList<>(
                    candidates.subList(0, Math.min(topK, candidates.size())));
            // Passthrough still has to hand the gate a score on the 0..1 relevance scale.
            // Using rawScore meant a full-text-only chunk arrived with a ts_rank_cd of 14, which
            // sails past minRerankScore no matter how irrelevant it is.
            out.forEach(c -> c.setRerankScore(c.getCosine() == null ? 0.0 : c.getCosine()));
            return RerankResult.reliable(out, name());
        }
    }
}
