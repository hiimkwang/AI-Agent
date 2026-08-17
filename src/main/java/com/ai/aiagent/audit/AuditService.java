package com.ai.aiagent.audit;

import com.ai.aiagent.config.RagProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ghi nhat ky thao tac.
 *
 * GHI O LUONG NEN: mot lan INSERT khong duoc phep cong them do tre vao thao tac
 * cua nguoi dung, va cang khong duoc lam hong thao tac do khi DB cham.
 *
 * NHUNG khi hang doi day thi GHI THANG trong luong request thay vi vut bo. Day la
 * khac biet co chu dinh so voi cac buoc phu tro khac cua he thong (rewrite, HyDE,
 * cache - nhung thu duoc phep that bai im lang): mot nhat ky kiem toan mat dong
 * ngay luc he thong dang qua tai la mat dung doan can nhat. Tha cham con hon thung.
 */
@Service
@Slf4j
public class AuditService {

    /** Du lon de nuot cac cum thao tac hang loat, du nho de khong an nhieu bo nho. */
    private static final int QUEUE_CAPACITY = 10_000;

    private final AuditRepository repository;
    private final RagProperties props;
    private final BlockingQueue<AuditEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread writer;
    private volatile boolean running = true;

    public AuditService(AuditRepository repository, RagProperties props) {
        this.repository = repository;
        this.props = props;
        this.writer = new Thread(this::drainLoop, "audit-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public void record(AuditEvent event) {
        if (!props.getAudit().isEnabled()) return;
        if (queue.offer(event)) return;

        // Hang doi day: ghi dong bo. Cham hon nhung khong mat vet.
        log.warn("Audit: hang doi day ({} muc), ghi dong bo.", QUEUE_CAPACITY);
        writeSafely(event);
    }

    private void drainLoop() {
        while (running) {
            try {
                AuditEvent event = queue.poll(1, TimeUnit.SECONDS);
                if (event != null) writeSafely(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Vong lap ghi khong duoc chet - neu chet thi tu do tro di khong con
                // nhat ky nao ma khong ai biet.
                log.error("Audit: loi trong vong lap ghi", e);
            }
        }
    }

    private void writeSafely(AuditEvent event) {
        try {
            repository.insert(event);
        } catch (RuntimeException e) {
            // Khong nem ra ngoai: mot loi ghi nhat ky khong duoc lam hong thao tac
            // that. Nhung PHAI la ERROR de con nhin thay tren giam sat.
            log.error("Audit: khong ghi duoc nhat ky cho '{}' cua {}: {}",
                    event.action(), event.actorUpn(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        writer.interrupt();
        // Vet con lai trong hang doi: ghi not truoc khi tat, khong bo.
        AuditEvent remaining;
        int flushed = 0;
        while ((remaining = queue.poll()) != null) {
            writeSafely(remaining);
            flushed++;
        }
        if (flushed > 0) log.info("Audit: da ghi not {} muc truoc khi tat.", flushed);
    }

    /** So muc dang cho ghi - hien o man quan tri de biet co dang un khong. */
    public int pending() {
        return queue.size();
    }
}
