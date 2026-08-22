package com.ai.aiagent.eval;

import com.ai.aiagent.chat.ChatDtos.ChatRequest;
import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.ai.aiagent.chat.RagChatService;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.InternalLlm;
import com.ai.aiagent.llm.LlmProvider;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.settings.RagSettingsService;
import com.ai.aiagent.store.EvalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EvalService {

    public record EvalRequest(String suite, String provider, String model, String category) {
    }

    public record CaseResult(String question, String answer, List<String> sources,
                             Double faithfulness, Double answerRelevance, Boolean sourceHit,
                             boolean judged, boolean abstained, long latencyMs) {
    }

    public record EvalReport(long runId, String suite, int total, int judged, int skipped,
                             Double avgFaithfulness, Double avgAnswerRelevance,
                             Double contextRecall, Double abstainRate,
                             Integer avgLatencyMs, Double totalCostUsd,
                             List<CaseResult> results) {
    }

    private final RagChatService chatService;
    private final InternalLlm internalLlm;
    private final EvalRepository repository;
    private final RagSettingsService settings;
    private final RagProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvalService(RagChatService chatService, InternalLlm internalLlm,
                       EvalRepository repository, RagSettingsService settings,
                       RagProperties props) {
        this.chatService = chatService;
        this.internalLlm = internalLlm;
        this.repository = repository;
        this.settings = settings;
        this.props = props;
    }

    public EvalReport run(EvalRequest request, AccessScope scope) {
        String suite = request.suite() == null || request.suite().isBlank()
                ? "default" : request.suite();
        List<EvalRepository.EvalCase> cases = repository.listCases(suite, true);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Bo cau hoi '" + suite + "' khong co case nao dang bat. "
                    + "Them case qua POST /api/v1/rag/eval/cases truoc.");
        }

        LlmProvider provider = LlmProvider.fromString(request.provider());
        String model = request.model();
        if (provider == null) {
            provider = settings.current().provider();
            model = settings.current().model();
        }

        long runId = repository.createRun(suite, provider.name(), model, cases.size(), snapshotParams());

        List<CaseResult> results = new ArrayList<>();
        double sumFaithfulness = 0;
        double sumRelevance = 0;
        int judged = 0;
        int skipped = 0;
        int recallDenominator = 0;
        int recallHits = 0;
        int abstainCount = 0;
        long latencySum = 0;
        double costSum = 0;

        for (EvalRepository.EvalCase testCase : cases) {
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setQuestion(testCase.question());
            chatRequest.setProvider(provider.name());
            chatRequest.setModel(model);
            chatRequest.setCategory(testCase.category() != null
                    ? testCase.category() : request.category());
            chatRequest.setUseCache(false);

            ChatResponse response;
            try {
                response = chatService.answer(chatRequest, scope);
            } catch (Exception e) {
                log.warn("Eval case '{}' failed: {}", testCase.question(), e.getMessage());
                repository.addResult(runId, testCase.id(), testCase.question(),
                        "LOI: " + e.getMessage(), List.of(), null, null, null, false, false, null);
                results.add(new CaseResult(testCase.question(), "LOI: " + e.getMessage(),
                        List.of(), null, null, null, false, false, 0));
                skipped++;
                continue;
            }

            List<String> sources = response.citations().stream()
                    .map(c -> c.fileName()).distinct().toList();
            latencySum += response.latencyMs();
            costSum += response.usage() == null ? 0 : response.usage().costUsd();
            if (response.abstained()) abstainCount++;

            Boolean sourceHit = null;
            if (testCase.expectedSource() != null && !testCase.expectedSource().isBlank()) {
                recallDenominator++;
                String expected = testCase.expectedSource().toLowerCase();
                sourceHit = sources.stream()
                        .anyMatch(s -> s != null && s.toLowerCase().contains(expected));
                if (sourceHit) recallHits++;
            }

            Judgement judgement = judge(testCase.question(), response.answer(),
                    response.abstained(), testCase.expectedAnswer());

            if (judgement.parsed()) {
                sumFaithfulness += judgement.faithfulness();
                sumRelevance += judgement.relevance();
                judged++;
            } else {
                skipped++;
            }

            repository.addResult(runId, testCase.id(), testCase.question(), response.answer(),
                    sources, judgement.parsed() ? judgement.faithfulness() : null,
                    judgement.parsed() ? judgement.relevance() : null,
                    sourceHit, judgement.parsed(), response.abstained(),
                    (int) response.latencyMs());

            results.add(new CaseResult(testCase.question(), response.answer(), sources,
                    judgement.parsed() ? judgement.faithfulness() : null,
                    judgement.parsed() ? judgement.relevance() : null,
                    sourceHit, judgement.parsed(), response.abstained(), response.latencyMs()));
        }

        Double avgFaithfulness = judged == 0 ? null : round(sumFaithfulness / judged);
        Double avgRelevance = judged == 0 ? null : round(sumRelevance / judged);
        Double contextRecall = recallDenominator == 0 ? null
                : round((double) recallHits / recallDenominator);
        Double abstainRate = cases.isEmpty() ? null : round((double) abstainCount / cases.size());
        Integer avgLatency = cases.isEmpty() ? null : (int) (latencySum / cases.size());

        repository.completeRun(runId, judged, skipped, avgFaithfulness, avgRelevance,
                contextRecall, abstainRate, avgLatency, round6(costSum));

        log.info("Eval '{}' finished: {} case(s), {} judged, {} skipped | faithfulness={} "
                        + "relevance={} recall={} abstain={} latency={}ms cost=${}",
                suite, cases.size(), judged, skipped, avgFaithfulness, avgRelevance,
                contextRecall, abstainRate, avgLatency, round6(costSum));

        return new EvalReport(runId, suite, cases.size(), judged, skipped, avgFaithfulness,
                avgRelevance, contextRecall, abstainRate, avgLatency, round6(costSum), results);
    }

    private record Judgement(boolean parsed, double faithfulness, double relevance) {
        static Judgement failed() {
            return new Judgement(false, 0, 0);
        }
    }

    private Judgement judge(String question, String answer, boolean abstained, String expectedAnswer) {
        if (abstained) {
            if (expectedAnswer == null || expectedAnswer.isBlank()) {
                return new Judgement(true, 1.0, 0.5);
            }
        }
        try {
            String prompt = """
                    Ban la giam khao danh gia cau tra loi cua mot tro ly RAG tra loi dua tren
                    tai lieu noi bo. Cham 2 tieu chi, moi tieu chi tu 0.0 den 1.0:

                    - faithfulness: cau tra loi co bam sat du lieu, khong bia dat, khong mau thuan
                      noi tai khong. Neu tra loi "khong tim thay thong tin trong tai lieu" thi
                      faithfulness = 1.0 (vi khong bia gi).
                    - relevance: cau tra loi co dung trong tam va giai quyet cau hoi khong.
                      Neu tra loi "khong tim thay" ma cau hoi le ra tra loi duoc thi relevance thap.

                    CHI tra ve JSON, khong giai thich: {"faithfulness": 0.0, "relevance": 0.0}

                    CAU HOI: %s

                    CAU TRA LOI: %s
                    %s
                    """.formatted(question, answer,
                    expectedAnswer == null || expectedAnswer.isBlank() ? ""
                            : "\nDAP AN MONG DOI (de doi chieu): " + expectedAnswer);

            String response = internalLlm.generate(prompt);
            if (response == null) return Judgement.failed();

            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.debug("Judge returned no JSON for '{}', dropping the case from the sample.", question);
                return Judgement.failed();
            }
            JsonNode json = mapper.readTree(response.substring(start, end + 1));
            if (!json.has("faithfulness") || !json.has("relevance")) {
                return Judgement.failed();
            }
            return new Judgement(true,
                    clamp(json.path("faithfulness").asDouble(-1)),
                    clamp(json.path("relevance").asDouble(-1)));
        } catch (Exception e) {
            log.debug("Judge failed for '{}': {}. Dropping the case from the sample.",
                    question, e.getMessage());
            return Judgement.failed();
        }
    }

    private Map<String, Object> snapshotParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topK", props.getRetrieval().getTopK());
        m.put("candidates", props.getRetrieval().getCandidates());
        m.put("vectorTopK", props.getRetrieval().getVectorTopK());
        m.put("fulltextTopK", props.getRetrieval().getFulltextTopK());
        m.put("hybridEnabled", props.getRetrieval().isHybridEnabled());
        m.put("multiQueryEnabled", props.getRetrieval().isMultiQueryEnabled());
        m.put("hydeEnabled", props.getRetrieval().isHydeEnabled());
        m.put("minRerankScore", props.getRetrieval().getMinRerankScore());
        m.put("minVectorScore", props.getRetrieval().getMinVectorScore());
        m.put("rerankProvider", props.getRerank().getProvider());
        m.put("childMaxChars", props.getChunking().getChildMaxChars());
        m.put("parentMaxChars", props.getChunking().getParentMaxChars());
        m.put("contextualEnabled", props.getIngestion().isContextualEnabled());
        m.put("internalModel", internalLlm.describe());
        return m;
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private Double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private Double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
