package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
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

/** Phase D4a: invoices made from orders — part or full, VAT from the order, due date from terms, on shipment. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InvoiceFromOrderTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired OrganisationSettingsService settingsService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;     // 20 %
    Product firstAid;   // zero rated
    Customer customer;  // 30 Day EOM

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Invoice " + id);
        t.setSlug("invoice-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = product("HELMET", null);
        firstAid = product("FIRSTAID", "Zero Rated Income");
        customer = new Customer();
        customer.setName("AE Yates " + id);
        customer.setPaymentTerm("30 Day EOM");
        customer = customerRepository.save(customer);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anOrderIsInvoicedInPartsWithItsVatAndCancellingGivesTheQuantitiesBack() {
        SaleOrderResponse so = saleOrderService.confirm(order().getId());
        Long helmetLine = so.getLines().get(0).getId();
        // 4 × 10 (+20 %) = 48, 2 × 5 (0 %) = 10, carriage 7.50 (+20 %) = 9
        assertThat(saleOrderService.findById(so.getId()).getToInvoiceTotal()).isEqualByComparingTo("67.00");

        // 3 helmets now (the charge goes on the first invoice): 30 + 6 + 7.50 + 1.50
        InvoiceResponse first = invoiceService.invoiceOrder(so.getId(), invoice(helmetLine, "3"));
        assertThat(first.getStatus()).isEqualTo(Invoice.InvoiceStatus.DRAFT);
        assertThat(first.getSubtotal()).isEqualByComparingTo("37.50");
        assertThat(first.getTaxTotal()).isEqualByComparingTo("7.50");
        assertThat(first.getTotalAmount()).isEqualByComparingTo("45.00");
        assertThat(first.getItems()).extracting(InvoiceResponse.ItemResponse::getTaxName)
                .containsExactly("20% (VAT on Income)", "20% (VAT on Income)");
        // 30 Day EOM: 30 days after the end of this month
        LocalDate today = LocalDate.now();
        assertThat(first.getDueDate()).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()).plusDays(30));
        assertThat(first.getCustomerId()).isEqualTo(customer.getId());
        assertThat(first.getCurrency()).isEqualTo("GBP");

        SaleOrderResponse after = saleOrderService.findById(so.getId());
        assertThat(after.getLines().get(0).getInvoicedQuantity()).isEqualByComparingTo("3");
        assertThat(after.getToInvoiceTotal()).isEqualByComparingTo("22.00");
        assertThat(after.getInvoicedTotal()).isEqualByComparingTo("45.00");
        assertThat(after.getInvoices()).extracting(SaleOrderResponse.InvoiceEntry::getInvoiceNumber).containsExactly(first.getInvoiceNumber());

        // More than is left is refused; the rest in one go; then nothing is left
        assertThatThrownBy(() -> invoiceService.invoiceOrder(so.getId(), invoice(helmetLine, "2")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 1 not invoiced");
        OrderInvoiceRequest rest = new OrderInvoiceRequest();
        rest.setIssue(true);
        InvoiceResponse second = invoiceService.invoiceOrder(so.getId(), rest);
        assertThat(second.getStatus()).isEqualTo(Invoice.InvoiceStatus.ISSUED);
        assertThat(second.getTotalAmount()).isEqualByComparingTo("22.00");
        assertThatThrownBy(() -> invoiceService.invoiceOrder(so.getId(), new OrderInvoiceRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Nothing left to invoice");

        // Cancelling the first puts 3 helmets and the carriage back to "to invoice"
        invoiceService.cancel(first.getId());
        SaleOrderResponse reopened = saleOrderService.findById(so.getId());
        assertThat(reopened.getLines().get(0).getInvoicedQuantity()).isEqualByComparingTo("1");
        assertThat(reopened.getCharges().get(0).isInvoiced()).isFalse();
        assertThat(reopened.getToInvoiceTotal()).isEqualByComparingTo("45.00");
        assertThat(reopened.getInvoicedTotal()).isEqualByComparingTo("22.00");
    }

    @Test
    void onlyConfirmedOrCompletedOrdersAreInvoiced() {
        SaleOrderResponse draft = order();
        assertThatThrownBy(() -> invoiceService.invoiceOrder(draft.getId(), new OrderInvoiceRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("confirmed or completed");
    }

    @Test
    void withTheSettingOnEveryShipmentIsInvoicedAndNumbersCarryOnFromTheSetting() {
        OrganisationSettingsService.Settings s = settingsService.get();
        settingsService.update(new OrganisationSettingsService.Settings(s.saleOrderPrefix(), s.nextSaleOrderNumber(),
                s.webSalesRep(), s.defaultSaleTaxRule(), s.quoteValidDays(), "INV-", 118554, true, s.creditNotePrefix(), s.nextCreditNoteNumber()));
        receive(helmet, 10);
        receive(firstAid, 10);
        SaleOrderResponse so = saleOrderService.confirm(order().getId());

        FulfilmentRequest two = new FulfilmentRequest();
        FulfilmentRequest.Line l = new FulfilmentRequest.Line();
        l.setSaleOrderLineId(so.getLines().get(0).getId());
        l.setQuantity(new BigDecimal("2"));
        two.setLines(List.of(l));
        SaleOrderResponse shipped = saleOrderService.fulfill(so.getId(), two);

        assertThat(shipped.getInvoices()).singleElement().satisfies(inv -> {
            assertThat(inv.getInvoiceNumber()).isEqualTo("INV-118554");
            assertThat(inv.getStatus()).isEqualTo(Invoice.InvoiceStatus.ISSUED);
            // 2 helmets + carriage
            assertThat(inv.getTotal()).isEqualByComparingTo("33.00");
            assertThat(inv.getFulfilmentId()).isEqualTo(shipped.getFulfilments().get(0).getId());
        });

        SaleOrderResponse done = saleOrderService.fulfill(so.getId(), null);
        assertThat(done.getInvoices()).extracting(SaleOrderResponse.InvoiceEntry::getInvoiceNumber)
                .containsExactly("INV-118554", "INV-118555");
        assertThat(done.getToInvoiceTotal()).isEqualByComparingTo("0");
        assertThat(done.getInvoicedTotal()).isEqualByComparingTo("67.00");
    }

    private SaleOrderResponse order() {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customer.getId());
        req.setWarehouseId(warehouse.getId());
        req.setLines(List.of(line(helmet, "4", "10.00"), line(firstAid, "2", "5.00")));
        SaleOrderRequest.Charge c = new SaleOrderRequest.Charge();
        c.setDescription("Carriage");
        c.setUnitPrice(new BigDecimal("7.50"));
        req.setCharges(List.of(c));
        return saleOrderService.create(req);
    }

    private static OrderInvoiceRequest invoice(Long lineId, String qty) {
        OrderInvoiceRequest req = new OrderInvoiceRequest();
        OrderInvoiceRequest.Line l = new OrderInvoiceRequest.Line();
        l.setSaleOrderLineId(lineId);
        l.setQuantity(new BigDecimal(qty));
        req.setLines(List.of(l));
        return req;
    }

    private static SaleOrderRequest.Line line(Product p, String qty, String price) {
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPrice(new BigDecimal(price));
        return l;
    }

    private Product product(String sku, String saleTaxRule) {
        Product p = new Product();
        p.setSku(sku + "-" + UUID.randomUUID());
        p.setName(sku);
        p.setUnit("pcs");
        p.getDetails().setSaleTaxRule(saleTaxRule);
        return productRepository.save(p);
    }

    private void receive(Product p, int qty) {
        movementService.createMovement(p, warehouse, BigDecimal.valueOf(qty), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
    }
}
