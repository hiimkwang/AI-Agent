package com.ai.aiagent.rerank;

import com.ai.aiagent.store.StoreModels.RetrievedChunk;

import java.util.List;

public interface Reranker {

    String name();

    RerankResult rerank(String query, List<RetrievedChunk> candidates, int topK);

    record RerankResult(List<RetrievedChunk> chunks, boolean reliable, String rerankerName) {

        public static RerankResult reliable(List<RetrievedChunk> chunks, String name) {
            return new RerankResult(chunks, true, name);
        }

        public static RerankResult degraded(List<RetrievedChunk> chunks, String name) {
            return new RerankResult(chunks, false, name);
        }

        public boolean isEmpty() {
            return chunks.isEmpty();
        }

        public double bestScore() {
            return chunks.stream().mapToDouble(RetrievedChunk::getRerankScore).max().orElse(-1);
        }
    }
}
