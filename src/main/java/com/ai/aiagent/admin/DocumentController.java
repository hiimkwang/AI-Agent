package com.ai.aiagent.admin;

import com.ai.aiagent.chat.AnswerCacheService;
import com.ai.aiagent.common.NotFoundException;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.store.ChunkRepository;
import com.ai.aiagent.store.DocumentRepository;
import com.ai.aiagent.store.StoreModels.DocumentMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class DocumentController {

    /** Chặn xoá cả kho vì một lời gọi lạc; muốn xoá nhiều hơn thì chia lượt. */
    private static final int MAX_BULK = 500;

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final AnswerCacheService cache;
    private final PlatformService platform;
    private final TransactionTemplate transactions;

    public DocumentController(DocumentRepository documents, ChunkRepository chunks,
                              AnswerCacheService cache, PlatformService platform,
                              TransactionTemplate transactions) {
        this.documents = documents;
        this.chunks = chunks;
        this.cache = cache;
        this.platform = platform;
        this.transactions = transactions;
    }

    @GetMapping("/documents")
    public Map<String, Object> list(@RequestParam(required = false) String category,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        List<DocumentMeta> found = documents.list(category, search, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", documents.countAll());
        // Filter-aware: "total" counts the whole store, so on its own it cannot tell the
        // UI whether a filtered result set has more rows than the page being shown.
        out.put("matching", documents.count(category, search));
        out.put("maxBulkDelete", MAX_BULK);
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

    /**
     * @param ids         xoá đúng những tài liệu này
     * @param allMatching xoá MỌI tài liệu khớp {@code category}/{@code search} — kể cả những
     *                    trang chưa hiển thị. Bắt buộc phải nói rõ, vì "chọn tất cả" trên giao
     *                    diện chỉ tích được những dòng đang thấy.
     */
    public record BulkDeleteRequest(List<Long> ids, String category, String search,
                                    Boolean allMatching) {
    }

    @PostMapping("/documents/bulk-delete")
    public Map<String, Object> bulkDelete(@RequestBody BulkDeleteRequest request) {
        List<Long> targets;
        long remaining = 0;
        if (Boolean.TRUE.equals(request.allMatching())) {
            // Lay id theo dung bo loc cua danh sach, khong gioi han so trang.
            targets = documents.list(request.category(), request.search(), MAX_BULK, 0)
                    .stream().map(DocumentMeta::id).toList();
            // Bi cat o MAX_BULK thi phai NOI RA: bao "da xoa 500" trong khi con 3500 tai
            // lieu nam lai la loi nguy hiem nhat o day - nguoi van hanh tuong da xong.
            remaining = Math.max(0, documents.count(request.category(), request.search())
                    - targets.size());
        } else {
            targets = request.ids() == null ? List.of()
                    : request.ids().stream().filter(Objects::nonNull).distinct().toList();
        }
        if (targets.isEmpty()) {
            return Map.of("deleted", 0, "message", "Không có tài liệu nào được chọn.");
        }
        if (targets.size() > MAX_BULK) {
            throw new IllegalArgumentException("Một lượt chỉ xoá được tối đa " + MAX_BULK
                    + " tài liệu, đang yêu cầu " + targets.size() + ".");
        }

        int deleted = 0;
        List<Long> notFound = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Long id : targets) {
            var meta = documents.findById(id);
            if (meta.isEmpty()) {
                notFound.add(id);
                continue;
            }
            documents.deleteById(id);
            deleted++;
            if (names.size() < 5) names.add(meta.get().fileName());
        }
        // Mot lan cho ca lo: cache la toan cuc, xoa 200 lan la vo ich.
        int purged = cache.clear();
        log.info("Bulk delete: {} document(s) deleted of {} requested, {} not found, "
                        + "{} cache entries purged.",
                deleted, targets.size(), notFound.size(), purged);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("requested", targets.size());
        out.put("notFound", notFound);
        out.put("sample", names);
        out.put("remaining", remaining);
        out.put("message", "Đã xoá " + deleted + " tài liệu và toàn bộ đoạn của chúng."
                + (notFound.isEmpty() ? "" : " " + notFound.size() + " tài liệu không còn tồn tại.")
                + (remaining == 0 ? "" : " Còn " + remaining + " tài liệu khớp bộ lọc chưa xoá "
                        + "(mỗi lượt tối đa " + MAX_BULK + ") — bấm lại để xoá tiếp."));
        return out;
    }

    public record MoveCategoryRequest(List<Long> ids, String category) {
    }

    /**
     * Move documents into another category.
     *
     * <p>Until this existed a category could only be set at ingest time, so a folder scan that
     * derived the wrong slug could only be repaired by deleting the documents and ingesting them
     * again. Three things have to move together or the document ends up half-way: the category on
     * the document row, the {@code doc_key} built from it, and the category denormalised onto
     * every chunk - retrieval filters on the chunk copy, not the document one.
     */
    @PostMapping("/documents/move-category")
    public Map<String, Object> moveCategory(@RequestBody MoveCategoryRequest request) {
        String category = request.category() == null ? "" : request.category().trim();
        if (category.isBlank()) {
            throw new IllegalArgumentException("Thiếu nhóm tài liệu đích.");
        }
        List<Long> ids = request.ids() == null ? List.of()
                : request.ids().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of("moved", 0, "message", "Không có tài liệu nào được chọn.");
        }
        if (ids.size() > MAX_BULK) {
            throw new IllegalArgumentException("Một lượt chuyển tối đa " + MAX_BULK
                    + " tài liệu, đang yêu cầu " + ids.size() + ".");
        }

        List<String> skipped = new ArrayList<>();
        int moved = 0;
        for (Long id : ids) {
            var found = documents.findById(id);
            if (found.isEmpty()) {
                skipped.add("#" + id + " (không còn tồn tại)");
                continue;
            }
            DocumentMeta meta = found.get();
            if (category.equals(meta.category())) continue;

            String newKey = category + "/" + meta.fileName();
            if (documents.docKeyTakenByOther(newKey, id)) {
                // Overwriting would silently destroy the document already sitting there.
                skipped.add(meta.fileName() + " (nhóm đích đã có file trùng tên)");
                continue;
            }
            transactions.executeWithoutResult(status -> {
                documents.updateCategory(id, category, newKey);
                chunks.updateCategory(id, category, newKey);
            });
            moved++;
        }

        boolean declared = moved > 0 && platform.ensureCollection(category, "move-category");
        int purged = moved > 0 ? cache.clear() : 0;
        log.info("Moved {} document(s) to category '{}', {} skipped, {} cache entries purged.",
                moved, category, skipped.size(), purged);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved);
        out.put("skipped", skipped);
        out.put("collectionCreated", declared);
        out.put("message", "Đã chuyển " + moved + " tài liệu sang nhóm '" + category + "'."
                + (skipped.isEmpty() ? "" : " Bỏ qua " + skipped.size() + ": "
                        + String.join("; ", skipped) + ".")
                + (declared ? " Nhóm này vừa được tạo và CHƯA cấp quyền đọc cho ai." : ""));
        return out;
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        DocumentMeta meta = documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Khong tim thay tai lieu id=" + id));
        documents.deleteById(id);
        int purged = cache.clear();
        log.info("Deleted document {} ({}), {} cache entries purged.", id, meta.fileName(), purged);
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
