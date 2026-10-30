package com.experterp.backend.service;

import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.InvoiceRepository;
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
    private final InvoiceRepository invoiceRepository;
    private final TaxService taxService;
    private final AuditService auditService;
    private final EntityManager em;

    public record Settings(
            @NotBlank @Size(max = 10) String saleOrderPrefix,
            @Min(1) long nextSaleOrderNumber,
            @NotBlank @Size(max = 100) String webSalesRep,
            @NotBlank @Size(max = 100) String defaultSaleTaxRule,
            @Min(1) @jakarta.validation.constraints.Max(365) int quoteValidDays,
            @NotBlank @Size(max = 10) String invoicePrefix,
            @Min(1) long nextInvoiceNumber,
            boolean autoInvoiceOnShipment,
            @NotBlank @Size(max = 10) String creditNotePrefix,
            @Min(1) long nextCreditNoteNumber) {}

    @Transactional(readOnly = true)
    public Settings get() {
        Tenant t = tenant();
        return new Settings(t.getSaleOrderPrefix(), t.getNextSaleOrderNumber(), t.getWebSalesRep(), t.getDefaultSaleTaxRule(),
                t.getQuoteValidDays(), t.getInvoicePrefix(), t.getNextInvoiceNumber(), t.isAutoInvoiceOnShipment(),
                t.getCreditNotePrefix(), t.getNextCreditNoteNumber());
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
        t.setQuoteValidDays(s.quoteValidDays());
        t.setInvoicePrefix(s.invoicePrefix().trim());
        t.setNextInvoiceNumber(s.nextInvoiceNumber());
        t.setAutoInvoiceOnShipment(s.autoInvoiceOnShipment());
        t.setCreditNotePrefix(s.creditNotePrefix().trim());
        t.setNextCreditNoteNumber(s.nextCreditNoteNumber());
        auditService.log("ORGANISATION", t.getId(), "UPDATE", Map.of(
                "saleOrderPrefix", t.getSaleOrderPrefix(), "nextSaleOrderNumber", t.getNextSaleOrderNumber(),
                "webSalesRep", t.getWebSalesRep(), "defaultSaleTaxRule", defaultRule, "quoteValidDays", s.quoteValidDays(),
                "invoicePrefix", t.getInvoicePrefix(), "nextInvoiceNumber", t.getNextInvoiceNumber(),
                "autoInvoiceOnShipment", t.isAutoInvoiceOnShipment(),
                "creditNotePrefix", t.getCreditNotePrefix(), "nextCreditNoteNumber", t.getNextCreditNoteNumber()));
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

    /** Takes the next invoice number (e.g. INV-118554), skipping numbers already used; locks the organisation row. */
    @Transactional
    public String nextInvoiceNumber() {
        Tenant t = em.find(Tenant.class, TenantContext.get(), LockModeType.PESSIMISTIC_WRITE);
        while (true) {
            long n = t.getNextInvoiceNumber();
            t.setNextInvoiceNumber(n + 1);
            String number = t.getInvoicePrefix() + n;
            if (!invoiceRepository.existsByInvoiceNumber(number)) return number;
        }
    }

    /** Takes the next credit note number, zero-padded to 5 digits as in Cin7 (e.g. CR-00232); numbers in use skipped. */
    @Transactional
    public String nextCreditNoteNumber() {
        Tenant t = em.find(Tenant.class, TenantContext.get(), LockModeType.PESSIMISTIC_WRITE);
        while (true) {
            long n = t.getNextCreditNoteNumber();
            t.setNextCreditNoteNumber(n + 1);
            String number = t.getCreditNotePrefix() + String.format("%05d", n);
            if (!invoiceRepository.existsByInvoiceNumber(number)) return number;
        }
    }

    public boolean autoInvoiceOnShipment() {
        return tenant().isAutoInvoiceOnShipment();
    }

    public String webSalesRep() {
        return tenant().getWebSalesRep();
    }

    public int quoteValidDays() {
        return tenant().getQuoteValidDays();
    }

    private Tenant tenant() {
        return tenantRepository.findById(TenantContext.get())
                .orElseThrow(() -> new IllegalStateException("No organisation for this request"));
    }
}
