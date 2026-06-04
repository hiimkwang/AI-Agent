package com.ai.aiagent.modules.rag.controller;

import com.ai.aiagent.modules.rag.service.RagIngestionJobService;
import com.ai.aiagent.modules.rag.service.RagIngestionService;
import com.ai.aiagent.modules.rag.store.RagVectorRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag/admin")
public class RagIngestionController {

    private final RagIngestionService ingestionService;
    private final RagIngestionJobService jobService;
    private final RagVectorRepository repository;

    public RagIngestionController(RagIngestionService ingestionService,
                                  RagIngestionJobService jobService,
                                  RagVectorRepository repository) {
        this.ingestionService = ingestionService;
        this.jobService = jobService;
        this.repository = repository;
    }

    /** Nạp 1 file (đồng bộ). Upload lại cùng tên file sẽ GHI ĐÈ (chống trùng). */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadDocument(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "category", required = false) String category) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File upload bị trống.");
        }
        File tempFile = null;
        try {
            tempFile = saveTemp(file);
            int chunks = ingestionService.processOfficeFile(tempFile, category);
            return ResponseEntity.ok("Đã nạp thành công file '" + file.getOriginalFilename()
                    + "' với " + chunks + " đoạn (chunk).");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Lỗi đọc file: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body("Lỗi khi nạp tài liệu: " + e.getMessage());
        } finally {
            deleteTemp(tempFile);
        }
    }

    /** Nạp NHIỀU file cùng lúc (chạy nền). Trả về jobId để theo dõi tiến độ. */
    @PostMapping(value = "/upload-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadBatch(@RequestParam("files") MultipartFile[] files,
                                         @RequestParam(value = "category", required = false) String category) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Không có file nào."));
        }
        try {
            List<File> tempFiles = new ArrayList<>();
            for (MultipartFile f : files) {
                if (!f.isEmpty()) tempFiles.add(saveTemp(f));
            }
            String jobId = jobService.submitFiles(tempFiles, category, true);
            return ResponseEntity.accepted().body(Map.of(
                    "message", "Đã nhận " + tempFiles.size() + " file, đang nạp nền.",
                    "jobId", jobId,
                    "statusUrl", "/api/v1/rag/admin/jobs/" + jobId
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi lưu file: " + e.getMessage()));
        }
    }

    /** Nạp toàn bộ file hợp lệ trong 1 THƯ MỤC trên máy chủ (chạy nền). */
    public record FolderRequest(String path, String category) {}

    @PostMapping("/ingest-folder")
    public ResponseEntity<?> ingestFolder(@RequestBody FolderRequest request) {
        try {
            String jobId = jobService.submitFolder(request.path(), request.category());
            return ResponseEntity.accepted().body(Map.of(
                    "message", "Đã khởi tạo nạp thư mục.",
                    "jobId", jobId,
                    "statusUrl", "/api/v1/rag/admin/jobs/" + jobId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Xem tiến độ một job nạp liệu. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> jobStatus(@PathVariable String jobId) {
        Map<String, Object> status = jobService.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /** Liệt kê tài liệu đã nạp + số chunk + tổng số vector. */
    @GetMapping("/documents")
    public ResponseEntity<?> listDocuments() {
        return ResponseEntity.ok(Map.of(
                "totalChunks", repository.count(),
                "documents", repository.listDocuments()
        ));
    }

    /** Xóa toàn bộ chunk của một tài liệu theo tên file. */
    @DeleteMapping("/documents/{fileName}")
    public ResponseEntity<?> deleteDocument(@PathVariable String fileName) {
        int deleted = repository.deleteByDocId(fileName);
        return ResponseEntity.ok(Map.of(
                "message", "Đã xóa tài liệu '" + fileName + "'.",
                "deletedChunks", deleted
        ));
    }

    // ---- Helper ----

    /** Lưu file tạm vào một thư mục tạm RIÊNG, giữ nguyên tên gốc (để docId/file_name đúng). */
    private File saveTemp(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) name = "upload.bin";
        File dir = Files.createTempDirectory("rag_").toFile();
        File tempFile = new File(dir, name);
        file.transferTo(tempFile);
        return tempFile;
    }

    /** Xóa file tạm và thư mục tạm riêng của nó. */
    private void deleteTemp(File tempFile) {
        if (tempFile == null) return;
        File dir = tempFile.getParentFile();
        if (tempFile.exists()) tempFile.delete();
        if (dir != null && dir.getName().startsWith("rag_")) dir.delete();
    }
}
