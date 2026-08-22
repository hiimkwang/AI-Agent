package com.ai.aiagent.admin;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.rerank.Reranker;
import com.ai.aiagent.rerank.RerankerProvider;
import com.ai.aiagent.retrieval.HybridRetriever;
import com.ai.aiagent.retrieval.QueryPlanner;
import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import com.ai.aiagent.chat.RelevanceGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class RetrievalDebugController {

    private final QueryPlanner planner;
    private final HybridRetriever retriever;
    private final RerankerProvider rerankers;
    private final RelevanceGate gate;
    private final RagProperties props;

    public RetrievalDebugController(QueryPlanner planner, HybridRetriever retriever,
                                    RerankerProvider rerankers, RelevanceGate gate,
                                    RagProperties props) {
        this.planner = planner;
        this.retriever = retriever;
        this.rerankers = rerankers;
        this.gate = gate;
        this.props = props;
    }

    public record DebugRequest(String question, String category, Integer topK) {
    }

    @PostMapping("/retrieval-test")
    public Map<String, Object> test(@RequestBody DebugRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("Thieu cau hoi.");
        }
        long start = System.currentTimeMillis();

        QueryPlanner.QueryPlan plan = planner.plan(request.question(), List.of());
        long afterPlan = System.currentTimeMillis();

        HybridRetriever.RetrievalResult retrieval =
                retriever.retrieve(plan.variants(), CurrentScope.get(), request.category());
        long afterRetrieval = System.currentTimeMillis();

        int topK = request.topK() == null ? props.getRetrieval().getTopK() : request.topK();
        Reranker reranker = rerankers.get();
        Reranker.RerankResult rerank = retrieval.isEmpty()
                ? Reranker.RerankResult.reliable(List.of(), reranker.name())
                : reranker.rerank(plan.rewritten(), retrieval.candidates(), topK);
        long afterRerank = System.currentTimeMillis();

        RelevanceGate.Decision decision = gate.evaluate(retrieval, rerank);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", request.question());
        out.put("queryVariants", plan.variants());
        out.put("rewritten", plan.wasRewritten() ? plan.rewritten() : null);

        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("planMs", afterPlan - start);
        timing.put("retrievalMs", afterRetrieval - afterPlan);
        timing.put("rerankMs", afterRerank - afterRetrieval);
        out.put("timingMs", timing);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("vectorHits", retrieval.vectorHits());
        counts.put("fulltextHits", retrieval.fulltextHits());
        counts.put("candidatesAfterFusion", retrieval.candidates().size());
        counts.put("selectedAfterRerank", rerank.chunks().size());
        out.put("counts", counts);

        Map<String, Object> scoring = new LinkedHashMap<>();
        scoring.put("bestCosine", round(retrieval.bestCosine()));
        scoring.put("bestRerankScore", round(rerank.bestScore()));
        scoring.put("reranker", rerank.rerankerName());
        scoring.put("rerankReliable", rerank.reliable());
        scoring.put("minRerankScore", props.getRetrieval().getMinRerankScore());
        scoring.put("minVectorScore", props.getRetrieval().getMinVectorScore());
        out.put("scoring", scoring);

        Map<String, Object> gateOut = new LinkedHashMap<>();
        gateOut.put("wouldAbstain", decision.abstain());
        gateOut.put("reason", decision.reason());
        out.put("gate", gateOut);

        out.put("selected", describe(rerank.chunks()));
        out.put("allCandidates", describe(retrieval.candidates()));
        return out;
    }

    private List<Map<String, Object>> describe(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> out = new ArrayList<>(chunks.size());
        int rank = 0;
        for (RetrievedChunk c : chunks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", ++rank);
            m.put("chunkId", c.getId());
            m.put("fileName", c.getFileName());
            m.put("headingPath", c.getHeadingPath());
            m.put("matchedBy", c.getMatchedBy());
            // Null, not a number, when this chunk only matched full-text: showing a
            // ts_rank_cd here is what made "cosine 14.0" appear on the diagnostics screen.
            m.put("cosine", c.getCosine() == null ? null : round(c.getCosine()));
            m.put("fulltextRank", c.getCosine() == null ? round(c.getRawScore()) : null);
            m.put("rrf", round(c.getFusedScore()));
            m.put("finalScore", round(c.getFinalScore()));
            m.put("rerankScore", c.getRerankScore() < 0 ? null : round(c.getRerankScore()));
            m.put("effectiveDate", c.getEffectiveDate());
            m.put("chars", c.getContent() == null ? 0 : c.getContent().length());
            m.put("content", c.getContent());
            out.add(m);
        }
        return out;
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
