package com.experterp.backend.integration.shopify;

import com.experterp.backend.repository.ShopifyProductLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** Product page: what Shopify was last told about this product's stock (7.6). 204 when it is not in Shopify. */
@RestController
@RequiredArgsConstructor
public class ShopifyProductStockController {

    private final ShopifyProductLinkRepository linkRepository;
    private final ShopifyStockSync stockSync;

    public record ShopifyStock(Integer sent, LocalDateTime sentAt, int available, boolean upToDate) {}

    @GetMapping("/api/products/{id}/shopify-stock")
    public ResponseEntity<ShopifyStock> get(@PathVariable Long id) {
        return linkRepository.findByProductId(id).map(link -> {
            int available = stockSync.wanted(id).stream().findFirst()
                    .map(ShopifyStockSync.Want::available).orElse(0);
            return ResponseEntity.ok(new ShopifyStock(link.getStockSent(), link.getStockSentAt(), available,
                    link.getStockSent() != null && link.getStockSent() == available));
        }).orElse(ResponseEntity.noContent().build());
    }
}
