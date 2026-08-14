package com.ai.aiagent.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Cac model cua tang luu tru. Gom vao mot file cho de doi chieu voi schema. */
public final class StoreModels {

    private StoreModels() {
    }

    /** Metadata muc tai lieu. {@code docKey} la khoa on dinh de ghi de. */
    public record DocumentMeta(
            Long id,
            String docKey,
            String fileName,
            String title,
            String category,
            String department,
            String docNumber,
            String docVersion,
            String sourcePath,
            String sourceFormat,
            LocalDate effectiveDate,
            LocalDate expiresDate,
            String status,
            List<String> allowedRoles,
            String contentSha256,
            int chunkCount,
            int charCount,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
        public boolean isActive() {
            return status == null || "ACTIVE".equalsIgnoreCase(status);
        }
    }

    /** Mot chunk chuan bi ghi vao DB. */
    public record ChunkToInsert(
            Long documentId,
            String docKey,
            String fileName,
            String category,
            int chunkIndex,
            String headingPath,
            String content,
            String context,
            String parentContent,
            String contentSha256,
            float[] embedding
    ) {
    }

    /**
     * Chunk lay ra tu DB kem diem.
     *
     * Dung class (khong phai record) vi diem duoc CAP NHAT nhieu lan trong pipeline:
     * diem goc -> diem RRF -> diem rerank -> diem sau boost do moi.
     */
    public static final class RetrievedChunk {
        private final long id;
        private final Long documentId;
        private final String docKey;
        private final String fileName;
        private final String category;
        private final int chunkIndex;
        private final String headingPath;
        private final String content;
        private final String context;
        private final String parentContent;
        private final String documentTitle;
        private final String docNumber;
        private final String docVersion;
        private final LocalDate effectiveDate;
        private final String status;

        /** Diem goc tu nhanh tim kiem: cosine (0..1) hoac ts_rank_cd. */
        private double rawScore;
        /** Diem sau khi gop RRF. */
        private double fusedScore;
        /** Diem do rerank tra ve (0..1). Day la diem dung de so voi nguong tu choi. */
        private double rerankScore = -1;
        /** Diem cuoi cung sau boost do moi. */
        private double finalScore;
        /** Nhanh nao tim ra chunk nay: VECTOR, FULLTEXT hoac ca hai. */
        private String matchedBy = "";

        public RetrievedChunk(long id, Long documentId, String docKey, String fileName, String category,
                              int chunkIndex, String headingPath, String content, String context,
                              String parentContent, String documentTitle, String docNumber,
                              String docVersion, LocalDate effectiveDate, String status, double rawScore) {
            this.id = id;
            this.documentId = documentId;
            this.docKey = docKey;
            this.fileName = fileName;
            this.category = category;
            this.chunkIndex = chunkIndex;
            this.headingPath = headingPath;
            this.content = content;
            this.context = context;
            this.parentContent = parentContent;
            this.documentTitle = documentTitle;
            this.docNumber = docNumber;
            this.docVersion = docVersion;
            this.effectiveDate = effectiveDate;
            this.status = status;
            this.rawScore = rawScore;
        }

        public long getId() { return id; }
        public Long getDocumentId() { return documentId; }
        public String getDocKey() { return docKey; }
        public String getFileName() { return fileName; }
        public String getCategory() { return category; }
        public int getChunkIndex() { return chunkIndex; }
        public String getHeadingPath() { return headingPath; }
        public String getContent() { return content; }
        public String getContext() { return context; }
        public String getParentContent() { return parentContent; }
        public String getDocumentTitle() { return documentTitle; }
        public String getDocNumber() { return docNumber; }
        public String getDocVersion() { return docVersion; }
        public LocalDate getEffectiveDate() { return effectiveDate; }
        public String getStatus() { return status; }

        public double getRawScore() { return rawScore; }
        public void setRawScore(double v) { this.rawScore = v; }
        public double getFusedScore() { return fusedScore; }
        public void setFusedScore(double v) { this.fusedScore = v; }
        public double getRerankScore() { return rerankScore; }
        public void setRerankScore(double v) { this.rerankScore = v; }
        public double getFinalScore() { return finalScore; }
        public void setFinalScore(double v) { this.finalScore = v; }
        public String getMatchedBy() { return matchedBy; }
        public void setMatchedBy(String v) { this.matchedBy = v; }

        public void addMatchedBy(String branch) {
            if (matchedBy == null || matchedBy.isEmpty()) {
                matchedBy = branch;
            } else if (!matchedBy.contains(branch)) {
                matchedBy = matchedBy + "+" + branch;
            }
        }

        /** Doan lon dung de tra loi; quay ve child neu tai lieu cu khong co parent. */
        public String answerText() {
            return parentContent != null && !parentContent.isBlank() ? parentContent : content;
        }

        /** Van ban dua cho bo rerank doc: co duong dan heading nen bot "mat goc". */
        public String rerankText() {
            StringBuilder sb = new StringBuilder();
            if (headingPath != null && !headingPath.isBlank()) sb.append('[').append(headingPath).append("]\n");
            if (context != null && !context.isBlank()) sb.append(context).append('\n');
            sb.append(content);
            return sb.toString();
        }

        /** Nhan nguon hien cho nguoi dung: uu tien so hieu van ban neu co. */
        public String sourceLabel() {
            StringBuilder sb = new StringBuilder(fileName == null ? "?" : fileName);
            if (docNumber != null && !docNumber.isBlank()) sb.append(" (").append(docNumber).append(')');
            if (docVersion != null && !docVersion.isBlank()) sb.append(" v").append(docVersion);
            return sb.toString();
        }
    }

    /** Mot luot hoi-dap trong lich su hoi thoai. */
    public record Turn(String role, String content) {
    }

    /** Trich dan tra ve cho nguoi dung - du chi tiet de kiem chung. */
    public record Citation(
            long chunkId,
            Long documentId,
            String fileName,
            String headingPath,
            String snippet,
            double score,
            int rank
    ) {
    }

    /** Ban ghi mot cau tra loi da luu, dung de gan feedback. */
    public record StoredMessage(long id, String conversationId) {
    }

    /** Trang thai job nap lieu. */
    public record JobStatus(
            String id,
            String state,
            String kind,
            String category,
            int total,
            int processed,
            int succeeded,
            int failed,
            int skipped,
            int totalChunks,
            String currentFile,
            List<String> errors,
            boolean cancelRequested,
            String createdBy,
            Instant startedAt,
            Instant finishedAt
    ) {
        public int percent() {
            return total == 0 ? 100 : (int) Math.round(processed * 100.0 / total);
        }

        public long elapsedMs() {
            Instant end = finishedAt != null ? finishedAt : Instant.now();
            return end.toEpochMilli() - startedAt.toEpochMilli();
        }
    }
}
