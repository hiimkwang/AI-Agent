package com.ai.aiagent.rerank;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.InternalLlm;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class LlmReranker implements Reranker {

    private static final int MAX_SNIPPET_CHARS = 900;

    private final InternalLlm internalLlm;
    private final RagProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "llm-rerank");
        t.setDaemon(true);
        return t;
    });

    public LlmReranker(InternalLlm internalLlm, RagProperties props) {
        this.internalLlm = internalLlm;
        this.props = props;
    }

    @Override
    public String name() {
        return "LLM";
    }

    @Override
    public RerankResult rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return RerankResult.reliable(List.of(), name());
        }

        int batchSize = Math.max(4, props.getRerank().getBatchSize());

        // Batches are independent, so run them together: sequentially they turned one rerank
        // call into three and added ~1.6s to every question.
        List<CompletableFuture<List<Scored>>> futures = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += batchSize) {
            final int offset = start;
            int end = Math.min(start + batchSize, candidates.size());
            List<RetrievedChunk> slice = candidates.subList(start, end);
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<Scored> batch = scoreBatch(query, slice);
                if (batch == null) return null;
                List<Scored> shifted = new ArrayList<>(batch.size());
                for (Scored s : batch) shifted.add(new Scored(s.index() + offset, s.score()));
                return shifted;
            }, pool));
        }

        List<Scored> all = new ArrayList<>();
        boolean anyBatchFailed = false;
        for (CompletableFuture<List<Scored>> f : futures) {
            List<Scored> batch;
            try {
                batch = f.join();
            } catch (Exception e) {
                batch = null;
            }
            if (batch == null) anyBatchFailed = true;
            else all.addAll(batch);
        }
        // join() returns out of submission order only if a batch failed; sorting by index keeps
        // the RRF order as the stable tie-break for equal scores.
        all.sort((a, b) -> Integer.compare(a.index(), b.index()));

        if (all.isEmpty()) {
            // "Nothing was relevant" and "we never managed to look" must not collapse into the
            // same answer: the first is a reliable refusal, the second has to fall back to the
            // cosine gate instead of telling the user the documents say nothing.
            if (anyBatchFailed) {
                log.warn("Every LLM rerank batch failed; falling back to the original order and "
                        + "marking the result unreliable.");
                return RerankResult.degraded(fallback(candidates, topK), name());
            }
            log.debug("LLM rerank found no passage above the relevance threshold; the answer will "
                    + "be a refusal.");
            return RerankResult.reliable(List.of(), name());
        }

        all.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<RetrievedChunk> out = new ArrayList<>();
        for (Scored s : all) {
            if (out.size() >= topK) break;
            RetrievedChunk chunk = candidates.get(s.index());
            chunk.setRerankScore(s.score());
            out.add(chunk);
        }
        log.debug("LLM rerank: {} candidates in {} batch(es), {} passages kept, top score {}.",
                candidates.size(), (candidates.size() + batchSize - 1) / batchSize, out.size(),
                String.format("%.2f", out.get(0).getRerankScore()));
        return RerankResult.reliable(out, name());
    }

    /**
     * Score one batch. Indices in the result are local to {@code batch}.
     *
     * @return null when this batch could not be scored at all
     */
    private List<Scored> scoreBatch(String query, List<RetrievedChunk> batch) {
        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            listing.append('[').append(i).append("] ")
                    .append(truncate(batch.get(i).rerankText()))
                    .append("\n\n");
        }

        String prompt = """
                Ban la bo loc xep hang do lien quan. Cho mot CAU HOI va danh sach DOAN VAN
                (danh so tu 0).

                Nhiem vu: cham diem tung doan tu 0.0 den 1.0 theo muc do doan do THUC SU chua
                thong tin giup tra loi cau hoi.
                  - 1.0 = tra loi truc tiep va day du cau hoi
                  - 0.5 = co lien quan mot phan, can them thong tin khac
                  - 0.0 = khong lien quan (chi trung tu khoa nhung lac de)

                CHI liet ke cac doan co diem >= 0.3, sap xep giam dan theo diem.
                Neu KHONG doan nao dat 0.3, tra ve mang rong [] - dieu nay hoan toan binh
                thuong va can thiet, dung co gang chon bua mot doan nao.

                Tra ve DUY NHAT mot mang JSON, khong giai thich, dang:
                [{"i": 3, "score": 0.92}, {"i": 0, "score": 0.55}]

                CAU HOI: %s

                CAC DOAN VAN:
                %s
                """.formatted(query, listing);

        String response;
        try {
            response = internalLlm.generate(prompt);
        } catch (Exception e) {
            log.warn("LLM rerank batch failed ({}).", e.getMessage());
            return null;
        }

        try {
            List<Scored> scored = parse(response, batch.size());
            if (scored == null) {
                log.warn("LLM rerank batch did not return a JSON array.");
                return null;
            }
            return scored;
        } catch (Exception e) {
            log.warn("LLM rerank batch returned unparseable JSON. Response: {}",
                    truncate(response, 200));
            return null;
        }
    }

    private record Scored(int index, double score) {
    }

    private List<Scored> parse(String response, int size) throws Exception {
        if (response == null) return null;
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) return null;

        JsonNode array = mapper.readTree(response.substring(start, end + 1));
        if (!array.isArray()) return null;

        List<Scored> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            int index;
            double score;
            if (node.isObject()) {
                index = node.path("i").asInt(node.path("index").asInt(-1));
                score = node.path("score").asDouble(-1);
            } else if (node.isNumber()) {
                index = node.asInt(-1);
                score = 0.5;
            } else {
                continue;
            }
            if (index < 0 || index >= size) continue;
            if (score < 0) score = 0.5;
            if (seen.add(index)) out.add(new Scored(index, Math.min(1.0, score)));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    private List<RetrievedChunk> fallback(List<RetrievedChunk> candidates, int topK) {
        List<RetrievedChunk> out = new ArrayList<>(
                candidates.subList(0, Math.min(topK, candidates.size())));
        out.forEach(c -> c.setRerankScore(-1));
        return out;
    }

    private String truncate(String s) {
        return truncate(s, MAX_SNIPPET_CHARS);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
