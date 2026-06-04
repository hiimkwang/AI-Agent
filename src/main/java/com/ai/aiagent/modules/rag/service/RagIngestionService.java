package com.ai.aiagent.modules.rag.service;

import com.ai.aiagent.modules.rag.ingest.ContextualEnricher;
import com.ai.aiagent.modules.rag.store.RagChunk;
import com.ai.aiagent.modules.rag.store.RagVectorRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Nạp tài liệu theo kiến trúc RAG nâng cao:
 *   1) Parse text  ->  2) Băm PARENT (đoạn lớn)  ->  3) Băm CHILD (đoạn nhỏ) trong mỗi parent
 *   4) Sinh CONTEXT cho từng child (song song)   ->  5) Embed (context + child)
 *   6) Ghi đè theo file (chống trùng) và lưu DB
 *
 * Parent-Child: tìm bằng child nhỏ (chính xác) nhưng câu trả lời dùng parent lớn (đủ ngữ cảnh).
 */
@Service
@Slf4j
public class RagIngestionService {

    private final EmbeddingModel embeddingModel;
    private final RagVectorRepository repository;
    private final ContextualEnricher contextualEnricher;

    @Value("${rag.ingestion.parent-chunk-size}")
    private int parentSize;
    @Value("${rag.ingestion.parent-chunk-overlap}")
    private int parentOverlap;
    @Value("${rag.ingestion.child-chunk-size}")
    private int childSize;
    @Value("${rag.ingestion.child-chunk-overlap}")
    private int childOverlap;

    public RagIngestionService(EmbeddingModel ragEmbeddingModel,
                               RagVectorRepository repository,
                               ContextualEnricher contextualEnricher) {
        this.embeddingModel = ragEmbeddingModel;
        this.repository = repository;
        this.contextualEnricher = contextualEnricher;
    }

    public int processOfficeFile(File file) {
        return processOfficeFile(file, null);
    }

    /**
     * @param category nhóm/chủ đề của tài liệu (vd "nhan-su"); null = không phân loại.
     */
    public int processOfficeFile(File file, String category) {
        String fileName = file.getName();
        log.info("--- BẮT ĐẦU NẠP FILE: {} (category={}) ---", fileName, category);

        try {
            // 1) Parse
            DocumentParser parser = new ApachePoiDocumentParser();
            Document document = FileSystemDocumentLoader.loadDocument(file.toPath(), parser);
            log.info("1. Đọc xong: {} ký tự.", document.text().length());

            // 2) Băm PARENT
            DocumentSplitter parentSplitter = DocumentSplitters.recursive(parentSize, parentOverlap);
            List<TextSegment> parents = parentSplitter.split(document);
            log.info("2. Băm thành {} parent chunk.", parents.size());

            // 3) Với mỗi parent -> băm CHILD; gom song song danh sách (content, parentText)
            DocumentSplitter childSplitter = DocumentSplitters.recursive(childSize, childOverlap);
            List<String> contents = new ArrayList<>();
            List<String> parentTexts = new ArrayList<>();
            for (TextSegment parent : parents) {
                String parentText = parent.text();
                for (TextSegment child : childSplitter.split(Document.from(parentText))) {
                    contents.add(child.text());
                    parentTexts.add(parentText);
                }
            }
            if (contents.isEmpty()) {
                log.warn("Không tạo được chunk nào từ file {}.", fileName);
                return 0;
            }

            // 4) Sinh CONTEXT (song song nếu bật)
            log.info("3. Tạo {} child chunk (contextual={}). Sinh context...",
                    contents.size(), contextualEnricher.isEnabled());
            List<String> contexts = contextualEnricher.buildContexts(fileName, parentTexts, contents);

            // 5) Embed cả lô (text = context + content)
            List<TextSegment> embedInputs = new ArrayList<>(contents.size());
            for (int i = 0; i < contents.size(); i++) {
                String ctx = contexts.get(i);
                String embedText = (ctx == null || ctx.isBlank() ? "" : ctx + "\n") + contents.get(i);
                embedInputs.add(TextSegment.from(embedText));
            }
            log.info("4. Đang nhúng {} vector...", embedInputs.size());
            List<Embedding> embeddings = embeddingModel.embedAll(embedInputs).content();

            // 6) Dựng RagChunk + ghi đè theo file + lưu
            List<RagChunk> finalChunks = new ArrayList<>(contents.size());
            for (int i = 0; i < contents.size(); i++) {
                finalChunks.add(new RagChunk(
                        fileName, fileName, category, i,
                        contents.get(i), contexts.get(i), parentTexts.get(i),
                        embeddings.get(i).vector()
                ));
            }

            repository.deleteByDocId(fileName);
            repository.insertBatch(finalChunks);

            log.info("--- HOÀN TẤT! Đã lưu {} chunk cho '{}' ---", finalChunks.size(), fileName);
            return finalChunks.size();

        } catch (Exception e) {
            log.error("Lỗi nghiêm trọng khi nạp file {}: ", fileName, e);
            throw new RuntimeException("Lỗi khi nạp file: " + e.getMessage(), e);
        }
    }
}
