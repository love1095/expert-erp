package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.SaleOrder.Status;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
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

/** Phase D3: quotes — the first stage of the same sale document (docs/phase-d-sales.md). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class QuoteTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired ProductService productService;
    @Autowired DashboardService dashboardService;
    @Autowired InvoiceService invoiceService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired SaleOrderRepository saleOrderRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Quote " + id);
        t.setSlug("quote-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELMET-" + id);
        helmet.setName("Helmet");
        helmet.setUnit("pcs");
        helmet = productRepository.save(helmet);
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aQuoteReservesNothingAndIsNotASaleUntilItIsConverted() {
        SaleOrderResponse quote = saleOrderService.create(request(true, 4, "10.00"));
        assertThat(quote.getStatus()).isEqualTo(Status.QUOTE);
        assertThat(quote.getOrderNumber()).startsWith("SO-");
        assertThat(quote.getOrderedAt()).isNull();
        assertThat(quote.getQuoteValidUntil()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(quote.getTotal()).isEqualByComparingTo("48.00");
        assertThat(productService.findById(helmet.getId()).getReservedStock()).isEqualByComparingTo("0");
        assertThat(dashboardService.get(7).kpis().ordersInPeriod()).isZero();
        assertThat(dashboardService.get(7).sales().openQuotes()).isEqualTo(1);
        assertThatThrownBy(() -> saleOrderService.confirm(quote.getId())).isInstanceOf(IllegalStateException.class);

        // Revised: 5 at 9.00 — same number, VAT worked out again
        SaleOrderResponse revised = saleOrderService.update(quote.getId(), request(true, 5, "9.00"));
        assertThat(revised.getOrderNumber()).isEqualTo(quote.getOrderNumber());
        assertThat(revised.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getQuantity()).isEqualByComparingTo("5");
            assertThat(l.getTaxName()).isEqualTo("20% (VAT on Income)");
        });
        assertThat(revised.getTotal()).isEqualByComparingTo("54.00");

        SaleOrderResponse order = saleOrderService.convertQuote(quote.getId());
        assertThat(order.getStatus()).isEqualTo(Status.DRAFT);
        assertThat(order.getOrderNumber()).isEqualTo(quote.getOrderNumber());
        assertThat(order.getOrderedAt()).isNotNull();
        assertThat(dashboardService.get(7).kpis().salesValueInPeriod()).isEqualByComparingTo("45.00");

        saleOrderService.confirm(order.getId());
        assertThat(productService.findById(helmet.getId()).getReservedStock()).isEqualByComparingTo("5");
        // Nothing is edited once confirmed
        assertThatThrownBy(() -> saleOrderService.update(order.getId(), request(false, 1, "1.00")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Only quotes and draft orders");
    }

    @Test
    void anExpiredQuoteMustBeRenewedBeforeItIsConverted() {
        SaleOrderRequest req = request(true, 1, "10.00");
        req.setQuoteValidUntil(LocalDate.now().minusDays(1));
        SaleOrderResponse quote = saleOrderService.create(req);
        assertThat(quote.isQuoteExpired()).isTrue();
        assertThatThrownBy(() -> saleOrderService.convertQuote(quote.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");

        req.setQuoteValidUntil(LocalDate.now().plusDays(7));
        assertThat(saleOrderService.update(quote.getId(), req).isQuoteExpired()).isFalse();
        assertThat(saleOrderService.convertQuote(quote.getId()).getStatus()).isEqualTo(Status.DRAFT);
    }

    @Test
    void aRejectedQuoteCanNoLongerBeConvertedOrChanged() {
        SaleOrderResponse quote = saleOrderService.create(request(true, 1, "10.00"));
        assertThat(saleOrderService.rejectQuote(quote.getId()).getStatus()).isEqualTo(Status.REJECTED);
        assertThatThrownBy(() -> saleOrderService.convertQuote(quote.getId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saleOrderService.update(quote.getId(), request(true, 2, "1.00"))).isInstanceOf(IllegalStateException.class);
        assertThat(dashboardService.get(7).sales().openQuotes()).isZero();
    }

    @Test
    void draftOrdersCanBeChangedButShopifyOnesCannotAndTheListFiltersByStatus() {
        SaleOrderResponse draft = saleOrderService.create(request(false, 1, "10.00"));
        assertThat(saleOrderService.update(draft.getId(), request(false, 3, "10.00")).getTotal()).isEqualByComparingTo("36.00");
        saleOrderService.create(request(true, 1, "10.00"));

        assertThat(saleOrderService.findAll(null, Status.QUOTE, 0, 20).getContent()).extracting(SaleOrderResponse::getStatus)
                .containsOnly(Status.QUOTE).hasSize(1);
        assertThat(saleOrderService.findAll(null, null, 0, 20).getTotalElements()).isEqualTo(2);

        SaleOrder shopify = saleOrderRepository.findById(draft.getId()).orElseThrow();
        shopify.setSource(SaleOrder.Source.SHOPIFY);
        saleOrderRepository.save(shopify);
        assertThatThrownBy(() -> saleOrderService.update(draft.getId(), request(false, 2, "10.00")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Shopify");
    }

    @Test
    void undoTakesAConfirmedOrderBackToDraftUntilItIsShippedOrInvoiced() {
        SaleOrderResponse order = saleOrderService.create(request(false, 4, "10.00"));
        assertThatThrownBy(() -> saleOrderService.undo(order.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Only confirmed orders");

        saleOrderService.confirm(order.getId());
        assertThat(productService.findById(helmet.getId()).getReservedStock()).isEqualByComparingTo("4");
        SaleOrderResponse undone = saleOrderService.undo(order.getId());
        assertThat(undone.getStatus()).isEqualTo(Status.DRAFT);
        assertThat(productService.findById(helmet.getId()).getReservedStock()).isEqualByComparingTo("0");
        // A draft again: it can be changed and authorised again
        assertThat(saleOrderService.update(order.getId(), request(false, 2, "10.00")).getTotal()).isEqualByComparingTo("24.00");

        saleOrderService.confirm(order.getId());
        invoiceService.invoiceOrder(order.getId(), new com.experterp.backend.dto.OrderInvoiceRequest());
        assertThatThrownBy(() -> saleOrderService.undo(order.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has invoices");

        SaleOrderResponse shipped = saleOrderService.create(request(false, 1, "10.00"));
        saleOrderService.confirm(shipped.getId());
        saleOrderService.fulfill(shipped.getId(), null);
        assertThatThrownBy(() -> saleOrderService.undo(shipped.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    private SaleOrderRequest request(boolean quote, int qty, String price) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("Quote customer");
        req.setWarehouseId(warehouse.getId());
        req.setQuote(quote);
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        req.setLines(List.of(l));
        return req;
    }
}
