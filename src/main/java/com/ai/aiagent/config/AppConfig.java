package com.ai.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class AppConfig {

    /**
     * Spring Boot tu cau hinh {@code PlatformTransactionManager} nhung KHONG tao
     * {@code TransactionTemplate}. Ta can no de mo transaction tuong minh trong
     * {@code IngestionService} (loi goi noi bo cung class nen {@code @Transactional}
     * se khong duoc proxy chan).
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    /** Pool chay SSE: moi request stream chiem mot luong cho den khi xong. */
    @Bean(name = "sseExecutor")
    public TaskExecutor sseExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("sse-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(200);
        return executor;
    }
}
