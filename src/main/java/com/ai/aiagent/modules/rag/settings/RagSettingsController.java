package com.ai.aiagent.modules.rag.settings;

import com.ai.aiagent.modules.rag.llm.LlmProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API quản lý cấu hình model toàn cục.
 *
 *  GET  /api/v1/rag/settings        -> xem cấu hình hiện tại
 *  PUT  /api/v1/rag/settings        -> đặt provider/model mặc định
 *  GET  /api/v1/rag/settings/models -> liệt kê các provider + model gợi ý
 */
@RestController
@RequestMapping("/api/v1/rag/settings")
public class RagSettingsController {

    private final RagSettingsService settingsService;

    public RagSettingsController(RagSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // DTO nhận dữ liệu JSON từ client. Jackson sẽ tự gán giá trị qua setter.
    public static class SettingsRequest {
        private String provider;
        private String model;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        RagSettingsService.ModelSelection s = settingsService.getCurrent();
        return ResponseEntity.ok(Map.of(
                "provider", s.getProvider().name(),
                "model", s.getModel()
        ));
    }

    @PutMapping
    public ResponseEntity<?> updateSettings(@RequestBody SettingsRequest request) {
        try {
            LlmProvider provider = LlmProvider.fromString(request.getProvider());
            RagSettingsService.ModelSelection updated =
                    settingsService.update(provider, request.getModel());
            return ResponseEntity.ok(Map.of(
                    "message", "Đã cập nhật cấu hình model mặc định.",
                    "provider", updated.getProvider().name(),
                    "model", updated.getModel()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Danh sách provider + một vài model thông dụng để client gợi ý cho người dùng. */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        return ResponseEntity.ok(List.of(
                Map.of("provider", "OPENAI", "models",
                        List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1")),
                Map.of("provider", "GEMINI", "models",
                        List.of("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash")),
                Map.of("provider", "OLLAMA", "models",
                        List.of("qwen2.5:7b", "llama3.1:8b", "qwen2.5:14b", "qwen2.5-coder:7b"))
        ));
    }
}
