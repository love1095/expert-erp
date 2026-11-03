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
 * Background work for the Shopify link: a small pool that runs received webhooks, a worker that retries failed
 * ones every 30 s and the daily check against Shopify (7.5); stock changes sent every 20 s and a full stock
 * re-send every night (7.6). Tests turn the timers off
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
        private final ShopifyStockSync stockSync;

        @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
        void retryFailedEvents() {
            int ran = processor.runDue();
            if (ran > 0) log.info("Shopify: ran {} waiting webhook events", ran);
        }

        // Whatever stock changed since the last run (receipts, shipments, orders confirmed or cancelled...)
        @Scheduled(initialDelay = 20_000, fixedDelayString = "${app.shopify.stock-every:20000}")
        void sendChangedStock() {
            stockSync.syncAll(false);
        }

        // Every night at 03:45: all of it again, whatever was sent before
        @Scheduled(cron = "${app.shopify.stock-full-cron:0 45 3 * * *}")
        void sendAllStock() {
            stockSync.syncAll(true);
        }

        // Every day at 03:15 (server time)
        @Scheduled(cron = "${app.shopify.reconcile-cron:0 15 3 * * *}")
        void dailyCheck() {
            log.info("Shopify: daily check for {} organisations", reconciler.reconcileAll());
        }
    }
}
