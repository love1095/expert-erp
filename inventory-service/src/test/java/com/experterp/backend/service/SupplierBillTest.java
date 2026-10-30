package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
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

/** Phase E3: supplier bills and three-way match (docs/phase-e-purchasing.md). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SupplierBillTest {

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Supplier portwest;
    Product small;
    Product xxxl;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Bills " + id);
        t.setSlug("bills-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse " + id);
        warehouse = warehouseRepository.save(warehouse);
        portwest = new Supplier();
        portwest.setName("Portwest Clothing Ltd");
        portwest.setPaymentTerm("30 Day EOM");
        portwest = supplierRepository.save(portwest);
        small = product("HVRW-PW352-YB-S");
        xxxl = product("HVRW-PW352-YB-XXXL");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theBillForWhatWasReceivedWithTheSuppliersShippingMatchesCin7sPo19064() {
        // 3 + 2 at 65.50 = 327.50 + VAT 65.50 = 393.00 ordered and received
        PurchaseOrderResponse po = ordered(line(small, 3), line(xxxl, 2));
        purchaseOrderService.receive(po.getId());

        BillRequest req = bill("006387333");
        req.setIssueDate(LocalDate.of(2026, 10, 28));
        BillRequest.Charge shipping = new BillRequest.Charge();
        shipping.setDescription("Shipping - Standard");
        shipping.setUnitPrice(new BigDecimal("12"));
        req.setCharges(List.of(shipping));
        InvoiceResponse bill = invoiceService.billPurchaseOrder(po.getId(), req);

        assertThat(bill.getType()).isEqualTo(Invoice.InvoiceType.PURCHASE);
        assertThat(bill.getInvoiceNumber()).isEqualTo("006387333");
        assertThat(bill.getStatus()).isEqualTo(Invoice.InvoiceStatus.ISSUED);
        // 30 Day EOM: 30 days after the end of October, as Cin7 shows
        assertThat(bill.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(bill.getSubtotal()).isEqualByComparingTo("339.50");
        assertThat(bill.getTaxTotal()).isEqualByComparingTo("67.90");
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("407.40");
        // The shipping is an extra charge, not a price difference
        assertThat(bill.getMatchIssueCount()).isZero();

        PurchaseOrderResponse after = purchaseOrderService.findById(po.getId());
        assertThat(after.getBillingStatus()).isEqualTo("BILLED");
        assertThat(after.getPaymentStatus()).isEqualTo("UNPAID");
        assertThat(after.getBills()).singleElement().satisfies(b -> assertThat(b.getInvoiceNumber()).isEqualTo("006387333"));

        // The same supplier invoice can't be entered twice
        assertThatThrownBy(() -> invoiceService.billPurchaseOrder(po.getId(), bill("006387333")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already entered");

        PaymentRequest pay = new PaymentRequest();
        pay.setAmount(new BigDecimal("407.40"));
        pay.setMethod(Payment.PaymentMethod.BANK);
        invoiceService.addPayment(bill.getId(), pay);
        assertThat(purchaseOrderService.findById(po.getId()).getPaymentStatus()).isEqualTo("PAID");
    }

    @Test
    void aDifferentPriceAndBillingAheadOfTheGoodsAreFlagged() {
        PurchaseOrderResponse po = ordered(line(small, 3), line(xxxl, 2));
        purchaseOrderService.receive(po.getId(), receipt(po, 0, 3));

        BillRequest req = bill("INV-77");
        req.setLines(List.of(billLine(po, 0, 3, "66.00"), billLine(po, 1, 2, null)));
        InvoiceResponse bill = invoiceService.billPurchaseOrder(po.getId(), req);

        assertThat(bill.getMatchIssueCount()).isEqualTo(2);
        assertThat(bill.getItems().get(0).getMatchIssues()).containsExactly("Price £66.00 differs from the PO (£65.50)");
        assertThat(bill.getItems().get(1).getMatchIssues()).containsExactly("Billed 2, received 0");

        // Nothing is left to bill on either line, and more than ordered is refused
        BillRequest more = bill("INV-78");
        more.setLines(List.of(billLine(po, 1, 1, null)));
        assertThatThrownBy(() -> invoiceService.billPurchaseOrder(po.getId(), more))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 0 left to bill");
        assertThatThrownBy(() -> purchaseOrderService.cancel(po.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingABillGivesItsQuantitiesBack() {
        PurchaseOrderResponse po = ordered(line(small, 3));
        purchaseOrderService.receive(po.getId());
        InvoiceResponse bill = invoiceService.billPurchaseOrder(po.getId(), bill("A-1"));
        assertThat(purchaseOrderService.findById(po.getId()).getBillingStatus()).isEqualTo("BILLED");

        invoiceService.cancel(bill.getId());
        PurchaseOrderResponse after = purchaseOrderService.findById(po.getId());
        assertThat(after.getBillingStatus()).isEqualTo("NOT_BILLED");
        assertThat(after.getLines().get(0).getBilledQuantity()).isEqualByComparingTo("0");
        // A cancelled bill's number can be entered again
        assertThat(invoiceService.billPurchaseOrder(po.getId(), bill("A-1")).getStatus()).isEqualTo(Invoice.InvoiceStatus.ISSUED);
    }

    @Test
    void theAccountingListShowsOverdueBillsAndThePoListCarriesItsBills() {
        PurchaseOrderResponse po = ordered(line(small, 3));
        purchaseOrderService.receive(po.getId());
        BillRequest late = bill("LATE-1");
        late.setIssueDate(LocalDate.now().minusDays(40));
        late.setDueDate(LocalDate.now().minusDays(10));
        InvoiceResponse bill = invoiceService.billPurchaseOrder(po.getId(), late);

        assertThat(invoiceService.findAll(0, 20, Invoice.InvoiceType.PURCHASE, null, true, null).getContent())
                .extracting(InvoiceResponse::getId).containsExactly(bill.getId());
        assertThat(invoiceService.findAll(0, 20, Invoice.InvoiceType.SALES, null, true, null).getTotalElements()).isZero();
        assertThat(bill.getPurchaseOrderNumber()).isEqualTo(po.getOrderNumber());

        PurchaseOrderResponse listed = purchaseOrderService.findAll(0, 20, null, null).getContent().get(0);
        assertThat(listed.getBills()).extracting(PurchaseOrderResponse.Bill::getInvoiceNumber).containsExactly("LATE-1");
        assertThat(listed.getPaymentStatus()).isEqualTo("UNPAID");
    }

    @Test
    void aDraftOrderCannotBeBilled() {
        PurchaseOrderRequest req = request(line(small, 1));
        req.setOrdered(false);
        PurchaseOrderResponse draft = purchaseOrderService.create(req);
        assertThatThrownBy(() -> invoiceService.billPurchaseOrder(draft.getId(), bill("X")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Only ordered or received");
    }

    private PurchaseOrderResponse ordered(PurchaseOrderRequest.Line... lines) {
        return purchaseOrderService.create(request(lines));
    }

    private PurchaseOrderRequest request(PurchaseOrderRequest.Line... lines) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(portwest.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        req.setLines(List.of(lines));
        return req;
    }

    private Product product(String sku) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku);
        p.setUnit("Each");
        return productRepository.save(p);
    }

    private static PurchaseOrderRequest.Line line(Product p, int qty) {
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitCost(new BigDecimal("65.50"));
        return l;
    }

    private static PurchaseReceiptRequest receipt(PurchaseOrderResponse po, int lineIndex, int qty) {
        PurchaseReceiptRequest r = new PurchaseReceiptRequest();
        PurchaseReceiptRequest.Line l = new PurchaseReceiptRequest.Line();
        l.setPurchaseOrderLineId(po.getLines().get(lineIndex).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        r.setLines(List.of(l));
        return r;
    }

    private static BillRequest bill(String number) {
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber(number);
        return b;
    }

    private static BillRequest.Line billLine(PurchaseOrderResponse po, int lineIndex, int qty, String price) {
        BillRequest.Line l = new BillRequest.Line();
        l.setPurchaseOrderLineId(po.getLines().get(lineIndex).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        if (price != null) l.setUnitPrice(new BigDecimal(price));
        return l;
    }
}
