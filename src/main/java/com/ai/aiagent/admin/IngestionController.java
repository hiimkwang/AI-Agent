package com.ai.aiagent.admin;

import com.ai.aiagent.common.NotFoundException;
import com.ai.aiagent.ingest.DocumentConverterService;
import com.ai.aiagent.ingest.DocumentFormat;
import com.ai.aiagent.ingest.IngestionJobService;
import com.ai.aiagent.ingest.IngestionService;
import com.ai.aiagent.ingest.MarkdownChunker;
import com.ai.aiagent.observability.RagMetrics;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.security.PathAllowlist;
import com.ai.aiagent.store.JobRepository;
import com.ai.aiagent.store.StoreModels.JobStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class IngestionController {

    private final IngestionService ingestion;
    private final IngestionJobService jobService;
    private final JobRepository jobs;
    private final DocumentConverterService converter;
    private final MarkdownChunker chunker;
    private final PathAllowlist allowlist;
    private final RagMetrics metrics;
    private final PlatformService platform;

    public IngestionController(IngestionService ingestion, IngestionJobService jobService,
                               JobRepository jobs, DocumentConverterService converter,
                               MarkdownChunker chunker, PathAllowlist allowlist,
                               RagMetrics metrics, PlatformService platform) {
        this.platform = platform;
        this.ingestion = ingestion;
        this.jobService = jobService;
        this.jobs = jobs;
        this.converter = converter;
        this.chunker = chunker;
        this.allowlist = allowlist;
        this.metrics = metrics;
    }

    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> convertPreview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "includeChunks", defaultValue = "true") boolean includeChunks,
            @RequestParam(value = "maxChunks", defaultValue = "20") int maxChunks) throws IOException {

        String fileName = PathAllowlist.sanitizeFileName(file.getOriginalFilename());
        DocumentConverterService.Result result = converter.convert(file.getBytes(), fileName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", fileName);
        out.put("format", result.format().name());
        out.put("markdownChars", result.markdown().length());
        out.put("markdown", result.markdown());
        out.put("warnings", result.warnings());

        if (includeChunks) {
            List<MarkdownChunker.Chunk> chunks = chunker.chunk(result.markdown());
            out.put("chunkCount", chunks.size());
            List<Map<String, Object>> preview = new ArrayList<>();
            for (MarkdownChunker.Chunk c : chunks.stream().limit(Math.max(1, maxChunks)).toList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", c.index());
                m.put("headingPath", c.headingPath());
                m.put("chars", c.content().length());
                m.put("content", c.content());
                m.put("parentChars", c.parentContent() == null ? 0 : c.parentContent().length());
                preview.add(m);
            }
            out.put("chunks", preview);
        }
        return out;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "category", required = false) String category,
                                      @RequestParam(value = "department", required = false) String department,
                                      @RequestParam(value = "roles", required = false) String roles,
                                      @RequestParam(value = "effectiveDate", required = false) String effectiveDate,
                                      @RequestParam(value = "expiresDate", required = false) String expiresDate,
                                      @RequestParam(value = "force", defaultValue = "false") boolean force)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File upload bi trong.");
        }
        String fileName = PathAllowlist.sanitizeFileName(file.getOriginalFilename());
        IngestionService.IngestResult result = ingestion.ingest(file.getBytes(), fileName,
                options(category, department, roles, effectiveDate, expiresDate, force));

        if (result.outcome() == IngestionService.Outcome.INGESTED) {
            metrics.recordIngest(result.chunkCount());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", result.outcome().name());
        out.put("documentId", result.documentId() > 0 ? result.documentId() : null);
        out.put("docKey", result.docKey());
        out.put("fileName", result.fileName());
        out.put("format", result.format() == null ? null : result.format().name());
        out.put("chunkCount", result.chunkCount());
        out.put("markdownChars", result.markdownChars());
        out.put("warnings", result.warnings());
        out.put("message", switch (result.outcome()) {
            case INGESTED -> "Đã nạp " + result.chunkCount() + " đoạn từ '" + fileName + "'.";
            case SKIPPED_UNCHANGED -> "Bỏ qua '" + fileName + "': nội dung không đổi so với lần nạp trước.";
            case EMPTY -> "Không nạp được '" + fileName + "': không có nội dung sau khi chuyển đổi.";
        });
        return out;
    }

    @PostMapping(value = "/upload-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "roles", required = false) String roles,
            @RequestParam(value = "effectiveDate", required = false) String effectiveDate,
            @RequestParam(value = "expiresDate", required = false) String expiresDate,
            @RequestParam(value = "force", defaultValue = "false") boolean force) throws IOException {

        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Khong co file nao duoc gui len.");
        }
        List<IngestionJobService.WorkItem> items = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String fileName = PathAllowlist.sanitizeFileName(file.getOriginalFilename());
            if (!DocumentFormat.isSupported(fileName)) {
                rejected.add(fileName);
                continue;
            }
            items.add(IngestionJobService.upload(fileName, file.getBytes()));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Khong co file nao duoc ho tro. Duoi file ho tro: "
                    + DocumentFormat.allExtensions());
        }

        String jobId = jobService.submitUploads(items,
                options(category, department, roles, effectiveDate, expiresDate, force));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("accepted", items.size());
        out.put("rejected", rejected);
        out.put("statusUrl", "/api/v1/rag/admin/jobs/" + jobId);
        out.put("message", "Đã nhận " + items.size() + " file, đang nạp ở chế độ nền.");
        return ResponseEntity.accepted().body(out);
    }

    /**
     * Xem trước: quét thư mục, gom theo thư mục con và đề xuất nhóm tài liệu, KHÔNG nạp gì.
     * Có tồn tại vì bước sai duy nhất ở đây (nhóm tài liệu) chỉ lộ ra rất muộn - tài liệu nạp
     * xong mà người dùng thường không đọc được, còn quản trị viên thì vẫn thấy bình thường.
     */
    @PostMapping("/ingest-folder/scan")
    public Map<String, Object> scanFolder(@RequestBody FolderRequest request) {
        IngestionJobService.FolderScan scan = jobService.scanFolder(
                request.path(), request.recursive() == null || request.recursive());

        Set<String> known = platform.snapshot().collections().stream()
                .filter(CollectionDef::isActive)
                .map(CollectionDef::slug)
                .collect(Collectors.toSet());

        List<Map<String, Object>> groups = new ArrayList<>();
        for (IngestionJobService.FolderGroup g : scan.groups()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("folder", g.folder());
            row.put("suggestedCategory", g.suggestedCategory());
            row.put("fileCount", g.fileCount());
            row.put("sampleFiles", g.sampleFiles());
            row.put("collectionExists", g.suggestedCategory() != null
                    && known.contains(g.suggestedCategory()));
            groups.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", scan.root());
        out.put("fileCount", scan.fileCount());
        out.put("groups", groups);
        out.put("unsupported", scan.unsupported());
        out.put("knownCategories", known.stream().sorted().toList());
        long missing = groups.stream().filter(g -> !(Boolean) g.get("collectionExists")).count();
        out.put("message", missing == 0
                ? "Quét xong " + scan.fileCount() + " file. Mọi nhóm tài liệu đều đã tồn tại."
                : "Quét xong " + scan.fileCount() + " file. " + missing + " nhóm CHƯA có trong "
                  + "màn quản trị — nạp vào đó thì chỉ quản trị viên đọc được. Hãy tạo nhóm "
                  + "trước, hoặc sửa lại nhóm cho đúng.");
        return out;
    }

    @PostMapping("/ingest-folder")
    public ResponseEntity<Map<String, Object>> ingestFolder(@RequestBody FolderRequest request) {
        String jobId = jobService.submitFolder(request.path(),
                request.recursive() == null || request.recursive(),
                options(request.category(), request.department(), request.roles(),
                        request.effectiveDate(), request.expiresDate(),
                        Boolean.TRUE.equals(request.force())),
                Boolean.TRUE.equals(request.categoryFromFolder()),
                request.categoryByFolder());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("statusUrl", "/api/v1/rag/admin/jobs/" + jobId);
        out.put("message", "Đã khởi tạo job nạp thư mục.");
        return ResponseEntity.accepted().body(out);
    }

    /**
     * @param categoryFromFolder nạp cả cây một lần: mỗi file lấy nhóm tài liệu theo thư mục chứa
     *                           nó (<code>ptpm/Core FDS</code> ⇒ <code>ptpm-core-fds</code>).
     *                           Bỏ qua nếu đã chỉ định <code>category</code>.
     */
    public record FolderRequest(String path, String category, String department, String roles,
                                String effectiveDate, String expiresDate, Boolean recursive,
                                Boolean force, Boolean categoryFromFolder,
                                Map<String, String> categoryByFolder) {
    }

    @GetMapping("/allowed-roots")
    public Map<String, Object> allowedRoots() {
        return Map.of(
                "roots", allowlist.configuredRoots(),
                "configured", allowlist.hasRoots(),
                "supportedExtensions", DocumentFormat.allExtensions());
    }

    @GetMapping("/jobs")
    public Map<String, Object> recentJobs(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("jobs", jobs.recent(limit).stream().map(this::toMap).toList());
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> jobStatus(@PathVariable String jobId) {
        JobStatus status = jobs.find(jobId)
                .orElseThrow(() -> new NotFoundException("Khong tim thay job " + jobId));
        return toMap(status);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public Map<String, Object> cancelJob(@PathVariable String jobId) {
        jobs.find(jobId).orElseThrow(() -> new NotFoundException("Khong tim thay job " + jobId));
        jobs.requestCancel(jobId);
        return Map.of("message", "Đã gửi yêu cầu dừng. Job sẽ dừng sau khi xử lý xong file hiện tại.");
    }

    private Map<String, Object> toMap(JobStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", s.id());
        m.put("state", s.state());
        m.put("kind", s.kind());
        m.put("category", s.category());
        m.put("total", s.total());
        m.put("processed", s.processed());
        m.put("succeeded", s.succeeded());
        m.put("failed", s.failed());
        m.put("skipped", s.skipped());
        m.put("totalChunks", s.totalChunks());
        m.put("currentFile", s.currentFile());
        m.put("percent", s.percent());
        m.put("elapsedMs", s.elapsedMs());
        m.put("cancelRequested", s.cancelRequested());
        m.put("createdBy", s.createdBy());
        m.put("startedAt", s.startedAt());
        m.put("finishedAt", s.finishedAt());
        m.put("errors", s.errors());
        return m;
    }

    private IngestionService.IngestOptions options(String category, String department, String roles,
                                                  String effectiveDate, String expiresDate,
                                                  boolean force) {
        List<String> roleList = roles == null || roles.isBlank() ? List.of()
                : java.util.Arrays.stream(roles.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).toList();

        return IngestionService.IngestOptions.builder()
                .category(category)
                .department(department)
                .allowedRoles(roleList)
                .effectiveDate(parseDate(effectiveDate))
                .expiresDate(parseDate(expiresDate))
                .createdBy(CurrentScope.get().clientId())
                .force(force)
                .build();
    }

    private LocalDate parseDate(String value) {
        return com.ai.aiagent.ingest.FrontMatter.parseDate(value);
    }
}
