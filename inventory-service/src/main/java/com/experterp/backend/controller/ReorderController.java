package com.experterp.backend.controller;

import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.ReorderRequest;
import com.experterp.backend.service.DashboardService;
import com.experterp.backend.service.PurchaseOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The reorder screen (Cin7's Purchase → Reorder): products at or below their minimum, by supplier, raised as POs in bulk. */
@RestController
@RequestMapping("/api/reorder")
@RequiredArgsConstructor
public class ReorderController {

    private final DashboardService dashboardService;
    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    public Page<DashboardResponse.ReorderLine> list(
            @RequestParam(required = false) String search,
            // A supplier's products; noSupplier=true for products without one; neither = all
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "false") boolean noSupplier,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 500) throw new IllegalArgumentException("page must be ≥ 0 and size 1–500");
        return dashboardService.reorder(search, supplierId, noSupplier, page, size);
    }

    @GetMapping("/suppliers")
    public List<DashboardResponse.ReorderSupplier> suppliers() {
        return dashboardService.reorderSuppliers();
    }

    // One draft PO per supplier for the chosen products
    @PostMapping("/purchase-orders")
    public ResponseEntity<List<PurchaseOrderResponse>> raise(@Valid @RequestBody ReorderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrderService.createFromReorder(req));
    }
}
