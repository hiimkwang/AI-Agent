package com.ai.aiagent.ingest;

import com.ai.aiagent.common.Hashes;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.llm.EmbeddingService;
import com.ai.aiagent.security.AntivirusScanner;
import com.ai.aiagent.store.ChunkRepository;
import com.ai.aiagent.store.DocumentRepository;
import com.ai.aiagent.store.StoreModels.ChunkToInsert;
import com.ai.aiagent.store.StoreModels.DocumentMeta;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class IngestionService {

    @Builder
    public record IngestOptions(
            String category,
            String department,
            String docKeyOverride,
            String sourcePath,
            List<String> allowedRoles,
            LocalDate effectiveDate,
            LocalDate expiresDate,
            String status,
            String createdBy,
            boolean force
    ) {
        public static IngestOptions of(String category, String createdBy) {
            return IngestOptions.builder().category(category).createdBy(createdBy).build();
        }
    }

    public enum Outcome { INGESTED, SKIPPED_UNCHANGED, EMPTY }

    public record IngestResult(
            Outcome outcome,
            long documentId,
            String docKey,
            String fileName,
            DocumentFormat format,
            int chunkCount,
            int markdownChars,
            List<String> warnings
    ) {
    }

    private final DocumentConverterService converter;
    private final MarkdownChunker chunker;
    private final ContextualEnricher enricher;
    private final EmbeddingService embeddings;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final RagProperties props;
    private final TransactionTemplate transactions;
    private final AntivirusScanner antivirus;
    private final PlatformService platform;

    public IngestionService(DocumentConverterService converter,
                            MarkdownChunker chunker,
                            ContextualEnricher enricher,
                            EmbeddingService embeddings,
                            DocumentRepository documents,
                            ChunkRepository chunks,
                            RagProperties props,
                            TransactionTemplate transactions,
                            AntivirusScanner antivirus,
                            PlatformService platform) {
        this.converter = converter;
        this.chunker = chunker;
        this.enricher = enricher;
        this.embeddings = embeddings;
        this.documents = documents;
        this.chunks = chunks;
        this.props = props;
        this.transactions = transactions;
        this.antivirus = antivirus;
        this.platform = platform;
    }

    public IngestResult ingest(byte[] content, String fileName, IngestOptions options) {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        // Single chokepoint for all three ingestion paths; putting this in a controller
        // would leave any later path unscanned.
        antivirus.scan(content, fileName);

        DocumentConverterService.Result converted = converter.convert(content, fileName);
        warnings.addAll(converted.warnings());
        if (converted.isEmpty()) {
            log.warn("Document '{}' is empty after conversion, skipped.", fileName);
            return new IngestResult(Outcome.EMPTY, -1, null, fileName,
                    converted.format(), 0, 0, warnings);
        }

        FrontMatter.Parsed front = FrontMatter.parse(converted.markdown());
        String markdown = front.hadBlock() ? front.body() : converted.markdown();
        DocumentMeta meta = buildMeta(fileName, converted, front, options, markdown);

        if (props.getIngestion().isSkipUnchanged() && !options.force()) {
            String previous = documents.findSha(meta.docKey()).orElse(null);
            if (previous != null && previous.equals(meta.contentSha256())) {
                log.debug("Skipping '{}': content unchanged since the last ingest.", fileName);
                return new IngestResult(Outcome.SKIPPED_UNCHANGED, -1, meta.docKey(), fileName,
                        converted.format(), 0, markdown.length(), warnings);
            }
        }

        List<MarkdownChunker.Chunk> parts = chunker.chunk(markdown);
        if (parts.isEmpty()) {
            warnings.add("Khong tao duoc chunk nao tu tai lieu.");
            return new IngestResult(Outcome.EMPTY, -1, meta.docKey(), fileName,
                    converted.format(), 0, markdown.length(), warnings);
        }

        List<String> contexts = enricher.buildContexts(fileName, parts);

        String identity = props.getChunking().isPrefixDocumentIdentity()
                ? MarkdownChunker.documentIdentity(meta.title(), meta.docNumber(),
                        meta.effectiveDate())
                : null;
        List<String> embedTexts = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            embedTexts.add(parts.get(i).embedText(identity, contexts.get(i)));
        }
        List<float[]> vectors = embeddings.embedAll(embedTexts);
        if (vectors.size() != parts.size()) {
            throw new IllegalStateException("So vector (" + vectors.size()
                    + ") khong khop so chunk (" + parts.size() + ").");
        }

        long documentId = persist(meta, parts, contexts, vectors, markdown);

        // Same argument as the antivirus scan above: this is the one place all three ingest
        // paths pass through. A folder scan derives a category per subfolder and used to write
        // it with no matching collection row, leaving the documents readable by admins only -
        // and nothing in the UI could repair that afterwards.
        if (meta.category() != null && !meta.category().isBlank()
                && platform.ensureCollection(meta.category(), "ingest")) {
            warnings.add("Đã tự khai nhóm tài liệu '" + meta.category()
                    + "'. Nhóm này chưa cấp quyền đọc cho ai — vào Quản trị › Bot & phân quyền "
                    + "để chọn nhóm Entra được đọc.");
        }

        log.info("Ingested '{}' [{}]: {} chunks, {} chars of Markdown, {} ms",
                fileName, converted.format(), parts.size(), markdown.length(),
                System.currentTimeMillis() - start);

        return new IngestResult(Outcome.INGESTED, documentId, meta.docKey(), fileName,
                converted.format(), parts.size(), markdown.length(), warnings);
    }

    private long persist(DocumentMeta meta, List<MarkdownChunker.Chunk> parts,
                         List<String> contexts, List<float[]> vectors, String markdown) {
        // TransactionTemplate, not @Transactional: the call below is a self-invocation
        // and the proxy would not apply the annotation.
        Long id = transactions.execute(status -> persistInTransaction(meta, parts, contexts, vectors, markdown));
        if (id == null) {
            throw new IllegalStateException("Khong ghi duoc tai lieu vao DB.");
        }
        return id;
    }

    private long persistInTransaction(DocumentMeta meta, List<MarkdownChunker.Chunk> parts,
                                      List<String> contexts, List<float[]> vectors, String markdown) {
        long documentId = documents.upsert(meta);
        chunks.deleteByDocumentId(documentId);
        chunks.deleteByDocKey(meta.docKey());

        List<ChunkToInsert> rows = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            MarkdownChunker.Chunk part = parts.get(i);
            rows.add(new ChunkToInsert(
                    documentId,
                    meta.docKey(),
                    meta.fileName(),
                    meta.category(),
                    part.index(),
                    part.headingPath(),
                    part.content(),
                    contexts.get(i),
                    part.parentContent(),
                    Hashes.sha256(part.content()),
                    vectors.get(i)));
        }
        chunks.insertBatch(rows);
        documents.updateChunkCount(documentId, rows.size());
        if (props.getConvert().isStoreMarkdown()) {
            documents.updateMarkdown(documentId, markdown);
        }
        return documentId;
    }

    /**
     * A document with no category is readable by admins ONLY, and nothing says so. Retrieval
     * filters {@code c.category} against the caller's collection slugs, and NULL matches none -
     * so the bot answers "khong tim thay" for every normal user while an admin sees the document
     * fine on the web. That happened in production with a document ingested through the upload
     * path; {@code orphanCategories()} did not catch it either because it skips NULL.
     *
     * <p>This is also the only place all three ingest paths pass through, the same reason the
     * virus scan lives here rather than in each controller.
     */
    static void requireCategory(String category, String fileName) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Thiếu nhóm tài liệu (category) cho '" + fileName
                    + "'. Tài liệu không có nhóm chỉ quản trị viên đọc được, người dùng thường "
                    + "và bot Teams sẽ không bao giờ tìm thấy. Cách khai: chọn nhóm khi nạp, "
                    + "hoặc đặt 'category' trong front matter của file, hoặc nạp thư mục với "
                    + "categoryFromFolder=true để lấy nhóm theo tên thư mục chứa file.");
        }
        // doc_key is "category/fileName", so a slash inside the category would make the key
        // ambiguous and the overwrite-on-reingest rule stop working.
        if (category.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Nhóm tài liệu '" + category
                    + "' không được chứa dấu '/'.");
        }
    }

    private DocumentMeta buildMeta(String fileName, DocumentConverterService.Result converted,
                                   FrontMatter.Parsed front, IngestOptions options, String markdown) {
        String category = firstNonBlank(options.category(), front.text("category"));
        if (category != null) category = category.toLowerCase();
        requireCategory(category, fileName);

        String department = firstNonBlank(options.department(), front.text("department"), category);
        if (department != null) department = department.toLowerCase();

        String docKey = options.docKeyOverride() != null && !options.docKeyOverride().isBlank()
                ? options.docKeyOverride()
                : (category == null || category.isBlank() ? fileName : category + "/" + fileName);

        List<String> roles = options.allowedRoles() != null && !options.allowedRoles().isEmpty()
                ? options.allowedRoles()
                : front.list("roles").stream().map(String::toUpperCase).toList();

        LocalDate effective = options.effectiveDate() != null
                ? options.effectiveDate() : front.date("effective_date");
        LocalDate expires = options.expiresDate() != null
                ? options.expiresDate() : front.date("expires_date");
        String status = firstNonBlank(options.status(), front.text("status"), "ACTIVE");

        return new DocumentMeta(
                null,
                docKey,
                fileName,
                firstNonBlank(front.text("title"), titleFromMarkdown(markdown), stripExtension(fileName)),
                category,
                department,
                front.text("doc_number"),
                firstNonBlank(front.text("version"), front.text("doc_version")),
                options.sourcePath(),
                converted.format().name(),
                effective,
                expires,
                status.toUpperCase(),
                roles,
                Hashes.sha256(markdown),
                0,
                markdown.length(),
                options.createdBy(),
                null,
                null);
    }

    private String titleFromMarkdown(String markdown) {
        for (String line : markdown.split("\n", 40)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) return trimmed.substring(2).strip();
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
