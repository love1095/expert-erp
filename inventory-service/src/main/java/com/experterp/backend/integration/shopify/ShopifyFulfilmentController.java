package com.experterp.backend.integration.shopify;

import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.FulfilmentRepository;
import com.experterp.backend.service.SaleOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Retry sending a shipment to Shopify (anyone who can ship orders may retry). */
@RestController
@RequestMapping("/api/sale-orders")
@RequiredArgsConstructor
public class ShopifyFulfilmentController {

    private final FulfilmentRepository fulfilmentRepository;
    private final ShopifyFulfilmentSync sync;
    private final SaleOrderService saleOrderService;

    @PostMapping("/{id}/fulfilments/{fulfilmentId}/shopify-sync")
    public SaleOrderResponse retry(@PathVariable Long id, @PathVariable Long fulfilmentId) {
        // Tenant-filtered lookup, and the shipment must belong to this order
        boolean belongs = fulfilmentRepository.findById(fulfilmentId)
                .map(f -> f.getSaleOrder().getId().equals(id)).orElse(false);
        if (!belongs) throw new ResourceNotFoundException("Shipment " + fulfilmentId + " not found on sale order " + id);
        sync.push(fulfilmentId);
        return saleOrderService.findById(id);
    }
}
