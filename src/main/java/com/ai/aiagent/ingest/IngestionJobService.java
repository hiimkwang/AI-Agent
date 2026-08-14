package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.security.PathAllowlist;
import com.ai.aiagent.store.AnswerCacheRepository;
import com.ai.aiagent.store.JobRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Nap tai lieu HANG LOAT, chay nen, tien do luu trong DB.
 *
 * Khac ban cu:
 *   - trang thai job nam trong bang {@code rag_ingest_jobs} nen khong mat khi restart
 *   - co the YEU CAU DUNG job dang chay
 *   - nap tu thu muc may chu phai qua {@link PathAllowlist} (truoc day nhan duong dan
 *     tuy y - ai goi duoc API cung bat server doc file bat ky roi doc lai qua /chat)
 *   - de quy vao thu muc con
 */
@Service
@Slf4j
public class IngestionJobService {

    /** Mot don vi cong viec: noi dung + ten file (khong giu handle file mo). */
    public record WorkItem(String fileName, Path path, byte[] content, boolean deleteAfter) {
    }

    private final IngestionService ingestion;
    private final JobRepository jobs;
    private final AnswerCacheRepository cache;
    private final PathAllowlist allowlist;
    private final ExecutorService executor;

    public IngestionJobService(IngestionService ingestion,
                               JobRepository jobs,
                               AnswerCacheRepository cache,
                               PathAllowlist allowlist,
                               RagProperties props) {
        this.ingestion = ingestion;
        this.jobs = jobs;
        this.cache = cache;
        this.allowlist = allowlist;
        int threads = Math.max(1, props.getIngestion().getJobConcurrency());
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ingest-job");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** Nap toan bo file hop le trong mot thu muc TRONG DANH SACH CHO PHEP. */
    public String submitFolder(String folderPath, boolean recursive,
                               IngestionService.IngestOptions options) {
        Path root = allowlist.requireAllowedDirectory(folderPath);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = recursive ? Files.walk(root, 12) : Files.list(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> DocumentFormat.isSupported(p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalArgumentException("Khong doc duoc thu muc: " + e.getMessage());
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Khong tim thay file nao duoc ho tro trong: " + folderPath
                    + ". Cac duoi file ho tro: " + DocumentFormat.allExtensions());
        }
        List<WorkItem> items = files.stream()
                .map(p -> new WorkItem(p.getFileName().toString(), p, null, false))
                .toList();
        return submit(items, "FOLDER", options);
    }

    /** Nap danh sach file da doc san vao bo nho (thuong la file vua upload). */
    public String submitUploads(List<WorkItem> uploads, IngestionService.IngestOptions options) {
        return submit(uploads, "UPLOAD", options);
    }

    /** Tao WorkItem tu noi dung da doc - dung boi controller khi nhan multipart. */
    public static WorkItem upload(String fileName, byte[] content) {
        return new WorkItem(fileName, null, content, false);
    }

    private String submit(List<WorkItem> items, String kind, IngestionService.IngestOptions options) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Khong co file nao de nap.");
        }
        String jobId = UUID.randomUUID().toString();
        jobs.create(jobId, kind, options.category(), items.size(), options.createdBy());
        executor.submit(() -> run(jobId, items, options));
        log.info("Tao job nap lieu {} [{}]: {} file (category={}).",
                jobId, kind, items.size(), options.category());
        return jobId;
    }

    private void run(String jobId, List<WorkItem> items, IngestionService.IngestOptions options) {
        int processed = 0;
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        int totalChunks = 0;
        boolean cancelled = false;

        for (WorkItem item : items) {
            if (jobs.isCancelRequested(jobId)) {
                cancelled = true;
                log.info("Job {} bi yeu cau dung sau {}/{} file.", jobId, processed, items.size());
                break;
            }
            jobs.progress(jobId, item.fileName(), processed, succeeded, failed, skipped, totalChunks);
            try {
                byte[] content = item.content() != null
                        ? item.content() : Files.readAllBytes(item.path());

                IngestionService.IngestOptions perFile = item.path() == null ? options
                        : IngestionService.IngestOptions.builder()
                        .category(options.category())
                        .department(options.department())
                        .sourcePath(item.path().toString())
                        .allowedRoles(options.allowedRoles())
                        .effectiveDate(options.effectiveDate())
                        .expiresDate(options.expiresDate())
                        .status(options.status())
                        .createdBy(options.createdBy())
                        .force(options.force())
                        .build();

                IngestionService.IngestResult result =
                        ingestion.ingest(content, item.fileName(), perFile);

                switch (result.outcome()) {
                    case INGESTED -> {
                        succeeded++;
                        totalChunks += result.chunkCount();
                    }
                    case SKIPPED_UNCHANGED -> skipped++;
                    case EMPTY -> {
                        failed++;
                        jobs.addError(jobId, item.fileName() + ": khong co noi dung sau khi chuyen doi");
                    }
                }
                for (String warning : result.warnings()) {
                    jobs.addError(jobId, item.fileName() + " (canh bao): " + warning);
                }
            } catch (Exception e) {
                failed++;
                // Loi mot file KHONG duoc lam dung ca job
                jobs.addError(jobId, item.fileName() + ": " + e.getMessage());
                log.warn("Job {} - loi nap file {}: {}", jobId, item.fileName(), e.getMessage());
            } finally {
                processed++;
            }
        }

        jobs.progress(jobId, null, processed, succeeded, failed, skipped, totalChunks);
        jobs.finish(jobId, cancelled ? "CANCELLED" : "DONE");

        if (succeeded > 0) {
            // Tai lieu doi => cau tra loi cache co the da sai
            int purged = cache.invalidateAll();
            log.info("Job {} xong: thanh cong {}, bo qua {}, loi {}, tong {} chunk. "
                            + "Da xoa {} ban ghi cache.",
                    jobId, succeeded, skipped, failed, totalChunks, purged);
        } else {
            log.info("Job {} xong: thanh cong {}, bo qua {}, loi {}.",
                    jobId, succeeded, skipped, failed);
        }
    }
}
