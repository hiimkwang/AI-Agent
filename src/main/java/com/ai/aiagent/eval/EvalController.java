package com.ai.aiagent.eval;

import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.store.EvalRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag/eval")
public class EvalController {

    private final EvalService evalService;
    private final RetrievalEvalService retrievalEvalService;
    private final EvalCaseBuilder caseBuilder;
    private final EvalRepository repository;

    public EvalController(EvalService evalService, RetrievalEvalService retrievalEvalService,
                          EvalCaseBuilder caseBuilder, EvalRepository repository) {
        this.evalService = evalService;
        this.retrievalEvalService = retrievalEvalService;
        this.caseBuilder = caseBuilder;
        this.repository = repository;
    }

    @PostMapping("/run")
    public EvalService.EvalReport run(@RequestBody(required = false) RunRequest request) {
        RunRequest r = request == null
                ? new RunRequest(null, null, null, null) : request;
        return evalService.run(
                new EvalService.EvalRequest(r.suite(), r.provider(), r.model(), r.category()),
                CurrentScope.get());
    }

    public record RunRequest(String suite, String provider, String model, String category) {
    }

    @PostMapping("/retrieval")
    public RetrievalEvalService.RetrievalReport runRetrieval(
            @RequestBody(required = false) RetrievalRunRequest request) {
        RetrievalRunRequest r = request == null
                ? new RetrievalRunRequest(null, null, null, false) : request;
        return retrievalEvalService.run(
                new RetrievalEvalService.RetrievalEvalRequest(
                        r.suite(), r.category(), r.topK(), r.includeRerank()),
                CurrentScope.get());
    }

    public record RetrievalRunRequest(String suite, String category, Integer topK,
                                      boolean includeRerank) {
    }

    @GetMapping("/cases")
    public Map<String, Object> listCases(@RequestParam(required = false) String suite,
                                         @RequestParam(defaultValue = "false") boolean onlyActive) {
        List<EvalRepository.EvalCase> cases = repository.listCases(suite, onlyActive);
        return Map.of(
                "suites", repository.suites(),
                "total", cases.size(),
                "cases", cases.stream().map(this::toMap).toList());
    }

    @PostMapping("/cases")
    public Map<String, Object> addCases(@RequestBody CasesRequest request) {
        if (request.cases() == null || request.cases().isEmpty()) {
            throw new IllegalArgumentException("Danh sach case trong.");
        }
        String suite = request.suite() == null || request.suite().isBlank()
                ? "default" : request.suite().trim();
        List<Long> ids = new ArrayList<>();
        for (CaseInput c : request.cases()) {
            if (c.question() == null || c.question().isBlank()) {
                throw new IllegalArgumentException("Case thieu cau hoi.");
            }
            ids.add(repository.addCase(new EvalRepository.EvalCase(
                    null, suite, c.question().strip(), c.expectedSource(),
                    c.expectedAnswer(), c.category(), true)));
        }
        return Map.of("message", "Đã thêm " + ids.size() + " câu hỏi vào bộ '" + suite + "'.",
                "ids", ids, "suite", suite);
    }

    public record CasesRequest(String suite, List<CaseInput> cases) {
    }

    public record CaseInput(String question, String expectedSource, String expectedAnswer,
                            String category) {
    }

    @DeleteMapping("/cases/{id}")
    public Map<String, Object> deleteCase(@PathVariable long id) {
        int deleted = repository.deleteCase(id);
        return Map.of("deleted", deleted > 0);
    }

    @PostMapping("/cases/generate")
    public Map<String, Object> generateCases(@RequestBody(required = false) GenerateRequest request) {
        GenerateRequest r = request == null ? new GenerateRequest(null, null, null) : request;
        String suite = r.suite() == null || r.suite().isBlank() ? "sinh-tu-tai-lieu" : r.suite();
        int perDocument = r.perDocument() == null || r.perDocument() < 1 ? 5 : r.perDocument();

        EvalCaseBuilder.BuildStatus status =
                caseBuilder.startGenerate(suite, perDocument, r.category());
        return Map.of(
                "message", "Đang sinh câu hỏi từ kho tài liệu. Theo dõi ở GET /eval/cases/build-status.",
                "suite", suite,
                "status", status);
    }

    public record GenerateRequest(String suite, Integer perDocument, String category) {
    }

    @GetMapping("/cases/build-status")
    public EvalCaseBuilder.BuildStatus buildStatus() {
        return caseBuilder.status();
    }

    @PostMapping("/cases/harvest")
    public Map<String, Object> harvest(@RequestBody(required = false) HarvestRequest request) {
        HarvestRequest r = request == null
                ? new HarvestRequest(null, null, null, false) : request;
        int sinceDays = r.sinceDays() == null || r.sinceDays() < 1 ? 90 : r.sinceDays();
        int limit = r.limit() == null || r.limit() < 1 ? 200 : r.limit();

        if (Boolean.TRUE.equals(r.negative())) {
            String suite = r.suite() == null || r.suite().isBlank() ? "can-xem-lai" : r.suite();
            return caseBuilder.harvestNegative(suite, sinceDays, limit);
        }
        String suite = r.suite() == null || r.suite().isBlank() ? "thuc-te" : r.suite();
        return caseBuilder.harvest(suite, sinceDays, limit);
    }

    public record HarvestRequest(String suite, Integer sinceDays, Integer limit, Boolean negative) {
    }

    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(required = false) String suite,
                                    @RequestParam(defaultValue = "20") int limit) {
        return Map.of("runs", repository.listRuns(suite, limit));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> runResults(@PathVariable long runId) {
        return Map.of("runId", runId, "results", repository.results(runId));
    }

    private Map<String, Object> toMap(EvalRepository.EvalCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("suite", c.suite());
        m.put("question", c.question());
        m.put("expectedSource", c.expectedSource());
        m.put("expectedAnswer", c.expectedAnswer());
        m.put("category", c.category());
        m.put("active", c.active());
        return m;
    }
}
