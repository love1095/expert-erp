package com.experterp.backend.service;

import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.entity.TaxRate;
import com.experterp.backend.entity.TaxRate.Usage;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TaxRateRepository;
import com.experterp.backend.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The organisation's VAT rates and which one applies (docs/business-rules.md §3): for a sale line, the product's
 * sale tax rule, else the customer's tax rule, else the organisation default — the first that names an active
 * rate. Purchases work the same way with the product's purchase tax rule and the supplier's tax rule.
 * An explicit choice on a line comes before all of them (see {@link #choose}).
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

    private static final Set<String> TAX_OVERRIDE_ROLES = Set.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SUPER_ADMIN");

    /** The rate a line gets, and whether it differs from the rule. */
    public record Choice(TaxRate rate, boolean overridden) {}

    /** The active sale rate with this name; unknown or inactive names are refused. */
    @Transactional
    public TaxRate saleRate(String name) {
        return rate(Usage.SALE, name);
    }

    /** The active rate of this usage with this name; unknown or inactive names are refused. */
    @Transactional
    public TaxRate rate(Usage usage, String name) {
        ensureDefaults();
        return find(usage, name).orElseThrow(() -> new IllegalArgumentException("Unknown tax rule: " + name));
    }

    /** Product's purchase rule, else supplier's, else the organisation default (product and supplier may be null). */
    @Transactional
    public TaxRate resolvePurchase(Product product, Supplier supplier) {
        ensureDefaults();
        List<String> names = new ArrayList<>();
        if (product != null && product.getDetails() != null) names.add(product.getDetails().getPurchaseTaxRule());
        if (supplier != null) names.add(supplier.getTaxRule());
        String fallback = tenantRepository.findById(TenantContext.get()).map(Tenant::getDefaultPurchaseTaxRule)
                .orElse("20% (VAT on Expenses)");
        names.add(fallback);
        for (String name : names) {
            Optional<TaxRate> rate = find(Usage.PURCHASE, name);
            if (rate.isPresent()) return rate.get();
        }
        throw new IllegalStateException("The default purchase tax rule '" + fallback + "' is not an active rate");
    }

    /**
     * The rule's rate, unless the request names another one: only a manager or admin may do that, and less VAT
     * than the rule needs a reason. Every change is collected for the audit log.
     */
    @Transactional
    public Choice choose(Usage usage, String requested, String reason, TaxRate rule, String what, List<Map<String, Object>> overrides) {
        if (requested == null || requested.isBlank() || requested.trim().equalsIgnoreCase(rule.getName())) {
            return new Choice(rule, false);
        }
        TaxRate chosen = rate(usage, requested);
        if (!mayOverride()) {
            throw new AccessDeniedException("Only a manager or admin can change the VAT on " + what);
        }
        if (chosen.getRate().compareTo(rule.getRate()) < 0 && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Give a reason for less VAT than " + rule.getName() + " on " + what);
        }
        overrides.add(Map.of("line", what, "from", rule.getName(), "to", chosen.getName(),
                "reason", reason == null ? "" : reason.trim()));
        return new Choice(chosen, true);
    }

    private static boolean mayOverride() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Jobs and integrations run without a user
        if (auth == null || "anonymousUser".equals(auth.getPrincipal())) return true;
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(TAX_OVERRIDE_ROLES::contains);
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
            Optional<TaxRate> rate = find(Usage.SALE, name);
            if (rate.isPresent()) return rate.get();
        }
        throw new IllegalStateException("The default sale tax rule '" + defaultSaleTaxRule() + "' is not an active rate");
    }

    private Optional<TaxRate> find(Usage usage, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return taxRateRepository.findFirstByUsageAndNameIgnoreCaseAndActiveTrue(usage, name.trim());
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
