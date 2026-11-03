package com.experterp.backend.controller;

import com.experterp.backend.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Price tiers (Settings → Organisation) and customers' contract prices (phase G1). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    @GetMapping("/settings/price-tiers")
    public List<PricingService.TierView> tiers() {
        return pricingService.tiers();
    }

    @PutMapping("/settings/price-tiers")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public List<PricingService.TierView> updateTiers(@RequestBody List<PricingService.TierRequest> tiers) {
        return pricingService.updateTiers(tiers);
    }

    @GetMapping("/customers/{id}/contract-prices")
    public List<PricingService.ContractPriceView> contractPrices(@PathVariable Long id) {
        return pricingService.contractPrices(id);
    }

    @PostMapping("/customers/{id}/contract-prices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SUPER_ADMIN')")
    public PricingService.ContractPriceView addContractPrice(@PathVariable Long id, @RequestBody PricingService.ContractPriceRequest req) {
        return pricingService.addContractPrice(id, req);
    }

    @PutMapping("/customers/{id}/contract-prices/{priceId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SUPER_ADMIN')")
    public PricingService.ContractPriceView updateContractPrice(@PathVariable Long id, @PathVariable Long priceId,
                                                               @RequestBody PricingService.ContractPriceRequest req) {
        return pricingService.updateContractPrice(id, priceId, req);
    }

    @DeleteMapping("/customers/{id}/contract-prices/{priceId}")
    public ResponseEntity<Void> deleteContractPrice(@PathVariable Long id, @PathVariable Long priceId) {
        pricingService.deleteContractPrice(id, priceId);
        return ResponseEntity.noContent().build();
    }
}
