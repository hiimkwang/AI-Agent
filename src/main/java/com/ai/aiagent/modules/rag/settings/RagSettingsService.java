package com.ai.aiagent.modules.rag.settings;

import com.ai.aiagent.modules.rag.llm.LlmProvider;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lưu trữ cấu hình model mặc định toàn cục (in-memory).
 * Cho phép thay đổi runtime qua Settings API mà không cần restart.
 */
@Service
@Slf4j
public class RagSettingsService {

    @Value("${rag.llm.default-provider}")
    private String configuredProvider;
    @Value("${rag.llm.default-model}")
    private String configuredModel;

    /** Trạng thái hiện tại (provider + model) áp dụng cho mọi request không override. */
    @Getter
    public static class ModelSelection {
        private final LlmProvider provider;
        private final String model;

        public ModelSelection(LlmProvider provider, String model) {
            this.provider = provider;
            this.model = model;
        }
    }

    private final AtomicReference<ModelSelection> current = new AtomicReference<>();

    @PostConstruct
    public void init() {
        LlmProvider provider = LlmProvider.fromString(configuredProvider);
        current.set(new ModelSelection(provider, configuredModel));
        log.info("Cấu hình LLM mặc định khởi tạo: provider={}, model={}", provider, configuredModel);
    }

    public ModelSelection getCurrent() {
        return current.get();
    }

    /** Cập nhật cấu hình mặc định toàn hệ thống. */
    public ModelSelection update(LlmProvider provider, String model) {
        ModelSelection existing = current.get();
        LlmProvider newProvider = provider != null ? provider : existing.getProvider();
        String newModel = (model != null && !model.isBlank()) ? model.trim() : existing.getModel();
        ModelSelection updated = new ModelSelection(newProvider, newModel);
        current.set(updated);
        log.info("Đã cập nhật LLM mặc định: provider={}, model={}", newProvider, newModel);
        return updated;
    }
}
