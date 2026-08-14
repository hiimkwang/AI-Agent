package com.ai.aiagent.admin;

import com.ai.aiagent.chat.AnswerCacheService;
import com.ai.aiagent.common.NotFoundException;
import com.ai.aiagent.store.ChunkRepository;
import com.ai.aiagent.store.DocumentRepository;
import com.ai.aiagent.store.StoreModels.DocumentMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Quan ly tai lieu da nap (chi ADMIN). */
@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class DocumentController {

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final AnswerCacheService cache;

    public DocumentController(DocumentRepository documents, ChunkRepository chunks,
                              AnswerCacheService cache) {
        this.documents = documents;
        this.chunks = chunks;
        this.cache = cache;
    }

    @GetMapping("/documents")
    public Map<String, Object> list(@RequestParam(required = false) String category,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        List<DocumentMeta> found = documents.list(category, search, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", documents.countAll());
        out.put("totalChunks", chunks.count());
        out.put("limit", limit);
        out.put("offset", offset);
        out.put("documents", found.stream().map(this::toMap).toList());
        return out;
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        DocumentMeta meta = documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Khong tim thay tai lieu id=" + id));
        Map<String, Object> out = toMap(meta);
        out.put("actualChunks", chunks.countByDocument(id));
        return out;
    }

    /**
     * Xem ban Markdown da chuyen doi cua tai lieu.
     *
     * Nho luu lai ban Markdown, ban kiem tra duoc bo chuyen doi lam dung hay sai ma
     * khong phai convert lai file goc - va co the bam lai chunk truc tiep tu day.
     */
    @GetMapping("/documents/{id}/markdown")
    public Map<String, Object> markdown(@PathVariable long id) {
        DocumentMeta meta = documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Khong tim thay tai lieu id=" + id));
        String markdown = documents.findMarkdown(id).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("fileName", meta.fileName());
        out.put("format", meta.sourceFormat());
        out.put("markdown", markdown);
        out.put("available", markdown != null && !markdown.isBlank());
        if (markdown == null) {
            out.put("note", "Ban Markdown khong duoc luu (rag.convert.store-markdown=false) "
                    + "hoac tai lieu duoc nap truoc khi co tinh nang nay.");
        }
        return out;
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        DocumentMeta meta = documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Khong tim thay tai lieu id=" + id));
        // rag_chunks co ON DELETE CASCADE
        documents.deleteById(id);
        int purged = cache.clear();
        log.info("Da xoa tai lieu {} ({}), xoa {} ban ghi cache.", id, meta.fileName(), purged);
        return Map.of("message", "Đã xoá tài liệu '" + meta.fileName() + "' và toàn bộ đoạn của nó.",
                "deletedDocumentId", id);
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return Map.of("categories", documents.distinctCategories(),
                "chunkCategories", chunks.distinctCategories());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>(documents.stats());
        out.put("chunksInIndex", chunks.count());
        out.put("embeddingDimensions", chunks.actualEmbeddingDimensions());
        return out;
    }

    private Map<String, Object> toMap(DocumentMeta m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("docKey", m.docKey());
        out.put("fileName", m.fileName());
        out.put("title", m.title());
        out.put("category", m.category());
        out.put("department", m.department());
        out.put("docNumber", m.docNumber());
        out.put("version", m.docVersion());
        out.put("sourceFormat", m.sourceFormat());
        out.put("sourcePath", m.sourcePath());
        out.put("effectiveDate", m.effectiveDate());
        out.put("expiresDate", m.expiresDate());
        out.put("status", m.status());
        out.put("allowedRoles", m.allowedRoles());
        out.put("chunkCount", m.chunkCount());
        out.put("charCount", m.charCount());
        out.put("createdBy", m.createdBy());
        out.put("updatedAt", m.updatedAt());
        return out;
    }
}
