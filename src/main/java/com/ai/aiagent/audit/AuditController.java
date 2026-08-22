package com.ai.aiagent.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class AuditController {

    private final AuditRepository repository;
    private final AuditService service;

    public AuditController(AuditRepository repository, AuditService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping("/audit")
    public Map<String, Object> search(@RequestParam(required = false) String actor,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) Boolean deniedOnly,
                                      @RequestParam(defaultValue = "30") int days,
                                      @RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        List<Map<String, Object>> rows = repository.search(actor, action, deniedOnly,
                days, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("limit", limit);
        out.put("offset", offset);
        out.put("returned", rows.size());
        out.put("pendingWrites", service.pending());
        out.put("entries", rows);
        return out;
    }

    @GetMapping("/audit/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>(repository.summary(days));
        out.put("totalAllTime", repository.count());
        out.put("pendingWrites", service.pending());
        return out;
    }
}
