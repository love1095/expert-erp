package com.experterp.backend.integration.shopify;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Refunds made in Shopify on a sale order, to review (7.4). */
@RestController
@RequestMapping("/api/sale-orders")
@RequiredArgsConstructor
public class ShopifyRefundController {

    private final ShopifyRefundService service;

    @GetMapping("/{id}/shopify-refunds")
    public List<ShopifyRefundService.RefundView> list(@PathVariable Long id) {
        return service.forOrder(id);
    }

    // Credit note (and restock) / cancel the unshipped quantities, as the refund says
    @PostMapping("/{id}/shopify-refunds/{refundId}/apply")
    public ShopifyRefundService.RefundView apply(@PathVariable Long id, @PathVariable Long refundId,
                                                 @RequestBody(required = false) ShopifyRefundService.ApplyRequest req) {
        return service.apply(id, refundId, req);
    }

    @PostMapping("/{id}/shopify-refunds/{refundId}/dismiss")
    public ShopifyRefundService.RefundView dismiss(@PathVariable Long id, @PathVariable Long refundId,
                                                   @RequestBody Map<String, String> body) {
        return service.dismiss(id, refundId, body.get("reason"));
    }
}
