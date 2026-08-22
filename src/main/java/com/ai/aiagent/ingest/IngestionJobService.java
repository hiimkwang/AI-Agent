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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

@Service
@Slf4j
public class IngestionJobService {

    /** @param category per-file override; null means "use the category of the job". */
    public record WorkItem(String fileName, Path path, byte[] content, boolean deleteAfter,
                           String category) {
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

    /** One folder found by a scan, with the category it would be ingested under. */
    public record FolderGroup(String folder, String suggestedCategory, int fileCount,
                              List<String> sampleFiles) {
    }

    /**
     * @param unsupported files present but not ingestable - reported so a missing document is
     *                    never a surprise later
     */
    public record FolderScan(String root, int fileCount, List<FolderGroup> groups,
                             List<String> unsupported) {
    }

    /**
     * Look at a folder without ingesting anything, so the operator can see what would happen and
     * fix the categories before committing. Nothing here writes to the database.
     */
    public FolderScan scanFolder(String folderPath, boolean recursive) {
        Path root = allowlist.requireAllowedDirectory(folderPath);
        List<Path> supported = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        try (Stream<Path> walk = recursive ? Files.walk(root, 12) : Files.list(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                if (DocumentFormat.isSupported(p.getFileName().toString())) {
                    supported.add(p);
                } else if (unsupported.size() < 50) {
                    unsupported.add(root.relativize(p).toString());
                }
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("Khong doc duoc thu muc: " + e.getMessage());
        }

        Map<String, List<Path>> byFolder = new LinkedHashMap<>();
        for (Path p : supported) {
            byFolder.computeIfAbsent(relativeFolder(root, p), k -> new ArrayList<>()).add(p);
        }

        List<FolderGroup> groups = new ArrayList<>();
        byFolder.forEach((folder, paths) -> groups.add(new FolderGroup(
                folder,
                CategorySlugs.fromPath(root, paths.get(0)),
                paths.size(),
                paths.stream().limit(5).map(p -> p.getFileName().toString()).toList())));
        groups.sort(Comparator.comparing(FolderGroup::folder));

        return new FolderScan(root.toString(), supported.size(), groups, unsupported);
    }

    /**
     * @param categoryFromFolder derive the category of each file from the folders between the root
     *                           and the file, instead of using one category for the whole job.
     *                           Ignored when {@code options.category()} is set - an explicit
     *                           choice always wins over a derived one.
     * @param categoryByFolder   per-folder override keyed by the folder path relative to the root,
     *                           exactly as {@link #scanFolder} reports it. Wins over everything
     *                           else: this is what the operator confirmed on screen.
     */
    public String submitFolder(String folderPath, boolean recursive,
                               IngestionService.IngestOptions options,
                               boolean categoryFromFolder,
                               Map<String, String> categoryByFolder) {
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
        boolean derive = categoryFromFolder
                && (options.category() == null || options.category().isBlank());
        Map<String, String> overrides = categoryByFolder == null ? Map.of() : categoryByFolder;
        List<WorkItem> items = files.stream()
                .map(p -> new WorkItem(p.getFileName().toString(), p, null, false,
                        categoryFor(root, p, overrides, derive)))
                .toList();
        return submit(items, "FOLDER", options);
    }

    /**
     * Precedence: what the operator confirmed per folder, then the derived slug, then nothing
     * (which leaves the job-wide category to apply, and fails loudly if there is none either).
     */
    static String categoryFor(Path root, Path file, Map<String, String> overrides, boolean derive) {
        String chosen = overrides.get(relativeFolder(root, file));
        if (chosen != null && !chosen.isBlank()) return chosen.strip().toLowerCase(Locale.ROOT);
        return derive ? CategorySlugs.fromPath(root, file) : null;
    }

    /**
     * Always '/'-separated: this string is the key the scan hands to the browser and gets back on
     * confirm, and {@code Path.toString()} would emit backslashes on Windows - the override would
     * then silently miss and every file fall back to the derived category.
     */
    static String relativeFolder(Path root, Path file) {
        Path parent = file.getParent();
        if (parent == null) return "";
        Path relative = root.relativize(parent);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (out.length() > 0) out.append('/');
            out.append(relative.getName(i));
        }
        return out.toString();
    }

    public String submitUploads(List<WorkItem> uploads, IngestionService.IngestOptions options) {
        return submit(uploads, "UPLOAD", options);
    }

    public static WorkItem upload(String fileName, byte[] content) {
        return new WorkItem(fileName, null, content, false, null);
    }

    private String submit(List<WorkItem> items, String kind, IngestionService.IngestOptions options) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Khong co file nao de nap.");
        }
        String jobId = UUID.randomUUID().toString();
        jobs.create(jobId, kind, options.category(), items.size(), options.createdBy());
        executor.submit(() -> run(jobId, items, options));
        log.info("Ingestion job {} created [{}]: {} file(s), category={}.",
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
                log.info("Ingestion job {} cancelled after {}/{} file(s).", jobId, processed, items.size());
                break;
            }
            jobs.progress(jobId, item.fileName(), processed, succeeded, failed, skipped, totalChunks);
            try {
                byte[] content = item.content() != null
                        ? item.content() : Files.readAllBytes(item.path());

                IngestionService.IngestOptions perFile = item.path() == null ? options
                        : IngestionService.IngestOptions.builder()
                        .category(item.category() != null ? item.category() : options.category())
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
                jobs.addError(jobId, item.fileName() + ": " + e.getMessage());
                log.warn("Ingestion job {}: file {} failed: {}", jobId, item.fileName(), e.getMessage());
            } finally {
                processed++;
            }
        }

        jobs.progress(jobId, null, processed, succeeded, failed, skipped, totalChunks);
        jobs.finish(jobId, cancelled ? "CANCELLED" : "DONE");

        if (succeeded > 0) {
            int purged = cache.invalidateAll();
            log.info("Ingestion job {} finished: {} succeeded, {} skipped, {} failed, {} chunks "
                            + "total, {} cache entries purged.",
                    jobId, succeeded, skipped, failed, totalChunks, purged);
        } else {
            log.info("Ingestion job {} finished: {} succeeded, {} skipped, {} failed.",
                    jobId, succeeded, skipped, failed);
        }
    }
}
