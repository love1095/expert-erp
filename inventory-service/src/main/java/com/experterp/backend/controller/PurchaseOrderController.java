package com.experterp.backend.controller;

import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.service.PurchaseOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    public Page<PurchaseOrderResponse> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) com.experterp.backend.entity.PurchaseOrder.Status status,
            @RequestParam(required = false) String q) {
        return purchaseOrderService.findAll(page, size, status, q);
    }

    @GetMapping("/{id}")
    public PurchaseOrderResponse findById(@PathVariable Long id) {
        return purchaseOrderService.findById(id);
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody PurchaseOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrderService.create(req));
    }

    // Defaults, VAT and totals for the form, nothing saved
    @PostMapping("/preview")
    public PurchaseOrderResponse preview(@Valid @RequestBody PurchaseOrderRequest req) {
        return purchaseOrderService.preview(req);
    }

    @PutMapping("/{id}")
    public PurchaseOrderResponse update(@PathVariable Long id, @Valid @RequestBody PurchaseOrderRequest req) {
        return purchaseOrderService.update(id, req);
    }

    // Placed with the supplier (Cin7's Authorise)
    @PostMapping("/{id}/order")
    public PurchaseOrderResponse markOrdered(@PathVariable Long id) {
        return purchaseOrderService.markOrdered(id);
    }

    // Everything still outstanding
    @PostMapping("/{id}/receive")
    public PurchaseOrderResponse receive(@PathVariable Long id) {
        return purchaseOrderService.receive(id);
    }

    // A receipt: the quantities that arrived (more than outstanding is accepted and flagged)
    @PostMapping("/{id}/receipts")
    public PurchaseOrderResponse receipt(@PathVariable Long id, @Valid @RequestBody com.experterp.backend.dto.PurchaseReceiptRequest req) {
        return purchaseOrderService.receive(id, req);
    }

    // Nothing more is coming: the rest is closed short
    @PostMapping("/{id}/close-short")
    public PurchaseOrderResponse closeShort(@PathVariable Long id) {
        return purchaseOrderService.closeShort(id);
    }

    @PostMapping("/{id}/cancel")
    public PurchaseOrderResponse cancel(@PathVariable Long id) {
        return purchaseOrderService.cancel(id);
    }
}
