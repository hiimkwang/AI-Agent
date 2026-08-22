package com.ai.aiagent.retrieval;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.EmbeddingService;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.store.ChunkRepository;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class HybridRetriever {

    public record RetrievalResult(
            List<RetrievedChunk> candidates,
            int vectorHits,
            int fulltextHits,
            double bestCosine
    ) {
        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    private final EmbeddingService embeddings;
    private final ChunkRepository chunks;
    private final RagProperties props;
    private final ExecutorService searchPool = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()), r -> {
                Thread t = new Thread(r, "retrieval");
                t.setDaemon(true);
                return t;
            });

    public HybridRetriever(EmbeddingService embeddings, ChunkRepository chunks, RagProperties props) {
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.props = props;
    }

    @PreDestroy
    public void shutdown() {
        searchPool.shutdownNow();
    }

    public RetrievalResult retrieve(List<String> variants, AccessScope scope, String category) {
        Set<String> categories = scope.narrowTo(category);
        ChunkRepository.SearchFilter filter = new ChunkRepository.SearchFilter(
                categories,
                scope.isAdmin() ? Set.of() : scope.roles(),
                props.getRetrieval().isExcludeExpired());

        RagProperties.Retrieval cfg = props.getRetrieval();
        List<List<RetrievedChunk>> rankedLists = new ArrayList<>();
        int vectorHits = 0;
        int fulltextHits = 0;

        for (String variant : variants) {
            if (variant == null || variant.isBlank()) continue;

            CompletableFuture<List<RetrievedChunk>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> chunks.vectorSearch(embeddings.embedOne(variant), cfg.getVectorTopK(), filter),
                    searchPool);

            CompletableFuture<List<RetrievedChunk>> textFuture = cfg.isHybridEnabled()
                    ? CompletableFuture.supplyAsync(
                    () -> chunks.fullTextSearch(variant, cfg.getFulltextTopK(), filter), searchPool)
                    : CompletableFuture.completedFuture(List.of());

            List<RetrievedChunk> vectorResults = joinSafely(vectorFuture, "vector", variant);
            List<RetrievedChunk> textResults = joinSafely(textFuture, "full-text", variant);

            // Only the vector branch produces a cosine. The full-text branch scores with
            // ts_rank_cd, which is unbounded - values above 10 are normal - so the two must not
            // share one field that the relevance gate later reads as "the best cosine".
            vectorResults.forEach(c -> c.setCosine(c.getRawScore()));

            vectorHits += vectorResults.size();
            fulltextHits += textResults.size();
            if (!vectorResults.isEmpty()) rankedLists.add(vectorResults);
            if (!textResults.isEmpty()) rankedLists.add(textResults);
        }

        if (rankedLists.isEmpty()) {
            return new RetrievalResult(List.of(), vectorHits, fulltextHits, 0);
        }

        List<RetrievedChunk> fused = dropTablesOfContents(reciprocalRankFusion(rankedLists));
        double bestCosine = fused.stream()
                .map(RetrievedChunk::getCosine)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max().orElse(0);

        if (cfg.isRecencyBoostEnabled()) {
            applyRecencyBoost(fused);
        }

        List<RetrievedChunk> trimmed = fused.size() > cfg.getCandidates()
                ? new ArrayList<>(fused.subList(0, cfg.getCandidates())) : fused;

        log.debug("Hybrid search: {} variants, vector={}, fulltext={}, fused to {} candidates "
                        + "(best cosine={}).",
                variants.size(), vectorHits, fulltextHits, trimmed.size(),
                String.format("%.3f", bestCosine));
        return new RetrievalResult(trimmed, vectorHits, fulltextHits, bestCosine);
    }

    private List<RetrievedChunk> reciprocalRankFusion(List<List<RetrievedChunk>> lists) {
        Map<Long, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<Long, Double> scoreById = new LinkedHashMap<>();
        int k = Math.max(1, props.getRetrieval().getRrfK());

        for (List<RetrievedChunk> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievedChunk chunk = list.get(rank);
                scoreById.merge(chunk.getId(), 1.0 / (k + rank + 1), Double::sum);

                RetrievedChunk existing = byId.get(chunk.getId());
                if (existing == null) {
                    byId.put(chunk.getId(), chunk);
                } else {
                    if ("VECTOR".equals(chunk.getMatchedBy())
                            && chunk.getRawScore() > existing.getRawScore()) {
                        existing.setRawScore(chunk.getRawScore());
                    }
                    // A chunk found by both branches must keep its cosine whichever list
                    // reached the map first.
                    if (existing.getCosine() == null && chunk.getCosine() != null) {
                        existing.setCosine(chunk.getCosine());
                    }
                    existing.addMatchedBy(chunk.getMatchedBy());
                }
            }
        }

        List<RetrievedChunk> merged = new ArrayList<>(byId.size());
        for (Map.Entry<Long, Double> e : scoreById.entrySet()) {
            RetrievedChunk chunk = byId.get(e.getKey());
            chunk.setFusedScore(e.getValue());
            chunk.setFinalScore(e.getValue());
            merged.add(chunk);
        }
        merged.sort(Comparator.comparingDouble(RetrievedChunk::getFusedScore).reversed());
        return merged;
    }

    /**
     * Remove chunks that are a document's table of contents. They match almost any question about
     * the document - they repeat all of its section titles - and answer none of them.
     *
     * <p>If everything looks like a table of contents the original list is kept: an empty result
     * would turn into a refusal, which is worse than a useless passage the gate can still reject.
     */
    private List<RetrievedChunk> dropTablesOfContents(List<RetrievedChunk> fused) {
        List<RetrievedChunk> kept = fused.stream()
                .filter(c -> !TableOfContentsFilter.isTableOfContents(c.getContent()))
                .toList();
        if (kept.isEmpty() || kept.size() == fused.size()) return fused;
        log.debug("Dropped {} table-of-contents chunk(s) from {} candidates.",
                fused.size() - kept.size(), fused.size());
        return new ArrayList<>(kept);
    }

    private void applyRecencyBoost(List<RetrievedChunk> chunks) {
        double weight = props.getRetrieval().getRecencyBoostWeight();
        if (weight <= 0) return;

        LocalDate today = LocalDate.now();
        double maxFused = chunks.stream().mapToDouble(RetrievedChunk::getFusedScore).max().orElse(1);
        if (maxFused <= 0) return;

        for (RetrievedChunk chunk : chunks) {
            LocalDate effective = chunk.getEffectiveDate();
            if (effective == null) continue;
            long days = ChronoUnit.DAYS.between(effective, today);
            if (days < 0) continue;
            double freshness = Math.max(0, 1.0 - (days / (365.0 * 5)));
            chunk.setFinalScore(chunk.getFusedScore() + weight * maxFused * freshness);
        }
        chunks.sort(Comparator.comparingDouble(RetrievedChunk::getFinalScore).reversed());
    }

    private List<RetrievedChunk> joinSafely(CompletableFuture<List<RetrievedChunk>> future,
                                            String branch, String variant) {
        try {
            return future.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Retrieval branch {} failed for variant '{}': {}: {}", branch, abbreviate(variant),
                    cause.getClass().getSimpleName(), cause.getMessage());
            return List.of();
        }
    }

    private String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}
