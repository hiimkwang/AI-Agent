package com.ai.aiagent.rerank;

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

/**
 * Rerank bang LLM: nho mo hinh doc tung ung vien va cho DIEM do lien quan.
 *
 * Hai thay doi so voi ban cu:
 *
 *  1) Yeu cau tra ve CA DIEM, khong chi thu tu. Truoc day chi co mang chi so nen
 *     khong co con so nao de dat nguong tu choi tra loi.
 *  2) Mang rong duoc TON TRONG. Truoc day {@code order.isEmpty()} bi coi la loi va
 *     fallback nhoi lai top-5 goc; gio mang rong nghia la "khong co gi lien quan"
 *     va he thong se tra "khong tim thay trong tai lieu".
 *     Chi khi that su co EXCEPTION hoac khong parse duoc JSON thi moi tra
 *     {@code degraded} de {@code RelevanceGate} chuyen sang danh gia bang cosine.
 */
@Component
@Slf4j
public class LlmReranker implements Reranker {

    private static final int MAX_SNIPPET_CHARS = 900;

    private final InternalLlm internalLlm;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmReranker(InternalLlm internalLlm) {
        this.internalLlm = internalLlm;
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

        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            listing.append('[').append(i).append("] ")
                    .append(truncate(candidates.get(i).rerankText()))
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
            log.warn("LLM rerank loi ({}) -> giu thu tu goc va danh dau khong dang tin.",
                    e.getMessage());
            return RerankResult.degraded(fallback(candidates, topK), name());
        }

        List<Scored> scored;
        try {
            scored = parse(response, candidates.size());
        } catch (Exception e) {
            log.warn("LLM rerank tra ve JSON khong doc duoc -> khong dang tin. Phan hoi: {}",
                    truncate(response, 200));
            return RerankResult.degraded(fallback(candidates, topK), name());
        }

        if (scored == null) {
            // Khong tim thay mang JSON nao trong phan hoi => coi la loi, khong phai "rong"
            log.warn("LLM rerank khong tra ve mang JSON -> khong dang tin.");
            return RerankResult.degraded(fallback(candidates, topK), name());
        }

        if (scored.isEmpty()) {
            // ĐÂY la truong hop truoc day bi hieu sai: LLM noi "khong co gi lien quan"
            log.info("LLM rerank: khong doan nao dat nguong lien quan -> se tra loi "
                    + "'khong tim thay trong tai lieu'.");
            return RerankResult.reliable(List.of(), name());
        }

        List<RetrievedChunk> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= topK) break;
            RetrievedChunk chunk = candidates.get(s.index());
            chunk.setRerankScore(s.score());
            out.add(chunk);
        }
        log.debug("LLM rerank: {} ung vien -> giu {} doan, diem cao nhat {}.",
                candidates.size(), out.size(),
                String.format("%.2f", out.isEmpty() ? 0 : out.get(0).getRerankScore()));
        return RerankResult.reliable(out, name());
    }

    private record Scored(int index, double score) {
    }

    /**
     * @return null neu khong tim thay mang JSON (loi that su);
     *         danh sach rong neu mang JSON rong (khong co gi lien quan)
     */
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
                // Chap nhan ca dinh dang cu [3, 0, 5] de tuong thich nguoc
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

    /** Khong xep hang lai duoc: giu thu tu gop RRF, diem rerank de la -1 (khong xac dinh). */
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
