package com.ai.aiagent.rerank;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chon bo rerank theo cau hinh {@code rag.rerank.provider}: LLM | COHERE | NONE.
 * Chon COHERE ma thieu key thi tu dong quay ve LLM.
 */
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
            log.warn("rag.rerank.provider=COHERE nhung thieu COHERE_API_KEY -> dung LLM rerank.");
        }
        return llmReranker;
    }

    /**
     * Khong rerank: giu thu tu gop RRF va quy diem RRF ve thang 0..1 de van co the
     * so voi nguong. Nhanh nhat, re nhat, nhung de lot doan "trung tu khoa lac de".
     */
    static class PassthroughReranker implements Reranker {

        @Override
        public String name() {
            return "NONE";
        }

        @Override
        public RerankResult rerank(String query, List<RetrievedChunk> candidates, int topK) {
            List<RetrievedChunk> out = new ArrayList<>(
                    candidates.subList(0, Math.min(topK, candidates.size())));
            // Khong co bo rerank thi diem tin cay nhat dang co la cosine
            out.forEach(c -> c.setRerankScore(c.getRawScore()));
            return RerankResult.reliable(out, name());
        }
    }
}
