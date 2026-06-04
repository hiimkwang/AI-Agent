package com.ai.aiagent.modules.rag.eval;

import com.ai.aiagent.modules.rag.eval.EvalDtos.EvalReport;
import com.ai.aiagent.modules.rag.eval.EvalDtos.EvalRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * API đánh giá chất lượng RAG.
 *
 * POST /api/v1/rag/eval
 * {
 *   "provider": "OPENAI",            // tùy chọn
 *   "model": "gpt-4o-mini",          // tùy chọn
 *   "cases": [
 *     { "question": "Quy trình nghỉ phép?", "expectedSource": "noi-quy.docx" },
 *     { "question": "Mức phụ cấp ăn trưa?" }
 *   ]
 * }
 */
@RestController
@RequestMapping("/api/v1/rag/eval")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping
    public ResponseEntity<EvalReport> evaluate(@RequestBody EvalRequest request) {
        return ResponseEntity.ok(evalService.run(request));
    }
}
