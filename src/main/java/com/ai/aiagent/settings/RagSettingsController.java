package com.ai.aiagent.settings;

import com.ai.aiagent.llm.LlmClientFactory;
import com.ai.aiagent.llm.LlmProvider;
import com.ai.aiagent.security.CurrentScope;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API cau hinh.
 *
 * GET  duoc phep voi moi nguoi dung da xac thuc (UI can biet model nao dang dung).
 * PUT/POST chi ADMIN - xem {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/rag/settings")
public class RagSettingsController {

    private final RagSettingsService settings;
    private final LlmClientFactory clients;

    public RagSettingsController(RagSettingsService settings, LlmClientFactory clients) {
        this.settings = settings;
        this.clients = clients;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", settings.current().provider().name());
        out.put("model", settings.current().model());
        out.put("settings", settings.snapshot());
        out.put("editableKeys", settings.editableKeys());
        return out;
    }

    /** Doi nhanh provider/model tra loi mac dinh. */
    @PutMapping
    public Map<String, Object> updateModel(@RequestBody ModelRequest request) {
        LlmProvider provider = LlmProvider.fromString(request.provider());
        RagSettingsService.ModelSelection updated =
                settings.updateModel(provider, request.model(), CurrentScope.get().clientId());
        return Map.of(
                "message", "Đã cập nhật model mặc định.",
                "provider", updated.provider().name(),
                "model", updated.model());
    }

    public record ModelRequest(String provider, String model) {
    }

    /**
     * Doi nhieu tham so mot luc, ap NGAY khong can restart.
     *
     * Vi du: {@code {"retrieval.topK": 8, "retrieval.minRerankScore": 0.4}}
     */
    @PostMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("Khong co tham so nao duoc gui len.");
        }
        var changed = settings.update(changes, CurrentScope.get().clientId());
        return Map.of(
                "message", "Đã cập nhật " + changed.size() + " tham số, áp dụng ngay.",
                "changed", changed,
                "settings", settings.snapshot());
    }

    @DeleteMapping
    public Map<String, Object> reset() {
        settings.resetToFileDefaults();
        return Map.of("message", "Đã xoá cấu hình lưu trong DB. "
                + "Khởi động lại để trở về giá trị trong application.properties.");
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of("providers", clients.catalog());
    }
}
