package com.ai.aiagent.llm;

import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.StreamSink;

/**
 * Giao dien chung cho moi provider.
 *
 * Truoc day tang chat gan chat vao {@code ChatLanguageModel} cua langchain4j, nen
 * khong stream duoc va khong lay duoc so token/chi phi. Truu tuong nay cho phep:
 *   - stream deu nhau tren ca 4 provider,
 *   - luon tra ve {@link com.ai.aiagent.llm.LlmDtos.LlmUsage} de tinh chi phi.
 */
public interface LlmClient {

    LlmProvider provider();

    String model();

    LlmResponse complete(LlmRequest request);

    /**
     * Sinh cau tra loi theo tung token.
     *
     * Cai dat MAC DINH: goi {@link #complete} roi ban ca cuc mot lan - de provider
     * nao chua ho tro stream van chay dung, chi la khong co cam giac "chay chu".
     */
    default void stream(LlmRequest request, StreamSink sink) {
        try {
            LlmResponse response = complete(request);
            if (response.text() != null && !response.text().isEmpty()) {
                sink.onToken(response.text());
            }
            sink.onComplete(response);
        } catch (Exception e) {
            sink.onError(e);
        }
    }

    default boolean supportsStreaming() {
        return false;
    }
}
