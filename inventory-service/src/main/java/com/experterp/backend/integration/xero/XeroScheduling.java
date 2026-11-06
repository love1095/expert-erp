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

    @Scheduled(initialDelay = 40_000, fixedDelayString = "${app.xero.sync-every:30000}")
    void sendDue() {
        int sent = sync.processAll();
        if (sent > 0) log.info("Xero: sent {} items", sent);
    }
}
