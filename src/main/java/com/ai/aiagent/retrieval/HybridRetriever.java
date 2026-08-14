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

/**
 * Truy xuat lai (hybrid) + multi-query + RRF.
 *
 * Cac diem da sua:
 *   - Hai nhanh vector va full-text chay THUC SU SONG SONG bang CompletableFuture.
 *     Javadoc cu ghi "chay song song" nhung code goi tuan tu, cong them latency vo ich.
 *   - RRF GIU LAI diem goc (cosine tot nhat) ben canh diem thu hang, nho vay van co
 *     mot con so tuyet doi de dat nguong tu choi tra loi - truoc day RRF bo hoan toan
 *     diem goc nen khong con cach nao noi "tai lieu khong du lien quan".
 *   - Gop ket qua cua NHIEU bien the truy van (cau goc + cau viet lai + HyDE).
 *   - Boost tai lieu moi ban hanh, loai tai lieu het hieu luc.
 */
@Service
@Slf4j
public class HybridRetriever {

    /**
     * @param candidates   ung vien sau khi gop, da cat con {@code candidates}
     * @param vectorHits   tong so ket qua nhanh vector (de chan doan)
     * @param fulltextHits tong so ket qua nhanh full-text
     * @param bestRawScore diem cosine cao nhat gap duoc - dung cho nguong tu choi
     */
    public record RetrievalResult(
            List<RetrievedChunk> candidates,
            int vectorHits,
            int fulltextHits,
            double bestRawScore
    ) {
        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    private final EmbeddingService embeddings;
    private final ChunkRepository chunks;
    private final RagProperties props;
    /** Pool rieng cho hai nhanh tim kiem; daemon nen khong chan luc tat ung dung. */
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

    /**
     * @param variants danh sach bien the truy van (cau goc, cau viet lai, HyDE)
     * @param scope    pham vi truy cap - quyet dinh ACL, KHONG tin category tu client
     * @param category bo loc category do client yeu cau (se bi thu hep theo scope)
     */
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

            // Hai nhanh chay song song
            CompletableFuture<List<RetrievedChunk>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> chunks.vectorSearch(embeddings.embedOne(variant), cfg.getVectorTopK(), filter),
                    searchPool);

            CompletableFuture<List<RetrievedChunk>> textFuture = cfg.isHybridEnabled()
                    ? CompletableFuture.supplyAsync(
                    () -> chunks.fullTextSearch(variant, cfg.getFulltextTopK(), filter), searchPool)
                    : CompletableFuture.completedFuture(List.of());

            // Hai nhanh phai that bai DOC LAP voi nhau. Neu gop hai lenh join vao
            // cung mot try thi mot nhanh loi se lam MAT LUON ket qua cua nhanh con
            // lai - dung loi da gap khi thu nghiem: nhanh full-text nem loi va keo
            // theo ca ket qua vector vua tim duoc.
            List<RetrievedChunk> vectorResults = joinSafely(vectorFuture, "vector", variant);
            List<RetrievedChunk> textResults = joinSafely(textFuture, "full-text", variant);

            vectorHits += vectorResults.size();
            fulltextHits += textResults.size();
            if (!vectorResults.isEmpty()) rankedLists.add(vectorResults);
            if (!textResults.isEmpty()) rankedLists.add(textResults);
        }

        if (rankedLists.isEmpty()) {
            return new RetrievalResult(List.of(), vectorHits, fulltextHits, 0);
        }

        List<RetrievedChunk> fused = reciprocalRankFusion(rankedLists);
        double bestRaw = fused.stream().mapToDouble(RetrievedChunk::getRawScore).max().orElse(0);

        if (cfg.isRecencyBoostEnabled()) {
            applyRecencyBoost(fused);
        }

        List<RetrievedChunk> trimmed = fused.size() > cfg.getCandidates()
                ? new ArrayList<>(fused.subList(0, cfg.getCandidates())) : fused;

        log.debug("Hybrid: {} bien the, vector={}, full-text={}, gop con {} ung vien (best cosine={}).",
                variants.size(), vectorHits, fulltextHits, trimmed.size(),
                String.format("%.3f", bestRaw));
        return new RetrievalResult(trimmed, vectorHits, fulltextHits, bestRaw);
    }

    /**
     * Reciprocal Rank Fusion.
     *
     * Diem = tong tren moi danh sach cua 1/(k + thu_hang). Uu diem: khong can chuan
     * hoa thang diem giua vector va full-text.
     *
     * Khac ban cu: giu lai diem COSINE cao nhat vao {@code rawScore}, vi diem RRF chi
     * mang y nghia tuong doi trong mot luot truy xuat, khong dung de dat nguong duoc.
     */
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
                    // Giu diem cosine tot nhat; ts_rank_cd khong cung thang nen bo qua
                    if ("VECTOR".equals(chunk.getMatchedBy())
                            && chunk.getRawScore() > existing.getRawScore()) {
                        existing.setRawScore(chunk.getRawScore());
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
     * Boost nhe tai lieu moi ban hanh.
     *
     * Khong co buoc nay, mot quy dinh nam 2019 va ban thay the nam 2026 duoc xep ngang
     * hang, va cau tra loi co the trich dan ban cu. Boost tinh theo do "moi" cua
     * {@code effective_date} trong 5 nam gan nhat.
     */
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
            if (days < 0) continue; // chua co hieu luc
            double freshness = Math.max(0, 1.0 - (days / (365.0 * 5)));
            chunk.setFinalScore(chunk.getFusedScore() + weight * maxFused * freshness);
        }
        chunks.sort(Comparator.comparingDouble(RetrievedChunk::getFinalScore).reversed());
    }

    /** Lay ket qua mot nhanh; nhanh loi tra ve rong va CHI mat nhanh do. */
    private List<RetrievedChunk> joinSafely(CompletableFuture<List<RetrievedChunk>> future,
                                            String branch, String variant) {
        try {
            return future.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Nhanh {} loi cho bien the '{}': {}: {}", branch, abbreviate(variant),
                    cause.getClass().getSimpleName(), cause.getMessage());
            return List.of();
        }
    }

    private String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}
