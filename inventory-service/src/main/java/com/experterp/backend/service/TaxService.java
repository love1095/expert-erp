package com.experterp.backend.service;

import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.TaxRate;
import com.experterp.backend.entity.TaxRate.Usage;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TaxRateRepository;
import com.experterp.backend.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The organisation's VAT rates and which one applies (docs/business-rules.md §3): for a sale line, the product's
 * sale tax rule, else the customer's tax rule, else the organisation default — the first that names an active
 * rate. An explicit choice on the line (checked by the caller) comes before all of them.
 */
@Service
@RequiredArgsConstructor
public class TaxService {

    // UK rates, named as in Cin7 / Xero so imported tax rules match by name
    static List<TaxRate> ukDefaults() {
        return List.of(
                new TaxRate("20% (VAT on Income)", "20", Usage.SALE),
                new TaxRate("5% (VAT on Income)", "5", Usage.SALE),
                new TaxRate("Zero Rated Income", "0", Usage.SALE),
                new TaxRate("Exempt Income", "0", Usage.SALE),
                new TaxRate("No VAT", "0", Usage.SALE),
                new TaxRate("20% (VAT on Expenses)", "20", Usage.PURCHASE),
                new TaxRate("5% (VAT on Expenses)", "5", Usage.PURCHASE),
                new TaxRate("Zero Rated Expenses", "0", Usage.PURCHASE),
                new TaxRate("Exempt Expenses", "0", Usage.PURCHASE),
                new TaxRate("Reverse Charge Expenses (20%)", "20", Usage.PURCHASE),
                new TaxRate("No VAT", "0", Usage.PURCHASE));
    }

    private final TaxRateRepository taxRateRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public List<TaxRate> rates(Usage usage) {
        ensureDefaults();
        return taxRateRepository.findByUsageOrderByRateDescNameAsc(usage);
    }

    /** The active sale rate with this name; unknown or inactive names are refused. */
    @Transactional
    public TaxRate saleRate(String name) {
        ensureDefaults();
        return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown tax rule: " + name));
    }

    /** Product's rule, else customer's, else the organisation default (product and customer may be null). */
    @Transactional
    public TaxRate resolveSale(Product product, Customer customer) {
        ensureDefaults();
        List<String> names = new ArrayList<>();
        if (product != null && product.getDetails() != null) names.add(product.getDetails().getSaleTaxRule());
        if (customer != null) names.add(customer.getTaxRule());
        names.add(defaultSaleTaxRule());
        for (String name : names) {
            Optional<TaxRate> rate = find(name);
            if (rate.isPresent()) return rate.get();
        }
        throw new IllegalStateException("The default sale tax rule '" + defaultSaleTaxRule() + "' is not an active rate");
    }

    private Optional<TaxRate> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return taxRateRepository.findFirstByUsageAndNameIgnoreCaseAndActiveTrue(Usage.SALE, name.trim());
    }

    private String defaultSaleTaxRule() {
        return tenantRepository.findById(TenantContext.get()).map(Tenant::getDefaultSaleTaxRule)
                .orElse("20% (VAT on Income)");
    }

    // Organisations created after the V12 migration get the UK rates on first use
    private void ensureDefaults() {
        if (taxRateRepository.count() == 0) taxRateRepository.saveAll(ukDefaults());
    }
}
