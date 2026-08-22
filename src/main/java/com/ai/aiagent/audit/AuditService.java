package com.ai.aiagent.audit;

import com.ai.aiagent.config.RagProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AuditService {

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

        log.warn("Audit queue full ({} entries), falling back to a synchronous write.",
                    QUEUE_CAPACITY);
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
                log.error("Audit writer loop failed", e);
            }
        }
    }

    private void writeSafely(AuditEvent event) {
        try {
            repository.insert(event);
        } catch (RuntimeException e) {
            log.error("Could not persist the audit entry for '{}' by {}: {}",
                    event.action(), event.actorUpn(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        writer.interrupt();
        AuditEvent remaining;
        int flushed = 0;
        while ((remaining = queue.poll()) != null) {
            writeSafely(remaining);
            flushed++;
        }
        if (flushed > 0) log.info("Flushed {} pending audit entries during shutdown.", flushed);
    }

    public int pending() {
        return queue.size();
    }
}
