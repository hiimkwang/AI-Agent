package com.ai.aiagent.modules.rag.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nạp tài liệu HÀNG LOẠT, chạy NỀN (bất đồng bộ), có theo dõi tiến độ.
 *
 * Dùng cho quy mô lớn (hàng trăm file): client gọi 1 lần để khởi tạo job và nhận về jobId,
 * sau đó hỏi tiến độ qua jobId thay vì phải đợi treo request hàng giờ.
 *
 * Lưu ý: trạng thái job lưu trong RAM, mất khi restart. Đủ cho 1 server.
 */
@Service
@Slf4j
public class RagIngestionJobService {

    private static final List<String> SUPPORTED_EXT =
            Arrays.asList(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt");

    private final RagIngestionService ingestionService;

    /** 2 job chạy song song; mỗi job xử lý các file của nó tuần tự. */
    private final ExecutorService jobExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    public RagIngestionJobService(RagIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PreDestroy
    public void shutdown() {
        jobExecutor.shutdownNow();
    }

    /** Nạp tất cả file hợp lệ trong một THƯ MỤC trên máy chủ. */
    public String submitFolder(String folderPath, String category) {
        File dir = new File(folderPath);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Không phải thư mục hợp lệ: " + folderPath);
        }
        File[] all = dir.listFiles();
        List<File> files = new ArrayList<>();
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && isSupported(f.getName())) files.add(f);
            }
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy file hợp lệ nào trong: " + folderPath);
        }
        return submit(files, category, false);
    }

    /**
     * Nạp một danh sách file (thường là các file tạm vừa upload).
     * @param deleteAfter xóa file sau khi xử lý xong (dùng cho file tạm)
     */
    public String submitFiles(List<File> files, String category, boolean deleteAfter) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Danh sách file trống.");
        }
        return submit(files, category, deleteAfter);
    }

    private String submit(List<File> files, String category, boolean deleteAfter) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, files.size());
        jobs.put(jobId, status);

        jobExecutor.submit(() -> runJob(status, files, category, deleteAfter));
        log.info("Tạo job nạp liệu {}: {} file (category={}).", jobId, files.size(), category);
        return jobId;
    }

    private void runJob(JobStatus status, List<File> files, String category, boolean deleteAfter) {
        for (File file : files) {
            status.setCurrentFile(file.getName());
            try {
                int chunks = ingestionService.processOfficeFile(file, category);
                status.onSuccess(chunks);
            } catch (Exception e) {
                status.onFailure(file.getName() + ": " + e.getMessage());
                log.warn("Job {} – lỗi nạp file {}: {}", status.jobId, file.getName(), e.getMessage());
            } finally {
                if (deleteAfter) {
                    File dir = file.getParentFile();
                    if (file.exists()) file.delete();
                    if (dir != null && dir.getName().startsWith("rag_")) dir.delete();
                }
            }
        }
        status.finish();
        log.info("Job {} HOÀN TẤT: thành công {}, lỗi {}, tổng chunk {}.",
                status.jobId, status.succeeded, status.failed, status.totalChunks);
    }

    public Map<String, Object> getStatus(String jobId) {
        JobStatus s = jobs.get(jobId);
        if (s == null) return null;
        return s.snapshot();
    }

    private boolean isSupported(String name) {
        String lower = name.toLowerCase();
        return SUPPORTED_EXT.stream().anyMatch(lower::endsWith);
    }

    /** Trạng thái một job (cập nhật bởi 1 luồng worker, đọc bởi luồng khác). */
    static class JobStatus {
        final String jobId;
        final int total;
        volatile String state = "RUNNING"; // RUNNING | DONE
        volatile String currentFile = "";
        volatile int processed = 0;
        volatile int succeeded = 0;
        volatile int failed = 0;
        volatile int totalChunks = 0;
        final List<String> errors = new ArrayList<>();
        final long startedAt = System.currentTimeMillis();
        volatile long finishedAt = 0;

        JobStatus(String jobId, int total) {
            this.jobId = jobId;
            this.total = total;
        }

        synchronized void onSuccess(int chunks) {
            processed++;
            succeeded++;
            totalChunks += chunks;
        }

        synchronized void onFailure(String error) {
            processed++;
            failed++;
            if (errors.size() < 100) errors.add(error);
        }

        void setCurrentFile(String f) { this.currentFile = f; }

        void finish() {
            this.state = "DONE";
            this.currentFile = "";
            this.finishedAt = System.currentTimeMillis();
        }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", jobId);
            m.put("state", state);
            m.put("total", total);
            m.put("processed", processed);
            m.put("succeeded", succeeded);
            m.put("failed", failed);
            m.put("totalChunks", totalChunks);
            m.put("currentFile", currentFile);
            m.put("percent", total == 0 ? 100 : Math.round(processed * 100.0 / total));
            m.put("elapsedMs", (finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - startedAt);
            m.put("errors", new ArrayList<>(errors));
            return m;
        }
    }
}
