package com.ai.aiagent.settings;

import com.ai.aiagent.security.CurrentScope;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Cau hinh API key / model cua tung provider LLM - CHI ADMIN.
 *
 * Nam duoi {@code /api/v1/rag/admin/**}, da bi khoa {@code hasRole("ADMIN")} boi
 * {@code SecurityConfig}, nen khong can khai bao rule rieng.
 */
@RestController
@RequestMapping("/api/v1/rag/admin/providers")
public class ProviderSettingsController {

    private final ProviderSettingsService settings;

    public ProviderSettingsController(ProviderSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> get() {
        return settings.snapshot();
    }

    /**
     * Doi nhieu cau hinh mot luc, vi du:
     * {@code {"openai.apiKey": "sk-...", "anthropic.chatModel": "claude-sonnet-5"}}.
     * Khoa vang mat trong body = giu nguyen.
     */
    @PostMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("Khong co tham so nao duoc gui len.");
        }
        var changed = settings.update(changes, CurrentScope.get().clientId());
        return Map.of(
                "message", changed.isEmpty()
                        ? "Khong co gia tri nao thay doi." : "Đã cập nhật " + changed.size() + " cấu hình.",
                "changed", changed,
                "providers", settings.snapshot());
    }

    @DeleteMapping("/{provider}/api-key")
    public Map<String, Object> clearKey(@PathVariable String provider) {
        settings.clearKey(provider);
        return Map.of("message", "Đã xoá API key của " + provider + ".",
                "providers", settings.snapshot());
    }

    /**
     * Xac thuc key/baseUrl bang cach goi that API cua nha cung cap va lay danh sach
     * model - thanh cong thi luu lai luon, that bai thi khong dung gi ca.
     */
    @PostMapping("/{provider}/connect")
    public Map<String, Object> connect(@PathVariable String provider, @RequestBody ConnectRequest request) {
        var models = settings.connect(provider,
                request == null ? null : request.apiKey(),
                request == null ? null : request.baseUrl(),
                CurrentScope.get().clientId());
        return Map.of(
                "message", "Kết nối thành công, tìm thấy " + models.size() + " model.",
                "models", models,
                "providers", settings.snapshot());
    }

    public record ConnectRequest(String apiKey, String baseUrl) {
    }
}
