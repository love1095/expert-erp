package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase G1 (spec §5): price tiers and contract prices decide an order line's price when none is typed. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PricingTest {

    @Autowired PricingService pricingService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product gloves;     // Retail 4.50, Contract (tier 2) 3.60
    Product boots;      // Retail 40.00, no tier-2 price
    Customer contract;
    Customer retail;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Pricing " + id);
        t.setSlug("pricing-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Main");
        warehouse = warehouseRepository.save(warehouse);
        gloves = product("GLOVE-1", "4.50", "3.60");
        boots = product("BOOT-1", "40.00", null);
        // New organisations get Retail and Trade; rename Trade to Contract like the Cin7 customers use
        pricingService.updateTiers(List.of(new PricingService.TierRequest(1, "Retail", null),
                new PricingService.TierRequest(2, "Contract", null)));
        contract = customer("Loudfoot Builders", "Contract", "5");
        retail = customer("Walk-in", null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aLineWithoutAPriceTakesTheCustomersTierAndRetailWhenTheProductHasNoTierPrice() {
        SaleOrderResponse so = saleOrderService.create(order(contract, line(gloves, null), line(boots, null)));
        assertThat(so.getLines().get(0).getUnitPrice()).isEqualByComparingTo("3.60");
        assertThat(so.getLines().get(0).getPriceSource()).isEqualTo("Contract tier");
        // The customer's 5 % discount still applies on a tier price
        assertThat(so.getLines().get(0).getDiscountPercent()).isEqualByComparingTo("5");
        assertThat(so.getLines().get(1).getUnitPrice()).isEqualByComparingTo("40.00");

        SaleOrderResponse walkIn = saleOrderService.create(order(retail, line(gloves, null)));
        assertThat(walkIn.getLines().get(0).getUnitPrice()).isEqualByComparingTo("4.50");
        assertThat(walkIn.getLines().get(0).getPriceSource()).isEqualTo("Retail");

        // A typed price is kept and says so
        SaleOrderResponse typed = saleOrderService.create(order(contract, line(gloves, "3.00")));
        assertThat(typed.getLines().get(0).getUnitPrice()).isEqualByComparingTo("3.00");
        assertThat(typed.getLines().get(0).getPriceSource()).isEqualTo("Typed");
    }

    @Test
    void aContractPriceWinsWhileItIsValidWithNoDiscountOnTop() {
        LocalDate today = LocalDate.now();
        pricingService.addContractPrice(contract.getId(), new PricingService.ContractPriceRequest(gloves.getId(), new BigDecimal("3.10"),
                today.minusDays(10), today.plusDays(10), "2026 framework"));
        // Ended last month: no longer used
        pricingService.addContractPrice(contract.getId(), new PricingService.ContractPriceRequest(boots.getId(), new BigDecimal("30.00"),
                today.minusDays(60), today.minusDays(30), null));

        SaleOrderResponse so = saleOrderService.create(order(contract, line(gloves, null), line(boots, null)));
        assertThat(so.getLines().get(0).getUnitPrice()).isEqualByComparingTo("3.10");
        assertThat(so.getLines().get(0).getPriceSource()).isEqualTo("Contract price");
        assertThat(so.getLines().get(0).getDiscountPercent()).isEqualByComparingTo("0");
        assertThat(so.getLines().get(1).getUnitPrice()).isEqualByComparingTo("40.00");

        var listed = pricingService.contractPrices(contract.getId());
        assertThat(listed).hasSize(2);
        assertThat(listed).filteredOn(PricingService.ContractPriceView::current).singleElement()
                .satisfies(v -> assertThat(v.tierPrice()).isEqualByComparingTo("3.60"));
    }

    @Test
    void anOrderIsPricedAtTheCustomersTierUnlessAnotherIsChosenOnTheOrder() {
        SaleOrderResponse usual = saleOrderService.create(order(contract, line(gloves, null)));
        assertThat(usual.getPriceTier()).isEqualTo("Contract");

        // Retail chosen on this order: lines without a typed price follow it, a contract price still wins
        pricingService.addContractPrice(contract.getId(), new PricingService.ContractPriceRequest(boots.getId(), new BigDecimal("30.00"),
                LocalDate.now(), null, null));
        SaleOrderRequest req = order(contract, line(gloves, null), line(boots, null));
        req.setPriceTier("retail");
        SaleOrderResponse so = saleOrderService.create(req);
        assertThat(so.getPriceTier()).isEqualTo("Retail");
        assertThat(so.getLines().get(0).getUnitPrice()).isEqualByComparingTo("4.50");
        assertThat(so.getLines().get(0).getPriceSource()).isEqualTo("Retail");
        assertThat(so.getLines().get(1).getUnitPrice()).isEqualByComparingTo("30.00");

        // Changed on the order later: re-priced at the new tier
        req.setPriceTier("Contract");
        req.setLines(List.of(line(gloves, null)));
        assertThat(saleOrderService.update(so.getId(), req).getLines().get(0).getUnitPrice()).isEqualByComparingTo("3.60");

        req.setPriceTier("Gold");
        assertThatThrownBy(() -> saleOrderService.create(req)).hasMessageContaining("no price tier called Gold");
    }

    @Test
    void contractPeriodsForOneSkuCannotOverlap() {
        LocalDate today = LocalDate.now();
        var first = pricingService.addContractPrice(contract.getId(), new PricingService.ContractPriceRequest(gloves.getId(),
                new BigDecimal("3.10"), today, null, null));
        assertThatThrownBy(() -> pricingService.addContractPrice(contract.getId(), new PricingService.ContractPriceRequest(gloves.getId(),
                new BigDecimal("2.90"), today.plusMonths(1), null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("end that one first");
        // End the first, then the next period can start
        pricingService.updateContractPrice(contract.getId(), first.id(), new PricingService.ContractPriceRequest(null,
                new BigDecimal("3.10"), today, today.plusMonths(1).minusDays(1), null));
        assertThat(pricingService.addContractPrice(contract.getId(), new PricingService.ContractPriceRequest(gloves.getId(),
                new BigDecimal("2.90"), today.plusMonths(1), null, null)).current()).isFalse();
        assertThatThrownBy(() -> pricingService.addContractPrice(contract.getId(), new PricingService.ContractPriceRequest(boots.getId(),
                new BigDecimal("1"), today, today.minusDays(1), null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPercentageTierPricesOffRetailAndRenamingATierMovesItsCustomers() {
        pricingService.updateTiers(List.of(new PricingService.TierRequest(1, "Retail", null),
                new PricingService.TierRequest(2, "Trade", null),
                new PricingService.TierRequest(5, "Key account", new BigDecimal("20"))));
        // Renamed Contract → Trade: the customer follows
        assertThat(customerRepository.findById(contract.getId()).orElseThrow().getPriceTier()).isEqualTo("Trade");
        Customer key = customer("Big Account", "Key account", null);
        assertThat(pricingService.price(key, gloves, LocalDate.now()).unitPrice()).isEqualByComparingTo("3.60");   // 4.50 − 20 %
        assertThat(pricingService.price(key, boots, LocalDate.now()).source()).isEqualTo("Key account tier");

        // A tier in use cannot go; tier 5+ needs a discount; tier 1 has none
        assertThatThrownBy(() -> pricingService.updateTiers(List.of(new PricingService.TierRequest(1, "Retail", null),
                new PricingService.TierRequest(2, "Trade", null)))).hasMessageContaining("Key account");
        assertThatThrownBy(() -> pricingService.updateTiers(List.of(new PricingService.TierRequest(1, "Retail", null),
                new PricingService.TierRequest(6, "VIP", null)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricingService.updateTiers(List.of(new PricingService.TierRequest(1, "Retail", BigDecimal.TEN))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pricingService.tiers()).extracting(PricingService.TierView::customers).containsExactly(0L, 1L, 1L);
    }

    // ---- helpers ----

    private Product product(String sku, String retail, String tier2) {
        Product p = new Product();
        p.setSku(sku + "-" + UUID.randomUUID().toString().substring(0, 6));
        p.setName(sku);
        p.setUnit("Each");
        p.setSellPrice(new BigDecimal(retail));
        if (tier2 != null) p.setTradePrice(new BigDecimal(tier2));
        return productRepository.save(p);
    }

    private Customer customer(String name, String tier, String discount) {
        Customer c = new Customer();
        c.setName(name);
        c.setPriceTier(tier);
        if (discount != null) c.setDiscount(new BigDecimal(discount));
        return customerRepository.save(c);
    }

    private SaleOrderRequest order(Customer c, SaleOrderRequest.Line... lines) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(c.getId());
        req.setWarehouseId(warehouse.getId());
        req.setLines(List.of(lines));
        return req;
    }

    private static SaleOrderRequest.Line line(Product p, String price) {
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.TEN);
        if (price != null) l.setUnitPrice(new BigDecimal(price));
        return l;
    }
}
