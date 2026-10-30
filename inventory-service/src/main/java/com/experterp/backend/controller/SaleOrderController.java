package com.experterp.backend.controller;

import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.OrderInvoiceRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.dto.SaleReturnRequest;
import com.experterp.backend.service.InvoiceService;
import com.experterp.backend.service.SaleOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sale-orders")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SaleOrderController {

    private final SaleOrderService saleOrderService;
    private final InvoiceService invoiceService;

    @GetMapping
    public Page<SaleOrderResponse> findAll(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) com.experterp.backend.entity.SaleOrder.Status status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return saleOrderService.findAll(customerId, status, q, page, size);
    }

    // Quotes and draft orders (not Shopify ones) can be changed; VAT and totals are worked out again
    @PutMapping("/{id}")
    public SaleOrderResponse update(@PathVariable Long id, @Valid @RequestBody SaleOrderRequest req) {
        return saleOrderService.update(id, req);
    }

    // Invoice for (part of) the order: the given quantities, or everything not invoiced yet
    @PostMapping("/{id}/invoices")
    public ResponseEntity<InvoiceResponse> invoice(@PathVariable Long id, @Valid @RequestBody(required = false) OrderInvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceService.invoiceOrder(id, req != null ? req : new OrderInvoiceRequest()));
    }

    @PostMapping("/{id}/convert")
    public SaleOrderResponse convertQuote(@PathVariable Long id) {
        return saleOrderService.convertQuote(id);
    }

    @PostMapping("/{id}/reject")
    public SaleOrderResponse rejectQuote(@PathVariable Long id) {
        return saleOrderService.rejectQuote(id);
    }

    @GetMapping("/{id}")
    public SaleOrderResponse findById(@PathVariable Long id) {
        return saleOrderService.findById(id);
    }

    @PostMapping
    public ResponseEntity<SaleOrderResponse> create(@Valid @RequestBody SaleOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(saleOrderService.create(req));
    }

    // The order as it would be created (rep, VAT, totals), nothing saved; for the order form
    @PostMapping("/preview")
    public SaleOrderResponse preview(@RequestBody SaleOrderRequest req) {
        return saleOrderService.preview(req);
    }

    @PostMapping("/{id}/confirm")
    public SaleOrderResponse confirm(@PathVariable Long id) {
        return saleOrderService.confirm(id);
    }

    // No body = ship everything remaining
    @PostMapping("/{id}/fulfill")
    public SaleOrderResponse fulfill(@PathVariable Long id,
                                     @Valid @RequestBody(required = false) FulfilmentRequest req) {
        return saleOrderService.fulfill(id, req);
    }

    @PostMapping("/{id}/returns")
    public SaleOrderResponse createReturn(@PathVariable Long id, @Valid @RequestBody SaleReturnRequest req) {
        return saleOrderService.createReturn(id, req);
    }

    @PostMapping("/{id}/cancel")
    public SaleOrderResponse cancel(@PathVariable Long id) {
        return saleOrderService.cancel(id);
    }
}
