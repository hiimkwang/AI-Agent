package com.ai.aiagent.retention;

import com.ai.aiagent.audit.AuditRepository;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.store.ConversationRepository;
import com.ai.aiagent.store.JobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
public class RetentionService {

    private final RagProperties props;
    private final ConversationRepository conversations;
    private final AuditRepository audit;
    private final JobRepository jobs;

    private volatile Map<String, Object> lastRun = Map.of("state", "chua chay lan nao");

    public RetentionService(RagProperties props, ConversationRepository conversations,
                            AuditRepository audit, JobRepository jobs) {
        this.props = props;
        this.conversations = conversations;
        this.audit = audit;
        this.jobs = jobs;
    }

    @Scheduled(cron = "0 5 * * * *")
    public void hourlyCheck() {
        RagProperties.Retention config = props.getRetention();
        if (!config.isEnabled()) return;
        if (LocalDateTime.now().getHour() != config.getRunAtHour()) return;
        run();
    }

    public Map<String, Object> run() {
        RagProperties.Retention config = props.getRetention();
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("conversations", purge("hoi thoai", config.getConversationDays(),
                () -> conversations.purgeInactiveOlderThanDays(config.getConversationDays())));
        result.put("auditEntries", purge("nhat ky kiem toan", config.getAuditDays(),
                () -> audit.purgeOlderThanDays(config.getAuditDays())));
        result.put("jobs", purge("job nap lieu", config.getJobDays(),
                () -> jobs.purgeFinishedOlderThanDays(config.getJobDays())));

        result.put("durationMs", System.currentTimeMillis() - start);
        result.put("finishedAt", java.time.OffsetDateTime.now().toString());
        lastRun = result;

        log.info("Retention sweep: conversations={} (>{}d), audit entries={} (>{}d), "
                        + "jobs={} (>{}d).",
                result.get("conversations"), config.getConversationDays(),
                result.get("auditEntries"), config.getAuditDays(),
                result.get("jobs"), config.getJobDays());
        return result;
    }

    public Map<String, Object> lastRun() {
        return lastRun;
    }

    private int purge(String what, int days, PurgeAction action) {
        if (days <= 0) return 0;
        try {
            return action.run();
        } catch (RuntimeException e) {
            log.error("Retention sweep failed while deleting {}: {}", what, e.getMessage());
            return -1;
        }
    }

    @FunctionalInterface
    private interface PurgeAction {
        int run();
    }
}
