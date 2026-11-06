package com.experterp.backend.integration.xero;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Phase L2: the Xero queue runs in the background (every 30 s by default). Tests turn it off and call the queue. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.xero.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class XeroScheduling {

    private final XeroSyncService sync;
    private final XeroPaymentPuller puller;

    @Scheduled(initialDelay = 40_000, fixedDelayString = "${app.xero.sync-every:30000}")
    void sendDue() {
        int sent = sync.processAll();
        if (sent > 0) log.info("Xero: sent {} items", sent);
    }

    // L4: payments entered in Xero, polled every 5 minutes …
    @Scheduled(initialDelay = 60_000, fixedDelayString = "${app.xero.pull-every:300000}")
    void pullPayments() {
        int n = puller.pullAll(true);
        if (n > 0) log.info("Xero: {} payments recorded from Xero", n);
    }

    // … and within 30 s when a Xero webhook said something changed
    @Scheduled(initialDelay = 45_000, fixedDelay = 30_000)
    void pullRequested() {
        puller.pullAll(false);
    }
}
