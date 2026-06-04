package com.ai.aiagent.modules.rag.ingest;

import com.ai.aiagent.modules.rag.llm.InternalLlm;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Contextual Retrieval (kỹ thuật của Anthropic).
 *
 * Vấn đề: một chunk nhỏ tách khỏi tài liệu thường mất ngữ cảnh (vd "Mức tăng là 3%"
 * – tăng cái gì? của ai? kỳ nào?). Khi embed sẽ khó khớp đúng câu hỏi.
 *
 * Giải pháp: nhờ LLM viết 1-2 câu NGỮ CẢNH định vị chunk này trong tài liệu, rồi
 * ghép vào trước chunk khi embed và index full-text.
 *
 * Vì mỗi chunk = 1 lời gọi LLM, ở quy mô lớn rất chậm nếu gọi tuần tự, nên ở đây
 * gọi SONG SONG với mức đồng thời giới hạn (tránh quá tải / rate-limit).
 */
@Component
@Slf4j
public class ContextualEnricher {

    private final InternalLlm internalLlm;

    @Value("${rag.ingestion.contextual-enabled}")
    private boolean enabled;
    @Value("${rag.ingestion.contextual-concurrency}")
    private int concurrency;

    private ExecutorService executor;

    public ContextualEnricher(InternalLlm internalLlm) {
        this.internalLlm = internalLlm;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newFixedThreadPool(Math.max(1, concurrency));
        }
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    /**
     * Sinh context cho NHIỀU chunk cùng lúc (song song).
     *
     * @param fileName    tên file
     * @param parentTexts đoạn cha tương ứng từng child (cùng độ dài với contents)
     * @param contents    nội dung các child chunk
     * @return danh sách câu ngữ cảnh, cùng thứ tự với contents (rỗng nếu tắt/lỗi)
     */
    public List<String> buildContexts(String fileName, List<String> parentTexts, List<String> contents) {
        int n = contents.size();
        List<String> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) results.add("");

        if (!enabled || n == 0) {
            return results;
        }

        List<Callable<String>> tasks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final String parent = parentTexts.get(i);
            final String content = contents.get(i);
            tasks.add(() -> buildOne(fileName, parent, content));
        }

        try {
            List<Future<String>> futures = executor().invokeAll(tasks);
            for (int i = 0; i < n; i++) {
                try {
                    results.set(i, futures.get(i).get());
                } catch (Exception e) {
                    results.set(i, ""); // lỗi 1 chunk thì bỏ context chunk đó, không chặn cả file
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Sinh context bị gián đoạn -> bỏ qua context cho file {}.", fileName);
        }
        return results;
    }

    private String buildOne(String fileName, String parentContent, String chunk) {
        try {
            String prompt = """
                    Đây là một phần của tài liệu "%s":
                    <tai_lieu>
                    %s
                    </tai_lieu>

                    Đây là một đoạn nhỏ trích từ phần trên:
                    <doan>
                    %s
                    </doan>

                    Hãy viết 1-2 câu NGẮN GỌN bằng tiếng Việt nêu ngữ cảnh của đoạn nhỏ này trong
                    tài liệu (nó nói về chủ đề/mục nào, thuộc về ai/kỳ nào nếu có), để khi tách
                    riêng vẫn hiểu được. Chỉ trả về câu ngữ cảnh, không thêm lời dẫn.
                    """.formatted(fileName, truncate(parentContent, 4000), chunk);

            String context = internalLlm.model().generate(prompt);
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Sinh context cho 1 chunk lỗi ({}) -> bỏ qua.", e.getMessage());
            return "";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
