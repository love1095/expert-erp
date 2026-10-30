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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase D4b: credit notes & restock, refunds — as Cin7's CR-00231 for INV-118520. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CreditNoteTest {

    @Autowired CreditNoteService creditNoteService;
    @Autowired InvoiceService invoiceService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired DashboardService dashboardService;
    @Autowired StockMovementService movementService;
    @Autowired StockMovementRepository movementRepository;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;
    SaleOrderResponse order;
    InvoiceResponse invoice;   // 4 helmets × 10 + carriage 5, all + 20 % = 54.00, shipped, issued

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Credit " + id);
        t.setSlug("credit-" + id);
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

        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("David Loudfoot " + id);
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(new BigDecimal("4"));
        l.setUnitPrice(BigDecimal.TEN);
        req.setLines(List.of(l));
        SaleOrderRequest.Charge c = new SaleOrderRequest.Charge();
        c.setDescription("Carriage");
        c.setUnitPrice(new BigDecimal("5"));
        req.setCharges(List.of(c));
        order = saleOrderService.confirm(saleOrderService.create(req).getId());
        saleOrderService.fulfill(order.getId(), null);
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        invoice = invoiceService.invoiceOrder(order.getId(), all);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void creditingGoodsAndAShippingRefundRestocksAndRecordsTheRefundLikeCin7() {
        CreditNoteRequest req = new CreditNoteRequest();
        req.setLines(List.of(line(helmetItem(), "2", true)));
        req.setCharges(List.of(charge("Shipping refund", "5.00")));
        req.setReason("Wrong size");
        req.setSettle(CreditNoteRequest.Settle.REFUND);
        req.setRefundReference("15219726221691");

        InvoiceResponse cn = creditNoteService.create(invoice.getId(), req);

        assertThat(cn.getKind()).isEqualTo(Invoice.Kind.CREDIT_NOTE);
        assertThat(cn.getInvoiceNumber()).isEqualTo("CR-00001");
        assertThat(cn.getCreditedInvoiceNumber()).isEqualTo(invoice.getInvoiceNumber());
        // 2 × 10 + 5, + 20 % VAT
        assertThat(cn.getSubtotal()).isEqualByComparingTo("25.00");
        assertThat(cn.getTaxTotal()).isEqualByComparingTo("5.00");
        assertThat(cn.getTotalAmount()).isEqualByComparingTo("30.00");
        assertThat(cn.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(cn.getPayments()).singleElement().satisfies(p -> {
            assertThat(p.getMethod()).isEqualTo(Payment.PaymentMethod.BANK);
            assertThat(p.getReference()).isEqualTo("15219726221691");
        });
        // Back in stock: 10 − 4 shipped + 2
        assertThat(movementRepository.calculateStock(helmet.getId(), warehouse.getId())).isEqualByComparingTo("8");
        assertThat(saleOrderService.findById(order.getId()).getLines().get(0).getReturnedQuantity()).isEqualByComparingTo("2");
        assertThat(invoiceService.findById(invoice.getId()).getItems().get(0).getCreditedQuantity()).isEqualByComparingTo("2");
        assertThat(saleOrderService.findById(order.getId()).getCreditedTotal()).isEqualByComparingTo("30.00");
    }

    @Test
    void byDefaultTheCreditPaysOffTheInvoice() {
        InvoiceResponse cn = creditNoteService.create(invoice.getId(), request(line(helmetItem(), "1", false)));

        assertThat(cn.getTotalAmount()).isEqualByComparingTo("12.00");
        assertThat(cn.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(cn.getCreditApplied()).isEqualByComparingTo("12.00");
        InvoiceResponse inv = invoiceService.findById(invoice.getId());
        assertThat(inv.getAmountPaid()).isEqualByComparingTo("12.00");
        assertThat(inv.getPayments()).singleElement().satisfies(p -> {
            assertThat(p.getMethod()).isEqualTo(Payment.PaymentMethod.CREDIT_NOTE);
            assertThat(p.getCreditNoteId()).isEqualTo(cn.getId());
        });
        // Nothing restocked: only the money
        assertThat(movementRepository.calculateStock(helmet.getId(), warehouse.getId())).isEqualByComparingTo("6");
        assertThat(dashboardService.get(7).kpis().receivablesOutstanding()).isEqualByComparingTo("42.00");
    }

    @Test
    void creditKeptOnAccountLowersWhatTheCustomerOwesUntilRefunded() {
        CreditNoteRequest req = request(line(helmetItem(), "1", false));
        req.setSettle(CreditNoteRequest.Settle.KEEP_AS_CREDIT);
        InvoiceResponse cn = creditNoteService.create(invoice.getId(), req);
        assertThat(cn.getStatus()).isEqualTo(Invoice.InvoiceStatus.ISSUED);
        assertThat(dashboardService.get(7).kpis().receivablesOutstanding()).isEqualByComparingTo("42.00");

        PaymentRequest refund = new PaymentRequest();
        refund.setAmount(new BigDecimal("12.00"));
        refund.setMethod(Payment.PaymentMethod.BANK);
        assertThat(invoiceService.addPayment(cn.getId(), refund).getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(dashboardService.get(7).kpis().receivablesOutstanding()).isEqualByComparingTo("54.00");
    }

    @Test
    void neverMoreThanInvoicedOrShippedAndCreditNotesAreFinal() {
        assertThatThrownBy(() -> creditNoteService.create(invoice.getId(), request(line(helmetItem(), "5", false))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 4 invoiced and not credited");

        InvoiceResponse cn = creditNoteService.create(invoice.getId(), request(line(helmetItem(), "3", true)));
        assertThatThrownBy(() -> creditNoteService.create(invoice.getId(), request(line(helmetItem(), "2", false))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 1 invoiced and not credited");

        assertThatThrownBy(() -> invoiceService.cancel(cn.getId())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be cancelled");
        assertThatThrownBy(() -> invoiceService.cancel(invoice.getId())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has credit notes");
    }

    @Test
    void goodsNotShippedCannotBeRestockedAndNothingIsSaved() {
        // A second order invoiced before shipping, as Cin7 allows
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("Not shipped");
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(new BigDecimal("2"));
        l.setUnitPrice(BigDecimal.TEN);
        req.setLines(List.of(l));
        SaleOrderResponse unshipped = saleOrderService.confirm(saleOrderService.create(req).getId());
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(unshipped.getId(), all);
        Long itemId = inv.getItems().get(0).getId();

        assertThatThrownBy(() -> creditNoteService.create(inv.getId(), request(line(itemId, "1", true))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot return");
        assertThat(invoiceService.findById(inv.getId()).getItems().get(0).getCreditedQuantity()).isEqualByComparingTo("0");
        // Crediting the money only is fine
        assertThat(creditNoteService.create(inv.getId(), request(line(itemId, "1", false))).getTotalAmount()).isEqualByComparingTo("12.00");
    }

    private Long helmetItem() {
        return invoice.getItems().stream().filter(i -> i.getProductId() != null).findFirst().orElseThrow().getId();
    }

    private static CreditNoteRequest request(CreditNoteRequest.Line... lines) {
        CreditNoteRequest req = new CreditNoteRequest();
        req.setLines(List.of(lines));
        return req;
    }

    private static CreditNoteRequest.Line line(Long itemId, String qty, boolean restock) {
        CreditNoteRequest.Line l = new CreditNoteRequest.Line();
        l.setInvoiceItemId(itemId);
        l.setQuantity(new BigDecimal(qty));
        l.setRestock(restock);
        return l;
    }

    private static CreditNoteRequest.Charge charge(String description, String price) {
        CreditNoteRequest.Charge c = new CreditNoteRequest.Charge();
        c.setDescription(description);
        c.setUnitPrice(new BigDecimal(price));
        return c;
    }
}
