package com.ai.aiagent.modules.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagChatService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatLanguageModel llmBrain;

    public RagChatService(EmbeddingModel ragEmbeddingModel, EmbeddingStore<TextSegment> ragEmbeddingStore) {
        this.embeddingModel = ragEmbeddingModel;
        this.embeddingStore = ragEmbeddingStore;

        // Cấu hình kết nối cục bộ tới mô hình Qwen 7B để tối ưu hiệu năng và VRAM
        this.llmBrain = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen2.5-coder:7b")
                .temperature(0.0) // Giữ độ sáng tạo bằng 0 để đảm bảo tính kỷ luật theo tài liệu gốc
                .numCtx(4096)
                .build();
    }

    public String retrieveAndAnswer(String userQuestion) {
        log.info(">>> NHẬN CÂU HỎI: {}", userQuestion);

        try {
            Embedding questionEmbedding = embeddingModel.embed(userQuestion).content();

            // QUAN TRỌNG: Hạ threshold từ 0.6 xuống 0.4 hoặc 0.3 để xem nó vét được data nào lên không
            double threshold = 0.4;
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(questionEmbedding, 3, threshold);

            log.info("Tìm thấy {} đoạn văn liên quan trong DB (Ngưỡng > {})", matches.size(), threshold);

            if (matches.isEmpty()) {
                log.warn("Cảnh báo: Không tìm thấy bất kỳ chunk nào khớp với câu hỏi.");
                return "Tôi không tìm thấy thông tin này trong tài liệu SharePoint nội bộ.";
            }

            // Log chi tiết từng đoạn văn nó móc lên được để anh kiểm tra xem nó có "ngu" không
            int i = 1;
            for (EmbeddingMatch<TextSegment> match : matches) {
                log.info("MATCH [{}]: Điểm khớp: {} | Nguồn: {} | Nội dung trích xuất: {}",
                        i++, match.score(), match.embedded().metadata().getString("file_name"), match.embedded().text());
            }

            String context = matches.stream()
                    .map(match -> match.embedded().text() + " [Nguồn: " + match.embedded().metadata().getString("file_name") + "]")
                    .collect(Collectors.joining("\n\n"));

            String finalPrompt = "Bạn là trợ lý AI nội bộ... \n\nTÀI LIỆU KHẢO SÁT:\n" + context + "\n\nCÂU HỎI: " + userQuestion;

            log.info("Đang gọi Qwen 7B để tổng hợp câu trả lời...");
            String answer = llmBrain.generate(finalPrompt);
            log.info("<<< TRẢ LỜI: {}", answer);

            return answer;

        } catch (Exception e) {
            log.error("Lỗi khi xử lý câu hỏi: ", e);
            return "Đã xảy ra lỗi hệ thống khi truy vấn tài liệu.";
        }
    }
}