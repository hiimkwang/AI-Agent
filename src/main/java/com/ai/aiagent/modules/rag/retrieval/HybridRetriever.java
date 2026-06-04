package com.ai.aiagent.modules.rag.retrieval;

import com.ai.aiagent.modules.rag.store.RagVectorRepository;
import com.ai.aiagent.modules.rag.store.RetrievedChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Truy xuất lai (Hybrid): chạy SONG SONG hai nhánh
 *   1) Vector search   – bắt ngữ nghĩa (đồng nghĩa, diễn đạt khác)
 *   2) Full-text search – bắt từ khóa chính xác (tên riêng, mã số, viết tắt)
 * rồi gộp kết quả bằng RRF (Reciprocal Rank Fusion).
 *
 * RRF: điểm của một tài liệu = tổng trên mọi danh sách của 1 / (rrfK + thứ_hạng).
 * Ưu điểm: không cần chuẩn hóa thang điểm giữa hai nhánh, chỉ dựa vào THỨ HẠNG.
 */
@Service
@Slf4j
public class HybridRetriever {

    private final EmbeddingModel embeddingModel;
    private final RagVectorRepository repository;

    @Value("${rag.retrieval.hybrid-enabled}")
    private boolean hybridEnabled;
    @Value("${rag.retrieval.vector-top-k}")
    private int vectorTopK;
    @Value("${rag.retrieval.fulltext-top-k}")
    private int fulltextTopK;
    @Value("${rag.retrieval.rrf-k}")
    private int rrfK;
    @Value("${rag.retrieval.candidates}")
    private int candidates;

    public HybridRetriever(EmbeddingModel embeddingModel, RagVectorRepository repository) {
        this.embeddingModel = embeddingModel;
        this.repository = repository;
    }

    /** Trả về danh sách ứng viên (đã gộp + cắt còn {@code candidates}) cho bước rerank. */
    public List<RetrievedChunk> retrieve(String query) {
        return retrieve(query, null);
    }

    /**
     * @param category nếu khác null/blank thì chỉ tìm trong các tài liệu thuộc nhóm này.
     */
    public List<RetrievedChunk> retrieve(String query, String category) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<RetrievedChunk> vectorHits = repository.vectorSearch(queryEmbedding.vector(), vectorTopK, category);

        if (!hybridEnabled) {
            log.info("Hybrid TẮT – chỉ dùng vector search: {} ứng viên.", vectorHits.size());
            return vectorHits.size() > candidates ? vectorHits.subList(0, candidates) : vectorHits;
        }

        List<RetrievedChunk> textHits = repository.fullTextSearch(query, fulltextTopK, category);
        log.info("Hybrid: vector={} ứng viên, full-text={} ứng viên.", vectorHits.size(), textHits.size());

        List<RetrievedChunk> fused = reciprocalRankFusion(vectorHits, textHits);
        return fused.size() > candidates ? fused.subList(0, candidates) : fused;
    }

    /** Gộp nhiều danh sách đã xếp hạng bằng công thức RRF. */
    @SafeVarargs
    private List<RetrievedChunk> reciprocalRankFusion(List<RetrievedChunk>... lists) {
        Map<Long, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<Long, Double> scoreById = new LinkedHashMap<>();

        for (List<RetrievedChunk> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievedChunk chunk = list.get(rank);
                double contribution = 1.0 / (rrfK + rank + 1); // rank bắt đầu từ 0 -> +1
                scoreById.merge(chunk.getId(), contribution, Double::sum);
                byId.putIfAbsent(chunk.getId(), chunk);
            }
        }

        List<RetrievedChunk> merged = new ArrayList<>();
        for (Map.Entry<Long, Double> e : scoreById.entrySet()) {
            RetrievedChunk chunk = byId.get(e.getKey());
            chunk.setFusedScore(e.getValue());
            merged.add(chunk);
        }
        merged.sort(Comparator.comparingDouble(RetrievedChunk::getFusedScore).reversed());
        return merged;
    }
}
