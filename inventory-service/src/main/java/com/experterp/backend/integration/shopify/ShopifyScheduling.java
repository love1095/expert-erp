package com.experterp.backend.integration.shopify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Background work for the Shopify link (7.5): a small pool that runs received webhooks, a worker that retries
 * failed ones every 30 s, and the daily check against Shopify. Tests turn the timers off
 * (app.shopify.scheduler.enabled=false) and run webhooks straight away (app.shopify.events.inline=true).
 */
@Configuration
@EnableScheduling
public class ShopifyScheduling {

    @Bean("shopifyEventExecutor")
    TaskExecutor shopifyEventExecutor(@Value("${app.shopify.events.inline:false}") boolean inline) {
        if (inline) return new SyncTaskExecutor();
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setThreadNamePrefix("shopify-event-");
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        // Beyond this the worker picks events up from the database
        pool.setQueueCapacity(1000);
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(20);
        pool.initialize();
        return pool;
    }

    @Slf4j
    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(name = "app.shopify.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class Timers {

        private final ShopifyEventProcessor processor;
        private final ShopifyReconciler reconciler;

        @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
        void retryFailedEvents() {
            int ran = processor.runDue();
            if (ran > 0) log.info("Shopify: ran {} waiting webhook events", ran);
        }

        // Every day at 03:15 (server time)
        @Scheduled(cron = "${app.shopify.reconcile-cron:0 15 3 * * *}")
        void dailyCheck() {
            log.info("Shopify: daily check for {} organisations", reconciler.reconcileAll());
        }
    }
}
