package com.experterp.backend.service;

import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.SaleOrderRepository;
import com.experterp.backend.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Organisation-wide settings (order numbering, Shopify sales rep, default VAT) and the sale order number sequence. */
@Service
@RequiredArgsConstructor
public class OrganisationSettingsService {

    private final TenantRepository tenantRepository;
    private final SaleOrderRepository saleOrderRepository;
    private final TaxService taxService;
    private final AuditService auditService;
    private final EntityManager em;

    public record Settings(
            @NotBlank @Size(max = 10) String saleOrderPrefix,
            @Min(1) long nextSaleOrderNumber,
            @NotBlank @Size(max = 100) String webSalesRep,
            @NotBlank @Size(max = 100) String defaultSaleTaxRule) {}

    @Transactional(readOnly = true)
    public Settings get() {
        Tenant t = tenant();
        return new Settings(t.getSaleOrderPrefix(), t.getNextSaleOrderNumber(), t.getWebSalesRep(), t.getDefaultSaleTaxRule());
    }

    @Transactional
    public Settings update(Settings s) {
        // Refuses a default that is not an active sale rate
        String defaultRule = taxService.saleRate(s.defaultSaleTaxRule()).getName();
        Tenant t = em.find(Tenant.class, TenantContext.get(), LockModeType.PESSIMISTIC_WRITE);
        t.setSaleOrderPrefix(s.saleOrderPrefix().trim());
        t.setNextSaleOrderNumber(s.nextSaleOrderNumber());
        t.setWebSalesRep(s.webSalesRep().trim());
        t.setDefaultSaleTaxRule(defaultRule);
        auditService.log("ORGANISATION", t.getId(), "UPDATE", Map.of(
                "saleOrderPrefix", t.getSaleOrderPrefix(), "nextSaleOrderNumber", t.getNextSaleOrderNumber(),
                "webSalesRep", t.getWebSalesRep(), "defaultSaleTaxRule", defaultRule));
        return get();
    }

    /** Takes the next sale order number (e.g. SO-66006), skipping numbers already used; locks the organisation row. */
    @Transactional
    public String nextSaleOrderNumber() {
        Tenant t = em.find(Tenant.class, TenantContext.get(), LockModeType.PESSIMISTIC_WRITE);
        while (true) {
            long n = t.getNextSaleOrderNumber();
            t.setNextSaleOrderNumber(n + 1);
            String number = t.getSaleOrderPrefix() + n;
            if (!saleOrderRepository.existsByOrderNumber(number)) return number;
        }
    }

    public String webSalesRep() {
        return tenant().getWebSalesRep();
    }

    private Tenant tenant() {
        return tenantRepository.findById(TenantContext.get())
                .orElseThrow(() -> new IllegalStateException("No organisation for this request"));
    }
}
