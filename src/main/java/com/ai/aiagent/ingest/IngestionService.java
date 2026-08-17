package com.ai.aiagent.ingest;

import com.ai.aiagent.common.Hashes;
import com.ai.aiagent.config.RagProperties;
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

/**
 * Nap MOT tai lieu: file bat ky -> Markdown -> chunk theo cau truc -> vector -> DB.
 *
 * Thu tu buoc (moi buoc giai quyet mot van de cu the cua ban truoc):
 *
 *  1) CHUYEN SANG MARKDOWN   - mot dinh dang trung gian duy nhat, giu heading va bang
 *  2) DOC FRONT-MATTER       - lay ngay hieu luc / phong ban / so hieu / phien ban
 *  3) HASH + BO QUA NEU CHUA DOI - nap lai 500 file khong ton tien LLM vo ich
 *  4) BAM CHUNK THEO HEADING - khong con cat ngang dieu/khoan/bang
 *  5) SINH NGU CANH (tuy chon)
 *  6) NHUNG THEO LO + RETRY  - file lon khong con lam hong ca luot nap
 *  7) GHI DE THEO docKey     - docKey = category/fileName nen file cung ten o hai
 *                              nhom khac nhau khong de len nhau nua
 */
@Service
@Slf4j
public class IngestionService {

    /** Tham so nap, thuong den tu form upload; front-matter bo sung phan con lai. */
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

    public IngestionService(DocumentConverterService converter,
                            MarkdownChunker chunker,
                            ContextualEnricher enricher,
                            EmbeddingService embeddings,
                            DocumentRepository documents,
                            ChunkRepository chunks,
                            RagProperties props,
                            TransactionTemplate transactions,
                            AntivirusScanner antivirus) {
        this.converter = converter;
        this.chunker = chunker;
        this.enricher = enricher;
        this.embeddings = embeddings;
        this.documents = documents;
        this.chunks = chunks;
        this.props = props;
        this.transactions = transactions;
        this.antivirus = antivirus;
    }

    public IngestResult ingest(byte[] content, String fileName, IngestOptions options) {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        // 0) Quet virus TRUOC moi thu khac. Day la diem nghen duy nhat cua ca ba duong
        //    nap (/upload, /upload-batch, /ingest-folder) nen dat o day la du - dat o
        //    tung controller thi duong nao them sau se bi bo sot.
        antivirus.scan(content, fileName);

        // 1) Chuyen sang Markdown
        DocumentConverterService.Result converted = converter.convert(content, fileName);
        warnings.addAll(converted.warnings());
        if (converted.isEmpty()) {
            log.warn("Tai lieu '{}' khong co noi dung sau khi chuyen doi -> bo qua.", fileName);
            return new IngestResult(Outcome.EMPTY, -1, null, fileName,
                    converted.format(), 0, 0, warnings);
        }

        // 2) Front-matter -> metadata
        FrontMatter.Parsed front = FrontMatter.parse(converted.markdown());
        String markdown = front.hadBlock() ? front.body() : converted.markdown();
        DocumentMeta meta = buildMeta(fileName, converted, front, options, markdown);

        // 3) Bo qua neu noi dung khong doi
        if (props.getIngestion().isSkipUnchanged() && !options.force()) {
            String previous = documents.findSha(meta.docKey()).orElse(null);
            if (previous != null && previous.equals(meta.contentSha256())) {
                log.info("Bo qua '{}': noi dung khong doi so voi lan nap truoc.", fileName);
                return new IngestResult(Outcome.SKIPPED_UNCHANGED, -1, meta.docKey(), fileName,
                        converted.format(), 0, markdown.length(), warnings);
            }
        }

        // 4) Bam chunk theo cau truc
        List<MarkdownChunker.Chunk> parts = chunker.chunk(markdown);
        if (parts.isEmpty()) {
            warnings.add("Khong tao duoc chunk nao tu tai lieu.");
            return new IngestResult(Outcome.EMPTY, -1, meta.docKey(), fileName,
                    converted.format(), 0, markdown.length(), warnings);
        }

        // 5) Ngu canh (tuy chon)
        List<String> contexts = enricher.buildContexts(fileName, parts);

        // 6) Nhung theo lo, co retry.
        // Gan dinh danh tai lieu (ten + so hieu + ngay hieu luc) vao dau moi chunk: neu
        // khong, nhung thong tin do khong nam trong vector va cau hoi dang "quy dinh so
        // bao nhieu" se truot du tai lieu dung nam ngay do.
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

        // 7) Ghi de theo docKey, trong mot transaction
        long documentId = persist(meta, parts, contexts, vectors, markdown);

        log.info("Nap xong '{}' [{}]: {} chunk, {} ky tu Markdown, {} ms",
                fileName, converted.format(), parts.size(), markdown.length(),
                System.currentTimeMillis() - start);

        return new IngestResult(Outcome.INGESTED, documentId, meta.docKey(), fileName,
                converted.format(), parts.size(), markdown.length(), warnings);
    }

    /**
     * Ghi de nguyen tu: xoa chunk cu + ghi chunk moi trong CUNG mot transaction.
     * Neu khong, mot loi giua duong se de tai lieu o trang thai chi co mot phan chunk.
     *
     * Dung {@link TransactionTemplate} chu khong phai {@code @Transactional}: day la
     * loi goi noi bo cung class nen proxy cua Spring se KHONG chan duoc, transaction
     * se im lang khong duoc mo.
     */
    private long persist(DocumentMeta meta, List<MarkdownChunker.Chunk> parts,
                         List<String> contexts, List<float[]> vectors, String markdown) {
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
        // Don ca chunk cu duoc nap truoc khi co bang rag_documents (document_id NULL)
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

    private DocumentMeta buildMeta(String fileName, DocumentConverterService.Result converted,
                                   FrontMatter.Parsed front, IngestOptions options, String markdown) {
        // Tham so tu request thang the front-matter; front-matter thang mac dinh.
        String category = firstNonBlank(options.category(), front.text("category"));
        if (category != null) category = category.toLowerCase();

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

    /** Lay heading cap 1 dau tien lam tieu de tai lieu. */
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
