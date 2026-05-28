package com.ai.aiagent.modules.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
@Slf4j
public class RagIngestionService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public RagIngestionService(EmbeddingModel ragEmbeddingModel, EmbeddingStore<TextSegment> ragEmbeddingStore) {
        this.embeddingModel = ragEmbeddingModel;
        this.embeddingStore = ragEmbeddingStore;
    }

    public void processOfficeFile(File file) {
        log.info("--- BẮT ĐẦU ĐỌC FILE: {} ---", file.getName());

        try {
            DocumentParser officeParser = new ApachePoiDocumentParser();
            Document document = FileSystemDocumentLoader.loadDocument(file.toPath(), officeParser);

            log.info("1. Đã phân giải xong file. Tổng số ký tự đọc được: {}", document.text().length());

            // In thử 200 ký tự đầu tiên xem nó đọc có bị rác không
            log.info("Preview nội dung đọc được: {}", document.text().substring(0, Math.min(200, document.text().length())));

            DocumentSplitter splitter = DocumentSplitters.recursive(1000, 100);
            List<TextSegment> segments = splitter.split(document);

            log.info("2. Đã băm file thành {} đoạn (chunks). Đang tiến hành nhúng Vector...", segments.size());

            int count = 0;
            for (TextSegment segment : segments) {
                Embedding embedding = embeddingModel.embed(segment).content();
                embeddingStore.add(embedding, segment);
                count++;
            }
            log.info("--- HOÀN TẤT! Đã lưu {} vector vào Postgres ---", count);

        } catch (Exception e) {
            log.error("Xảy ra lỗi nghiêm trọng khi nạp file {}: ", file.getName(), e);
        }
    }
}