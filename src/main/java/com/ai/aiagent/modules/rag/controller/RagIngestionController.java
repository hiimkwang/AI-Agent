package com.ai.aiagent.modules.rag.controller;
import com.ai.aiagent.modules.rag.service.RagIngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/rag/admin")
public class RagIngestionController {

    private final RagIngestionService ingestionService;

    public RagIngestionController(RagIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    // Endpoint mới chuyên dùng để nạp file (Word, Excel, PDF, TXT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File upload bị trống.");
        }

        try {
            // 1. Lưu file tạm xuống ổ cứng (có thể lưu vào thư mục temp trên ổ 120GB)
            String tempDirPath = System.getProperty("java.io.tmpdir");
            File tempFile = new File(tempDirPath + "/" + file.getOriginalFilename());
            file.transferTo(tempFile);

            // 2. Gọi Service để xử lý bóc tách và băm Vector
            ingestionService.processOfficeFile(tempFile);

            // 3. Xóa file tạm sau khi nạp xong
            tempFile.delete();

            return ResponseEntity.ok("Đã nạp thành công file tài liệu: " + file.getOriginalFilename());

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Lỗi đọc file: " + e.getMessage());
        }
    }
}