package com.experterp.backend.controller;

import com.experterp.backend.service.AuditService;
import com.experterp.backend.service.CostLayerRebuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** FIFO costing (phase E5). */
@RestController
@RequestMapping("/api/costing")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CostingController {

    private final CostLayerRebuildService rebuildService;
    private final AuditService auditService;

    // Builds the cost layers again by replaying the stock history and the supplier bills in order
    @PostMapping("/rebuild")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public CostLayerRebuildService.Result rebuild() {
        CostLayerRebuildService.Result r = rebuildService.rebuild();
        auditService.log("COSTING", 0L, "REBUILD", Map.of("movements", r.movements(), "bills", r.bills(),
                "layers", r.layers(), "stockValue", r.stockValue()));
        return r;
    }
}
