package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.InternalLlm;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Contextual Retrieval: nho LLM viet 1-2 cau NGU CANH cho tung chunk.
 *
 * Van de: mot chunk nho tach khoi tai lieu thuong mat ngu canh ("Muc tang la 3%" -
 * tang cai gi, cua ai, ky nao?). Khi nhung se kho khop dung cau hoi.
 *
 * Voi cach bam chunk moi theo heading, chunk da mang san duong dan heading nen loi
 * ich cua buoc nay giam bot - vi vay mac dinh vẫn TAT ({@code contextual-enabled=false}).
 * Bat len khi do bang eval thay thuc su co loi, vi moi chunk ton mot loi goi LLM.
 */
@Component
@Slf4j
public class ContextualEnricher {

    private final InternalLlm internalLlm;
    private final RagProperties props;
    private volatile ExecutorService executor;

    public ContextualEnricher(InternalLlm internalLlm, RagProperties props) {
        this.internalLlm = internalLlm;
        this.props = props;
    }

    public boolean isEnabled() {
        return props.getIngestion().isContextualEnabled();
    }

    private ExecutorService executor() {
        ExecutorService local = executor;
        if (local == null) {
            synchronized (this) {
                if (executor == null) {
                    int threads = Math.max(1, props.getIngestion().getContextualConcurrency());
                    executor = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "ctx-enrich");
                        t.setDaemon(true);
                        return t;
                    });
                }
                local = executor;
            }
        }
        return local;
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    /**
     * @return danh sach cau ngu canh, cung thu tu voi {@code chunks}; phan tu rong
     *         khi tat hoac khi rieng chunk do bi loi (khong lam chet ca file)
     */
    public List<String> buildContexts(String fileName, List<MarkdownChunker.Chunk> chunks) {
        int n = chunks.size();
        List<String> results = new ArrayList<>(Collections.nCopies(n, ""));
        if (!isEnabled() || n == 0) {
            return results;
        }

        List<Callable<String>> tasks = new ArrayList<>(n);
        for (MarkdownChunker.Chunk chunk : chunks) {
            tasks.add(() -> buildOne(fileName, chunk));
        }
        try {
            List<Future<String>> futures = executor().invokeAll(tasks);
            for (int i = 0; i < n; i++) {
                try {
                    results.set(i, futures.get(i).get());
                } catch (Exception e) {
                    results.set(i, "");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Sinh context bi ngat -> bo qua context cho file {}.", fileName);
        }
        return results;
    }

    private String buildOne(String fileName, MarkdownChunker.Chunk chunk) {
        try {
            String prompt = """
                    Day la mot phan cua tai lieu "%s", o muc: %s

                    <doan_lon>
                    %s
                    </doan_lon>

                    Day la doan nho trich tu phan tren:
                    <doan_nho>
                    %s
                    </doan_nho>

                    Hay viet 1-2 cau NGAN GON bang tieng Viet neu ngu canh cua doan nho nay
                    trong tai lieu (no noi ve chu de/muc nao, thuoc ve ai/ky nao neu co), de
                    khi tach rieng van hieu duoc. Chi tra ve cau ngu canh, khong them loi dan.
                    """.formatted(
                    fileName,
                    chunk.headingPath() == null || chunk.headingPath().isBlank()
                            ? "(khong ro)" : chunk.headingPath(),
                    truncate(chunk.parentContent(), 4000),
                    chunk.content());

            String context = internalLlm.generate(prompt);
            return context == null ? "" : context.strip();
        } catch (Exception e) {
            log.debug("Sinh context cho 1 chunk loi ({}) -> bo qua.", e.getMessage());
            return "";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
