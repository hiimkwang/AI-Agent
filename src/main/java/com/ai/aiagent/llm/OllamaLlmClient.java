package com.ai.aiagent.llm;

import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import com.ai.aiagent.llm.LlmDtos.StreamSink;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.output.Response;

import java.time.Duration;

public class OllamaLlmClient implements LlmClient {

    private final String modelName;
    private final ChatLanguageModel sync;
    private final StreamingChatLanguageModel streaming;

    public OllamaLlmClient(String baseUrl, String modelName, double temperature, int timeoutSeconds) {
        this.modelName = modelName;
        this.sync = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .numCtx(8192)
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 300)))
                .build();
        this.streaming = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .numCtx(8192)
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 300)))
                .build();
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OLLAMA;
    }

    @Override
    public String model() {
        return modelName;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.nanoTime();
        Response<AiMessage> response = sync.generate(OpenAiLlmClient.toMessages(request));
        long ms = (System.nanoTime() - start) / 1_000_000;
        String text = response.content() == null ? "" : OpenAiLlmClient.nullToEmpty(response.content().text());
        return new LlmResponse(text, usage(request, text), provider().name(), modelName, ms);
    }

    @Override
    public void stream(LlmRequest request, StreamSink sink) {
        long start = System.nanoTime();
        StringBuilder buffer = new StringBuilder();
        streaming.generate(OpenAiLlmClient.toMessages(request), new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                // LangChain4j 0.31 khong co cach huy request dang chay: chi ngung chuyen tiep.
                if (sink.cancelled()) return;
                buffer.append(token);
                sink.onToken(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                String text = buffer.toString();
                sink.onComplete(new LlmResponse(text, usage(request, text),
                        provider().name(), modelName, ms));
            }

            @Override
            public void onError(Throwable error) {
                sink.onError(error);
            }
        });
    }

    private LlmUsage usage(LlmRequest request, String output) {
        int in = ModelPricing.estimateTokens(
                OpenAiLlmClient.nullToEmpty(request.system()) + OpenAiLlmClient.nullToEmpty(request.user()));
        return new LlmUsage(in, ModelPricing.estimateTokens(output), 0.0);
    }
}
