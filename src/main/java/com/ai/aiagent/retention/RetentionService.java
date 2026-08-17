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

/**
 * Don du lieu qua han theo lich.
 *
 * Truoc ban nay he thong CHI co duong xoa thu cong ({@code DELETE /admin/conversations/purge}),
 * nghia la tren thuc te cau hoi va cau tra loi cua nhan vien duoc luu VO THOI HAN -
 * khong ai nho vao goi endpoint do hang thang. Mot chinh sach luu tru chi ton tai khi
 * no tu chay.
 *
 * Ba nhom du lieu, ba thoi han KHAC NHAU va do la co y:
 *   - hoi thoai: ngan nhat. Day la du lieu ca nhan, giu lau khong loi ich gi.
 *   - job nap lieu: trung binh. Chi de tra cuu su co gan day.
 *   - nhat ky kiem toan: dai nhat (mac dinh 2 nam). Day la thu de giai trinh, khong
 *     phai du lieu van hanh.
 *
 * KHONG dong toi {@code rag_documents}/{@code rag_chunks}: tai lieu het hieu luc da
 * duoc loc luc truy xuat ({@code rag.retrieval.exclude-expired}), va xoa tu dong tai
 * lieu goc la thu khong bao gio nen lam ngam.
 */
@Service
@Slf4j
public class RetentionService {

    private final RagProperties props;
    private final ConversationRepository conversations;
    private final AuditRepository audit;
    private final JobRepository jobs;

    /** Ket qua lan chay gan nhat - hien o {@code /admin/overview} de biet no co chay khong. */
    private volatile Map<String, Object> lastRun = Map.of("state", "chua chay lan nao");

    public RetentionService(RagProperties props, ConversationRepository conversations,
                            AuditRepository audit, JobRepository jobs) {
        this.props = props;
        this.conversations = conversations;
        this.audit = audit;
        this.jobs = jobs;
    }

    /**
     * Kiem tra moi gio, chi lam viec dung gio da hen.
     *
     * Dung cron cua Spring thay vi {@code fixedDelay} de gio chay khong bi troi dan sau
     * moi lan restart - mot job don du lieu chay vao gio cao diem la thu nen tranh.
     */
    @Scheduled(cron = "0 5 * * * *")
    public void hourlyCheck() {
        RagProperties.Retention config = props.getRetention();
        if (!config.isEnabled()) return;
        if (LocalDateTime.now().getHour() != config.getRunAtHour()) return;
        run();
    }

    /** Chay ngay, khong doi lich. Duoc goi tu {@code RetentionController}. */
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

        log.info("Don du lieu qua han: hoi thoai={} (>{}n), nhat ky={} (>{}n), job={} (>{}n).",
                result.get("conversations"), config.getConversationDays(),
                result.get("auditEntries"), config.getAuditDays(),
                result.get("jobs"), config.getJobDays());
        return result;
    }

    public Map<String, Object> lastRun() {
        return lastRun;
    }

    /**
     * Moi nhom xoa doc lap nhau.
     *
     * Loi o mot nhom KHONG duoc lam dung ca lan don: neu bang nhat ky co van de thi
     * hoi thoai qua han van phai duoc xoa - do la nghia vu voi du lieu ca nhan, khong
     * phai tinh nang "co thi tot".
     *
     * @return so ban ghi da xoa, {@code -1} khi loi, {@code 0} khi thoi han bi tat
     */
    private int purge(String what, int days, PurgeAction action) {
        if (days <= 0) return 0;
        try {
            return action.run();
        } catch (RuntimeException e) {
            log.error("Don du lieu qua han: loi khi xoa {}: {}", what, e.getMessage());
            return -1;
        }
    }

    @FunctionalInterface
    private interface PurgeAction {
        int run();
    }
}
