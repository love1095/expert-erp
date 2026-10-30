package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase D1: order header defaults, VAT per line (docs/business-rules.md §3), charges, totals, numbering. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SaleOrderVatTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired OrganisationSettingsService settingsService;
    @Autowired TaxService taxService;
    @Autowired DashboardService dashboardService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired SaleOrderRepository saleOrderRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;      // no tax rule of its own → customer's / default
    Product firstAid;    // zero rated
    Customer yates;      // 10 % discount, rep, terms, carrier

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("VAT " + id);
        t.setSlug("vat-" + id);
        TenantContext.set(tenantRepository.save(t).getId());

        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = product("HELMET", "4.00", null);
        firstAid = product("FIRSTAID", "6.00", "Zero Rated Income");

        yates = new Customer();
        yates.setName("AE Yates Ltd " + id);
        yates.setDiscount(new BigDecimal("10"));
        yates.setSalesRepresentative("Dwaine Silverblade");
        yates.setPaymentTerm("30 Day EOM");
        yates.setCarrier("Expert Van");
        yates.setTaxRule("20% (VAT on Income)");
        yates = customerRepository.save(yates);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void linesGetVatByTheRuleAndTheOrderTotalsAddUp() {
        SaleOrderRequest req = order();
        req.setCustomerReference("PO-024297");
        req.setLines(List.of(line(helmet, "2", "10.00"), line(firstAid, "1", "25.00")));
        req.setCharges(List.of(charge("Carriage", "7.50")));

        SaleOrderResponse so = saleOrderService.create(req);

        // Header defaults come from the customer
        assertThat(so.getSalesRep()).isEqualTo("Dwaine Silverblade");
        assertThat(so.getPaymentTerm()).isEqualTo("30 Day EOM");
        assertThat(so.getCarrier()).isEqualTo("Expert Van");
        assertThat(so.getCustomerReference()).isEqualTo("PO-024297");

        // Helmet: customer's 20 %, 10 % discount → 2 × 10.00 × 0.9 = 18.00 + 3.60 VAT
        SaleOrderResponse.Line h = so.getLines().get(0);
        assertThat(h.getTaxName()).isEqualTo("20% (VAT on Income)");
        assertThat(h.getDiscountPercent()).isEqualByComparingTo("10");
        assertThat(h.getNetAmount()).isEqualByComparingTo("18.00");
        assertThat(h.getTaxAmount()).isEqualByComparingTo("3.60");
        // Margin over cost 2 × 4.00: (18 − 8) / 18
        assertThat(h.getMarginPercent()).isEqualByComparingTo("55.56");
        // First aid: the product's own rule wins over the customer's → 22.50 + 0
        SaleOrderResponse.Line f = so.getLines().get(1);
        assertThat(f.getTaxName()).isEqualTo("Zero Rated Income");
        assertThat(f.getNetAmount()).isEqualByComparingTo("22.50");
        assertThat(f.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(f.isTaxOverridden()).isFalse();
        // Carriage: customer's rule, no discount → 7.50 + 1.50
        SaleOrderResponse.Charge c = so.getCharges().get(0);
        assertThat(c.getTaxName()).isEqualTo("20% (VAT on Income)");
        assertThat(c.getTaxAmount()).isEqualByComparingTo("1.50");

        assertThat(so.getSubtotal()).isEqualByComparingTo("48.00");
        assertThat(so.getTaxTotal()).isEqualByComparingTo("5.10");
        assertThat(so.getTotal()).isEqualByComparingTo("53.10");

        // A later rate change never alters the order
        TaxRate standard = taxRateRepository.findFirstByUsageAndNameIgnoreCaseAndActiveTrue(TaxRate.Usage.SALE, "20% (VAT on Income)").orElseThrow();
        standard.setRate(new BigDecimal("17.5"));
        taxRateRepository.save(standard);
        assertThat(saleOrderService.findById(so.getId()).getTaxTotal()).isEqualByComparingTo("5.10");
    }

    @Test
    void withoutProductOrCustomerRulesTheOrganisationDefaultApplies() {
        Customer walkIn = new Customer();
        walkIn.setName("Walk-in " + UUID.randomUUID());
        walkIn = customerRepository.save(walkIn);
        SaleOrderRequest req = order();
        req.setCustomerId(walkIn.getId());
        req.setLines(List.of(line(helmet, "1", "10.00")));

        SaleOrderResponse so = saleOrderService.create(req);
        assertThat(so.getLines().get(0).getTaxName()).isEqualTo("20% (VAT on Income)");
        assertThat(so.getLines().get(0).getDiscountPercent()).isEqualByComparingTo("0");
        assertThat(so.getSalesRep()).isNull();
        assertThat(so.getTotal()).isEqualByComparingTo("12.00");
    }

    @Test
    void onlyAManagerMayChangeTheVatAndLessVatNeedsAReason() {
        SaleOrderRequest req = order();
        SaleOrderRequest.Line zero = line(helmet, "1", "10.00");
        zero.setTaxRule("Zero Rated Income");
        req.setLines(List.of(zero));

        actAs("ROLE_STAFF");
        assertThatThrownBy(() -> saleOrderService.create(req)).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(helmet.getSku());

        actAs("ROLE_MANAGER");
        assertThatThrownBy(() -> saleOrderService.create(req)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        zero.setTaxReason("Exported outside the UK");
        SaleOrderResponse so = saleOrderService.create(req);
        assertThat(so.getLines().get(0).getTaxName()).isEqualTo("Zero Rated Income");
        assertThat(so.getLines().get(0).isTaxOverridden()).isTrue();

        // Choosing the rule's own rate is not a change; an unknown rate is refused
        actAs("ROLE_STAFF");
        zero.setTaxRule("20% (VAT on Income)");
        assertThat(saleOrderService.create(req).getLines().get(0).isTaxOverridden()).isFalse();
        zero.setTaxRule("17% Made Up");
        assertThatThrownBy(() -> saleOrderService.create(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderNumbersFollowTheOrganisationSequenceAndSkipNumbersInUse() {
        settingsService.update(new OrganisationSettingsService.Settings("SO-", 66006, "Web Sales", "20% (VAT on Income)", 30));
        SaleOrderResponse first = saleOrderService.create(simpleOrder());
        assertThat(first.getOrderNumber()).isEqualTo("SO-66006");

        // A number already taken (e.g. imported) is skipped
        SaleOrder taken = saleOrderRepository.findById(first.getId()).orElseThrow();
        taken.setOrderNumber("SO-66007");
        saleOrderRepository.save(taken);
        assertThat(saleOrderService.create(simpleOrder()).getOrderNumber()).isEqualTo("SO-66008");
        assertThat(settingsService.get().nextSaleOrderNumber()).isEqualTo(66009);

        assertThatThrownBy(() -> settingsService.update(new OrganisationSettingsService.Settings("SO-", 1, "Web", "Nope", 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPreviewCalculatesWithoutSavingOrCreatingTheCustomer() {
        long before = saleOrderRepository.count();
        SaleOrderRequest req = order();
        req.setCustomerId(null);
        req.setCustomerName("Brand new customer " + UUID.randomUUID());
        SaleOrderRequest.Line incomplete = new SaleOrderRequest.Line();   // still being filled in: ignored
        req.setLines(List.of(line(helmet, "3", "10.00"), incomplete));

        SaleOrderResponse preview = saleOrderService.preview(req);
        assertThat(preview.getId()).isNull();
        assertThat(preview.getOrderNumber()).isNull();
        assertThat(preview.getLines()).hasSize(1);
        assertThat(preview.getTotal()).isEqualByComparingTo("36.00");
        assertThat(saleOrderRepository.count()).isEqualTo(before);
        assertThat(customerRepository.findFirstByNameIgnoreCaseOrderByIdAsc(req.getCustomerName())).isEmpty();
    }

    @Test
    void theDashboardCountsRevenueExVatAndSalesPerRep() {
        SaleOrderRequest a = order();
        a.setLines(List.of(line(helmet, "2", "10.00")));          // 18.00 + 3.60 (Dwaine)
        a.setCharges(List.of(charge("Carriage", "5.00")));       // 5.00 + 1.00
        saleOrderService.create(a);
        SaleOrderRequest b = order();
        b.setSalesRep("Stephen Seddon");
        b.setLines(List.of(line(firstAid, "1", "20.00")));        // 18.00 + 0
        saleOrderService.create(b);
        SaleOrderRequest cancelled = order();
        cancelled.setLines(List.of(line(helmet, "100", "10.00")));
        saleOrderService.cancel(saleOrderService.create(cancelled).getId());

        DashboardResponse d = dashboardService.get(7);
        assertThat(d.kpis().salesValueInPeriod()).isEqualByComparingTo("41.00");
        assertThat(d.salesByRep()).extracting(DashboardResponse.RepSales::salesRep)
                .containsExactly("Dwaine Silverblade", "Stephen Seddon");
        DashboardResponse.RepSales dwaine = d.salesByRep().get(0);
        assertThat(dwaine.orders()).isEqualTo(1);
        assertThat(dwaine.net()).isEqualByComparingTo("23.00");
        assertThat(dwaine.tax()).isEqualByComparingTo("4.60");
        assertThat(dwaine.total()).isEqualByComparingTo("27.60");
    }

    private SaleOrderRequest order() {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(yates.getId());
        req.setWarehouseId(warehouse.getId());
        return req;
    }

    private SaleOrderRequest simpleOrder() {
        SaleOrderRequest req = order();
        req.setLines(List.of(line(helmet, "1", "1.00")));
        return req;
    }

    private static SaleOrderRequest.Line line(Product p, String qty, String price) {
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPrice(new BigDecimal(price));
        return l;
    }

    private static SaleOrderRequest.Charge charge(String description, String price) {
        SaleOrderRequest.Charge c = new SaleOrderRequest.Charge();
        c.setDescription(description);
        c.setUnitPrice(new BigDecimal(price));
        return c;
    }

    private Product product(String sku, String cost, String saleTaxRule) {
        Product p = new Product();
        p.setSku(sku + "-" + UUID.randomUUID());
        p.setName(sku);
        p.setUnit("pcs");
        p.setCostPrice(new BigDecimal(cost));
        p.getDetails().setSaleTaxRule(saleTaxRule);
        return productRepository.save(p);
    }

    private static void actAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", null, List.of(new SimpleGrantedAuthority(role))));
    }
}
