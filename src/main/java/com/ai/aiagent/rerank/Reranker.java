package com.ai.aiagent.rerank;

import com.ai.aiagent.store.StoreModels.RetrievedChunk;

import java.util.List;

/**
 * Sap xep lai ung vien theo do lien quan THUC SU voi cau hoi.
 */
public interface Reranker {

    String name();

    RerankResult rerank(String query, List<RetrievedChunk> candidates, int topK);

    /**
     * Ket qua rerank.
     *
     * {@code reliable} la thay doi quan trong nhat cua ban nay. Truoc day khi LLM
     * tra ve mang rong (nghia la "khong doan nao lien quan"), code coi do la LOI va
     * fallback nhoi lai 5 ung vien dau theo thu tu goc. Dung vao luc he thong nen
     * tra "khong tim thay trong tai lieu" thi no lai dua ngu canh rac vao prompt -
     * day la nguon bia dat lon nhat.
     *
     * Gio phan biet ro hai truong hop:
     *   - {@code reliable = true},  danh sach RONG  => that su khong co gi lien quan
     *                                                  => tu choi tra loi
     *   - {@code reliable = false}                   => BO RERANK BI LOI; quay ve thu tu
     *                                                  goc nhung danh dau khong dang tin,
     *                                                  de {@code RelevanceGate} chuyen sang
     *                                                  danh gia bang diem cosine
     *
     * @param chunks   danh sach da xep hang, moi phan tu co {@code rerankScore}
     * @param reliable bo rerank co chay thanh cong hay khong
     */
    record RerankResult(List<RetrievedChunk> chunks, boolean reliable, String rerankerName) {

        public static RerankResult reliable(List<RetrievedChunk> chunks, String name) {
            return new RerankResult(chunks, true, name);
        }

        /** Bo rerank loi: giu thu tu goc nhung KHONG coi diem la dang tin. */
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
