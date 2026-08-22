package com.ai.aiagent.llm;

import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.StreamSink;

public interface LlmClient {

    LlmProvider provider();

    String model();

    LlmResponse complete(LlmRequest request);

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
