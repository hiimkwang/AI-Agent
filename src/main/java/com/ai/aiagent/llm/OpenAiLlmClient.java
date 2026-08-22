package com.ai.aiagent.llm;

import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import com.ai.aiagent.llm.LlmDtos.StreamSink;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class OpenAiLlmClient implements LlmClient {

    private final String modelName;
    private final ChatLanguageModel sync;
    private final StreamingChatLanguageModel streaming;

    public OpenAiLlmClient(String apiKey, String baseUrl, String modelName,
                           double temperature, int maxOutputTokens, int timeoutSeconds) {
        this.modelName = modelName;

        OpenAiChatModel.OpenAiChatModelBuilder syncBuilder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxOutputTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds));
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamBuilder =
                OpenAiStreamingChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .temperature(temperature)
                        .maxTokens(maxOutputTokens)
                        .timeout(Duration.ofSeconds(timeoutSeconds));

        if (baseUrl != null && !baseUrl.isBlank()) {
            syncBuilder.baseUrl(baseUrl);
            streamBuilder.baseUrl(baseUrl);
        }
        this.sync = syncBuilder.build();
        this.streaming = streamBuilder.build();
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI;
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
        Response<AiMessage> response = sync.generate(toMessages(request));
        long ms = (System.nanoTime() - start) / 1_000_000;
        String text = response.content() == null ? "" : nullToEmpty(response.content().text());
        return new LlmResponse(text, toUsage(response.tokenUsage(), request, text),
                provider().name(), modelName, ms);
    }

    @Override
    public void stream(LlmRequest request, StreamSink sink) {
        long start = System.nanoTime();
        StringBuilder buffer = new StringBuilder();
        streaming.generate(toMessages(request), new StreamingResponseHandler<AiMessage>() {
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
                String text = buffer.length() > 0
                        ? buffer.toString()
                        : (response.content() == null ? "" : nullToEmpty(response.content().text()));
                sink.onComplete(new LlmResponse(text, toUsage(response.tokenUsage(), request, text),
                        provider().name(), modelName, ms));
            }

            @Override
            public void onError(Throwable error) {
                sink.onError(error);
            }
        });
    }

    private LlmUsage toUsage(TokenUsage usage, LlmRequest request, String output) {
        int in;
        int out;
        if (usage != null && usage.inputTokenCount() != null) {
            in = usage.inputTokenCount();
            out = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        } else {
            in = ModelPricing.estimateTokens(nullToEmpty(request.system()) + nullToEmpty(request.user()));
            out = ModelPricing.estimateTokens(output);
        }
        return ModelPricing.usage(provider(), modelName, in, out);
    }

    static List<ChatMessage> toMessages(LlmRequest request) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(SystemMessage.from(request.system()));
        }
        messages.add(UserMessage.from(request.user()));
        return messages;
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
