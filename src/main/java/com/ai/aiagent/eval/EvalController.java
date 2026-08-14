package com.ai.aiagent.eval;

import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.store.EvalRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API danh gia chat luong (chi ADMIN).
 *
 * Quy trinh dung: them case vao bo cau hoi chuan mot lan -> moi lan doi tham so
 * (top-k, chunk-size, model, nguong tu choi) thi chay lai cung bo do -> so sanh diem
 * giua cac lan chay. Do la cach duy nhat de biet mot thay doi lam tot len hay xau di.
 */
@RestController
@RequestMapping("/api/v1/rag/eval")
public class EvalController {

    private final EvalService evalService;
    private final EvalRepository repository;

    public EvalController(EvalService evalService, EvalRepository repository) {
        this.evalService = evalService;
        this.repository = repository;
    }

    /** Chay bo cau hoi chuan da luu trong DB. */
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

    // ------------------------------------------------------------ Cases

    @GetMapping("/cases")
    public Map<String, Object> listCases(@RequestParam(required = false) String suite,
                                         @RequestParam(defaultValue = "false") boolean onlyActive) {
        List<EvalRepository.EvalCase> cases = repository.listCases(suite, onlyActive);
        return Map.of(
                "suites", repository.suites(),
                "total", cases.size(),
                "cases", cases.stream().map(this::toMap).toList());
    }

    /** Them mot hoac nhieu cau hoi chuan. */
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

    // ------------------------------------------------------------- Runs

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
