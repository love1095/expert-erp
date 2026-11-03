package com.experterp.backend.config;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.service.CostLayerRebuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** At start-up, builds the FIFO cost layers of organisations whose stock history predates costing (phase E5). */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class CostLayerBackfill implements ApplicationRunner {

    private final CostLayerRebuildService rebuildService;

    @Override
    public void run(ApplicationArguments args) {
        for (Long tenantId : rebuildService.tenantsToBuild()) {
            TenantContext.set(tenantId);
            try {
                rebuildService.rebuild();
            } catch (RuntimeException e) {
                log.error("FIFO cost layers could not be built for organisation {}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
