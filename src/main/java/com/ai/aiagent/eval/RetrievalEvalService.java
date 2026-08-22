package com.ai.aiagent.eval;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.rerank.Reranker;
import com.ai.aiagent.rerank.RerankerProvider;
import com.ai.aiagent.retrieval.GlossaryService;
import com.ai.aiagent.retrieval.HybridRetriever;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.store.EvalRepository;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class RetrievalEvalService {

    public record RetrievalEvalRequest(String suite, String category, Integer topK,
                                       boolean includeRerank) {
    }

    public record CaseResult(String question, String expectedSource, Integer rank,
                             Integer rankAfterRerank, int candidates, List<String> topSources) {
        public boolean measured() {
            return expectedSource != null;
        }
    }

    public record RetrievalReport(long runId, String suite, int total, int measured, int skipped,
                                  Map<String, Double> recallAt, Double mrr, Double mrrReranked,
                                  Integer avgLatencyMs, List<CaseResult> results) {
    }

    private static final int[] RECALL_CUTOFFS = {1, 3, 5, 10};

    private final EvalRepository repository;
    private final HybridRetriever retriever;
    private final RerankerProvider rerankers;
    private final GlossaryService glossary;
    private final RagProperties props;

    public RetrievalEvalService(EvalRepository repository, HybridRetriever retriever,
                                RerankerProvider rerankers, GlossaryService glossary,
                                RagProperties props) {
        this.repository = repository;
        this.retriever = retriever;
        this.rerankers = rerankers;
        this.glossary = glossary;
        this.props = props;
    }

    public RetrievalReport run(RetrievalEvalRequest request, AccessScope scope) {
        String suite = request.suite() == null || request.suite().isBlank()
                ? "default" : request.suite();
        List<EvalRepository.EvalCase> cases = repository.listCases(suite, true);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Bo cau hoi '" + suite + "' khong co case nao dang bat.");
        }

        int topK = request.topK() != null && request.topK() > 0
                ? request.topK() : props.getRetrieval().getCandidates();

        long runId = repository.createRun(suite, "-", "-", cases.size(),
                Map.of("topK", topK, "includeRerank", request.includeRerank(),
                        "embedding", props.getEmbedding().modelName(),
                        "hybrid", props.getRetrieval().isHybridEnabled(),
                        "glossary", props.getRetrieval().isGlossaryEnabled()),
                "RETRIEVAL");

        List<CaseResult> results = new ArrayList<>();
        int[] hitsAt = new int[RECALL_CUTOFFS.length];
        double mrrSum = 0;
        double mrrRerankedSum = 0;
        int measured = 0;
        int skipped = 0;
        long latencySum = 0;

        for (EvalRepository.EvalCase testCase : cases) {
            String expected = testCase.expectedSource() == null
                    || testCase.expectedSource().isBlank() ? null
                    : testCase.expectedSource().toLowerCase(Locale.ROOT);

            long start = System.currentTimeMillis();
            List<RetrievedChunk> candidates;
            try {
                candidates = retriever.retrieve(variants(testCase.question()), scope,
                        testCase.category() != null ? testCase.category() : request.category())
                        .candidates();
            } catch (Exception e) {
                log.warn("Retrieval eval failed for '{}': {}", testCase.question(), e.getMessage());
                results.add(new CaseResult(testCase.question(), testCase.expectedSource(),
                        null, null, 0, List.of()));
                skipped++;
                continue;
            }

            List<RetrievedChunk> window = candidates.size() > topK
                    ? candidates.subList(0, topK) : candidates;

            Integer rankAfter = null;
            if (request.includeRerank() && !candidates.isEmpty()) {
                rankAfter = rankOf(rerank(testCase.question(), candidates), expected);
            }
            latencySum += System.currentTimeMillis() - start;

            Integer rank = rankOf(window, expected);
            results.add(new CaseResult(testCase.question(), testCase.expectedSource(), rank,
                    rankAfter, candidates.size(), topSources(window)));

            if (expected == null) {
                skipped++;
                continue;
            }
            measured++;
            if (rank != null) {
                mrrSum += 1.0 / rank;
                for (int i = 0; i < RECALL_CUTOFFS.length; i++) {
                    if (rank <= RECALL_CUTOFFS[i]) hitsAt[i]++;
                }
            }
            if (rankAfter != null) mrrRerankedSum += 1.0 / rankAfter;
        }

        double[] recall = new double[RECALL_CUTOFFS.length];
        Map<String, Double> recallAt = new LinkedHashMap<>();
        for (int i = 0; i < RECALL_CUTOFFS.length; i++) {
            recall[i] = measured == 0 ? 0 : round((double) hitsAt[i] / measured);
            recallAt.put("@" + RECALL_CUTOFFS[i], recall[i]);
        }
        Double mrr = measured == 0 ? null : round(mrrSum / measured);
        Double mrrReranked = !request.includeRerank() || measured == 0
                ? null : round(mrrRerankedSum / measured);
        Integer avgLatency = cases.isEmpty() ? null : (int) (latencySum / cases.size());

        repository.completeRetrievalRun(runId, measured, skipped, recall, mrr, mrrReranked,
                avgLatency);

        log.info("Retrieval eval '{}': {} case(s) ({} measured) | recall@1={} @3={} @5={} "
                        + "@10={} | MRR={} MRR after rerank={} | {} ms/case",
                suite, cases.size(), measured, recall[0], recall[1], recall[2], recall[3],
                mrr, mrrReranked, avgLatency);

        return new RetrievalReport(runId, suite, cases.size(), measured, skipped, recallAt,
                mrr, mrrReranked, avgLatency, results);
    }

    private List<String> variants(String question) {
        List<String> variants = new ArrayList<>();
        variants.add(question);
        if (props.getRetrieval().isGlossaryEnabled()) {
            var expansions = glossary.expand(question);
            if (!expansions.isEmpty()) {
                variants.add(question + " " + String.join(" ", expansions));
            }
        }
        return variants;
    }

    private List<RetrievedChunk> rerank(String question, List<RetrievedChunk> candidates) {
        try {
            Reranker.RerankResult result = rerankers.get()
                    .rerank(question, candidates, props.getRetrieval().getTopK());
            return result.chunks();
        } catch (Exception e) {
            log.warn("Rerank failed during eval ({}), the post-rerank column is skipped.",
                    e.getMessage());
            return List.of();
        }
    }

    private Integer rankOf(List<RetrievedChunk> chunks, String expectedLower) {
        if (expectedLower == null) return null;
        for (int i = 0; i < chunks.size(); i++) {
            String name = chunks.get(i).getFileName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(expectedLower)) {
                return i + 1;
            }
        }
        return null;
    }

    private List<String> topSources(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(RetrievedChunk::getFileName)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(5)
                .toList();
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
