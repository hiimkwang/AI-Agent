package com.ai.aiagent.modules.rag.store;

/**
 * Kết quả lấy ra từ DB khi truy vấn, kèm điểm số.
 * Dùng class (không phải record) vì điểm số fused được cập nhật trong quá trình RRF.
 */
public class RetrievedChunk {
    private final long id;
    private final String docId;
    private final String fileName;
    private final String category;
    private final int chunkIndex;
    private final String content;
    private final String context;
    private final String parentContent;

    /** Điểm gốc từ nhánh tìm kiếm (cosine hoặc ts_rank). */
    private double rawScore;
    /** Điểm sau khi gộp RRF. */
    private double fusedScore;

    public RetrievedChunk(long id, String docId, String fileName, String category, int chunkIndex,
                          String content, String context, String parentContent, double rawScore) {
        this.id = id;
        this.docId = docId;
        this.fileName = fileName;
        this.category = category;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.context = context;
        this.parentContent = parentContent;
        this.rawScore = rawScore;
    }

    public long getId() { return id; }
    public String getDocId() { return docId; }
    public String getFileName() { return fileName; }
    public String getCategory() { return category; }
    public int getChunkIndex() { return chunkIndex; }
    public String getContent() { return content; }
    public String getContext() { return context; }
    public String getParentContent() { return parentContent; }
    public double getRawScore() { return rawScore; }
    public void setRawScore(double rawScore) { this.rawScore = rawScore; }
    public double getFusedScore() { return fusedScore; }
    public void setFusedScore(double fusedScore) { this.fusedScore = fusedScore; }

    /** Text dùng để rerank / hiển thị (ưu tiên có cả context). */
    public String searchableText() {
        if (context != null && !context.isBlank()) {
            return context + "\n" + content;
        }
        return content;
    }
}
