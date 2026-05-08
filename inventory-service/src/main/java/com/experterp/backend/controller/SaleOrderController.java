package com.experterp.backend.controller;

import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
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

    @GetMapping
    public Page<SaleOrderResponse> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return saleOrderService.findAll(page, size);
    }

    @GetMapping("/{id}")
    public SaleOrderResponse findById(@PathVariable Long id) {
        return saleOrderService.findById(id);
    }

    @PostMapping
    public ResponseEntity<SaleOrderResponse> create(@Valid @RequestBody SaleOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(saleOrderService.create(req));
    }

    @PostMapping("/{id}/fulfill")
    public SaleOrderResponse fulfill(@PathVariable Long id) {
        return saleOrderService.fulfill(id);
    }

    @PostMapping("/{id}/cancel")
    public SaleOrderResponse cancel(@PathVariable Long id) {
        return saleOrderService.cancel(id);
    }
}
