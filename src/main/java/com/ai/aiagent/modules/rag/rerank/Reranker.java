package com.ai.aiagent.modules.rag.rerank;

import com.ai.aiagent.modules.rag.store.RetrievedChunk;

import java.util.List;

/**
 * Bộ rerank: sắp xếp lại các ứng viên theo độ liên quan THỰC SỰ với câu hỏi,
 * trả về tối đa {@code topK} phần tử.
 */
public interface Reranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
