package com.ai.aiagent.chat;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.rerank.Reranker.RerankResult;
import com.ai.aiagent.retrieval.HybridRetriever.RetrievalResult;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelevanceGateTest {

    private RagProperties props;
    private RelevanceGate gate;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getRetrieval().setMinRerankScore(0.30);
        props.getRetrieval().setMinVectorScore(0.20);
        props.getRetrieval().setAbstainWhenBelowThreshold(true);
        gate = new RelevanceGate(props);
    }

    private RetrievedChunk chunk(double cosine, double rerank) {
        RetrievedChunk c = new RetrievedChunk(1L, 1L, "k", "f.md", "cat", 0,
                "A > B", "noi dung", null, "parent", "title", null, null, null, "ACTIVE", cosine);
        c.setRerankScore(rerank);
        return c;
    }

    @Test
    @DisplayName("Khong co ung vien nao -> tu choi")
    void abstainsWhenNoCandidates() {
        RelevanceGate.Decision d = gate.evaluate(
                new RetrievalResult(List.of(), 0, 0, 0),
                RerankResult.reliable(List.of(), "LLM"));

        assertTrue(d.abstain());
        assertEquals("NO_CANDIDATES", d.reason());
        assertFalse(d.message().isBlank());
    }

    @Test
    @DisplayName("Rerank DANG TIN va tra ve rong -> tu choi (day la loi nghiem trong nhat cua ban cu)")
    void abstainsWhenReliableRerankerFoundNothing() {
        RetrievedChunk candidate = chunk(0.9, -1);
        RelevanceGate.Decision d = gate.evaluate(
                new RetrievalResult(List.of(candidate), 1, 0, 0.9),
                RerankResult.reliable(List.of(), "LLM"));

        assertTrue(d.abstain(), "bo rerank noi khong co gi lien quan thi phai tu choi, "
                + "khong duoc nhoi ngu canh rac vao prompt");
        assertEquals("RERANK_FOUND_NOTHING_RELEVANT", d.reason());
    }

    @Test
    @DisplayName("Diem rerank duoi nguong -> tu choi")
    void abstainsWhenRerankScoreTooLow() {
        RelevanceGate.Decision d = gate.evaluate(
                new RetrievalResult(List.of(chunk(0.8, 0.1)), 1, 0, 0.8),
                RerankResult.reliable(List.of(chunk(0.8, 0.1)), "LLM"));

        assertTrue(d.abstain());
        assertEquals("RERANK_SCORE_BELOW_THRESHOLD", d.reason());
    }

    @Test
    @DisplayName("Diem rerank dat nguong -> tra loi")
    void proceedsWhenRerankScoreOk() {
        RelevanceGate.Decision d = gate.evaluate(
                new RetrievalResult(List.of(chunk(0.8, 0.75)), 1, 0, 0.8),
                RerankResult.reliable(List.of(chunk(0.8, 0.75)), "LLM"));

        assertFalse(d.abstain());
    }

    @Test
    @DisplayName("Rerank BI LOI -> khong tin diem rerank, chuyen sang danh gia bang cosine")
    void fallsBackToCosineWhenRerankerDegraded() {
        RelevanceGate.Decision ok = gate.evaluate(
                new RetrievalResult(List.of(chunk(0.5, -1)), 1, 0, 0.5),
                RerankResult.degraded(List.of(chunk(0.5, -1)), "LLM"));
        assertFalse(ok.abstain());

        RelevanceGate.Decision low = gate.evaluate(
                new RetrievalResult(List.of(chunk(0.05, -1)), 1, 0, 0.05),
                RerankResult.degraded(List.of(chunk(0.05, -1)), "LLM"));
        assertTrue(low.abstain());
        assertEquals("VECTOR_SCORE_BELOW_THRESHOLD", low.reason());
    }

    @Test
    @DisplayName("Tat cong tu choi -> chi tu choi khi that su khong co doan nao")
    void gateCanBeDisabled() {
        props.getRetrieval().setAbstainWhenBelowThreshold(false);

        RelevanceGate.Decision lowScore = gate.evaluate(
                new RetrievalResult(List.of(chunk(0.8, 0.01)), 1, 0, 0.8),
                RerankResult.reliable(List.of(chunk(0.8, 0.01)), "LLM"));
        assertFalse(lowScore.abstain(), "tat cong thi diem thap van tra loi");

        RelevanceGate.Decision empty = gate.evaluate(
                new RetrievalResult(List.of(chunk(0.8, 0.01)), 1, 0, 0.8),
                RerankResult.reliable(List.of(), "LLM"));
        assertTrue(empty.abstain(), "khong con doan nao thi van phai tu choi");
    }
}
