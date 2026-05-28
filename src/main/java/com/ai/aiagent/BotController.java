package com.ai.aiagent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BotController {

    // Khởi tạo kết nối thẳng tới Ollama đang chạy trên máy anh
    private final ChatLanguageModel chatModel = OllamaChatModel.builder()
            .baseUrl("http://localhost:11434") // Cổng mặc định của Ollama
            .modelName("qwen2.5-coder:7b")  // Thay bằng 7b nếu lúc nãy anh kéo bản 7b
            .temperature(0.0)                  // Set 0.0 để AI trả lời logic code/tài liệu chính xác nhất
            .build();

    // Tạo một API API đơn giản để test chat
    @GetMapping("/api/test-chat")
    public String testChat(@RequestParam String question) {
        System.out.println("Đang gửi câu hỏi tới Ollama: " + question);

        // Gọi AI và lấy câu trả lời
        String response = chatModel.generate(question);

        return response;
    }
}