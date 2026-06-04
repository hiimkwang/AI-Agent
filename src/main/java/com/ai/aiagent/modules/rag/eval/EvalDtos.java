package com.ai.aiagent.modules.rag.eval;

import java.util.List;

/**
 * Các DTO cho bộ đánh giá (eval). Gom vào một file cho gọn.
 */
public class EvalDtos {

    /** Một câu test: câu hỏi + (tùy chọn) nguồn mong đợi để đo retrieval. */
    public static class EvalCase {
        private String question;
        private String expectedSource; // tên file mong đợi xuất hiện trong nguồn (tùy chọn)

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getExpectedSource() { return expectedSource; }
        public void setExpectedSource(String expectedSource) { this.expectedSource = expectedSource; }
    }

    /** Yêu cầu chạy eval cho một bộ test. */
    public static class EvalRequest {
        private List<EvalCase> cases;
        private String provider; // model trả lời (tùy chọn)
        private String model;

        public List<EvalCase> getCases() { return cases; }
        public void setCases(List<EvalCase> cases) { this.cases = cases; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    /** Kết quả cho một câu test. */
    public record EvalCaseResult(
            String question,
            String answer,
            List<String> sources,
            double faithfulness,     // 0..1: câu trả lời có bám sát tài liệu không (chống bịa)
            double answerRelevance,  // 0..1: câu trả lời có đúng trọng tâm câu hỏi không
            Boolean sourceHit        // nguồn mong đợi có được truy xuất không (null nếu không khai báo)
    ) {}

    /** Báo cáo tổng hợp. */
    public record EvalReport(
            int total,
            double avgFaithfulness,
            double avgAnswerRelevance,
            Double contextRecall,    // tỉ lệ câu có expectedSource được tìm đúng (null nếu không khai báo case nào)
            List<EvalCaseResult> results
    ) {}
}
