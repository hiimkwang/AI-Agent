package com.ai.aiagent.modules.rag.entity;

import java.time.LocalDateTime;
import java.util.Map;

public class RagDocumentSegment {
    private String id;
    private String fileName;
    private String textContent;
    private Map<String, String> metadata;
    private LocalDateTime syncedAt;

    // Boilerplate Code (Getter/Setter/Constructor)
    public RagDocumentSegment() {}

    public RagDocumentSegment(String id, String fileName, String textContent, Map<String, String> metadata, LocalDateTime syncedAt) {
        this.id = id;
        this.fileName = fileName;
        this.textContent = textContent;
        this.metadata = metadata;
        this.syncedAt = syncedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }
}