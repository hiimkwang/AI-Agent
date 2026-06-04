package com.ai.aiagent.modules.rag.store;

/**
 * Một bản ghi chunk chuẩn bị ghi vào DB.
 *
 * @param docId         khóa gom nhóm theo tài liệu (dùng để xóa/ghi đè) – hiện = file_name
 * @param fileName      tên file nguồn
 * @param category      nhóm/chủ đề tài liệu (vd "nhan-su", "ky-thuat") – dùng để lọc khi tìm; có thể null
 * @param chunkIndex    thứ tự chunk trong tài liệu
 * @param content       nội dung child chunk (đoạn nhỏ để tìm kiếm)
 * @param context       câu ngữ cảnh do LLM sinh (Contextual Retrieval); có thể rỗng
 * @param parentContent nội dung parent chunk (đoạn lớn để đưa vào câu trả lời)
 * @param embedding     vector của (context + content)
 */
public record RagChunk(
        String docId,
        String fileName,
        String category,
        int chunkIndex,
        String content,
        String context,
        String parentContent,
        float[] embedding
) {}
