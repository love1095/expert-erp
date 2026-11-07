package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase H2 (spec §6): supplier credit notes (goods sent back at their own cost), deposits, aged creditors. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchaseLedgerTest {

    @Autowired CostLayerRebuildService rebuild;
    @Autowired PurchaseLedgerService ledger;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired ProductService productService;
    @Autowired StockMovementRepository movementRepository;
    @Autowired CostingService costingService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Warehouse warehouse;
    Supplier portwest;
    Product vest;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Purchases " + id);
        t.setSlug("purchases-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse " + id);
        warehouse = warehouseRepository.save(warehouse);
        portwest = new Supplier();
        portwest.setName("Portwest Clothing Ltd");
        portwest = supplierRepository.save(portwest);
        vest = new Product();
        vest.setSku("HVRW-" + id.substring(0, 6));
        vest.setName("Hi-vis vest");
        vest.setUnit("Each");
        vest = productRepository.save(vest);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aCreditForThePriceWithTheGoodsKeptLowersTheirFifoCost() {
        PurchaseOrderResponse po = received("65.50", 3);
        InvoiceResponse bill = bill(po, "PRICE-1");
        // One vest's worth (£65.50) credited, the goods kept: the 3 vests now cost £131.00, £43.67 each
        ledger.createCreditNote(bill.getId(), new PurchaseLedgerService.CreditNoteRequest(null, null,
                List.of(new PurchaseLedgerService.CreditLine(bill.getItems().get(0).getId(), BigDecimal.ONE, false)), List.of(),
                "Price agreed lower", null, null, null));
        ProductStockResponse stock = productService.stock(vest.getId());
        assertThat(stock.onHand()).isEqualByComparingTo("3");
        assertThat(stock.stockValue()).isEqualByComparingTo("131.00");
        assertThat(movementRepository.findAll().stream().noneMatch(m -> m.getType() == MovementType.PURCHASE_RETURN_OUT)).isTrue();
        // Rebuilding the FIFO layers keeps it
        rebuild.rebuild();
        assertThat(productService.stock(vest.getId()).stockValue()).isEqualByComparingTo("131.00");
    }

    @Test
    void goodsSentBackLeaveAtTheCostTheyCameInAtAndTheCreditNotePaysOffTheBill() {
        received("60.00", 3);                                        // older stock, cheaper
        PurchaseOrderResponse po = received("65.50", 3);             // this order: 3 × 65.50 + VAT = 235.80
        InvoiceResponse bill = bill(po, "006387333");
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("235.80");

        InvoiceResponse cn = ledger.createCreditNote(bill.getId(), new PurchaseLedgerService.CreditNoteRequest("CR-5521", null,
                List.of(new PurchaseLedgerService.CreditLine(bill.getItems().get(0).getId(), BigDecimal.ONE, true)), List.of(),
                "Damaged in transit", null, null, null));
        assertThat(cn.getInvoiceNumber()).isEqualTo("CR-5521");
        assertThat(cn.getTotalAmount()).isEqualByComparingTo("78.60");
        assertThat(cn.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(invoiceService.findById(bill.getId()).getAmountPaid()).isEqualByComparingTo("78.60");

        // One vest back to Portwest, at this order's 65.50, not FIFO's oldest 60.00
        StockMovement back = movementRepository.findAll().stream().filter(m -> m.getType() == MovementType.PURCHASE_RETURN_OUT).findFirst().orElseThrow();
        assertThat(costingService.costOfMovement(back.getId())).isEqualByComparingTo("65.50");
        ProductStockResponse stock = productService.stock(vest.getId());
        assertThat(stock.onHand()).isEqualByComparingTo("5");
        assertThat(stock.stockValue()).isEqualByComparingTo("311.00");      // 3 × 60 + 2 × 65.50

        // Not more back than came in; not more credited than billed
        assertThatThrownBy(() -> ledger.createCreditNote(bill.getId(), new PurchaseLedgerService.CreditNoteRequest(null, null,
                List.of(new PurchaseLedgerService.CreditLine(bill.getItems().get(0).getId(), new BigDecimal("3"), false)), List.of(),
                null, null, null, null))).hasMessageContaining("only 2 billed and not credited yet");
        assertThat(ledger.balance(portwest.getId())).isEqualByComparingTo("157.20");
    }

    @Test
    void aDepositAndAnOverpaymentAreSupplierCreditUsedOnTheNextBill() {
        PurchaseOrderResponse po = received("65.50", 3);
        ledger.payDeposit(portwest.getId(), new PurchaseLedgerService.DepositRequest(new BigDecimal("100"), today.minusDays(5), null,
                "Proforma 88", po.getId(), null));
        assertThat(ledger.balance(portwest.getId())).isEqualByComparingTo("-100");
        InvoiceResponse bill = bill(po, "INV-1");
        InvoiceResponse used = ledger.useSupplierCredit(bill.getId(), null);
        assertThat(used.getAmountPaid()).isEqualByComparingTo("100");
        assertThat(used.getPayments()).singleElement().satisfies(p -> assertThat(p.getMethod()).isEqualTo(Payment.PaymentMethod.SUPPLIER_CREDIT));

        // 200 paid against 135.80 still due: 64.20 stays with Portwest
        PaymentRequest pay = new PaymentRequest();
        pay.setAmount(new BigDecimal("200"));
        pay.setMethod(Payment.PaymentMethod.BANK);
        assertThat(invoiceService.addPayment(bill.getId(), pay).getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(ledger.credits(portwest.getId())).extracting(PurchaseLedgerService.CreditView::available)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(BigDecimal.ZERO, new BigDecimal("64.20"));
        assertThat(ledger.balance(portwest.getId())).isEqualByComparingTo("-64.20");

        // Paid on the next bill
        PurchaseOrderResponse next = received("65.50", 1);
        InvoiceResponse second = bill(next, "INV-2");
        assertThat(ledger.useSupplierCredit(second.getId(), null).getAmountPaid()).isEqualByComparingTo("64.20");
        assertThat(ledger.balance(portwest.getId())).isEqualByComparingTo("14.40");    // 78.60 − 64.20
    }

    @Test
    void aKeptCreditNoteIsAllocatedToAnotherBillOrRefundedByTheSupplier() {
        InvoiceResponse first = bill(received("65.50", 2), "INV-1");
        PaymentRequest pay = new PaymentRequest();
        pay.setAmount(first.getTotalAmount());
        pay.setMethod(Payment.PaymentMethod.BANK);
        invoiceService.addPayment(first.getId(), pay);
        // A rebate on a paid bill, kept as credit
        InvoiceResponse cn = ledger.createCreditNote(first.getId(), new PurchaseLedgerService.CreditNoteRequest(null, today, List.of(),
                List.of(new PurchaseLedgerService.CreditCharge("Volume rebate", BigDecimal.ONE, new BigDecimal("10"), null)), null,
                PurchaseLedgerService.Settle.KEEP_AS_CREDIT, null, null));
        assertThat(cn.getInvoiceNumber()).isEqualTo("INV-1-CR1");
        assertThat(cn.getTotalAmount()).isEqualByComparingTo("12.00");
        assertThat(ledger.openCreditNotes(portwest.getId())).singleElement().satisfies(c -> assertThat(c.available()).isEqualByComparingTo("12"));

        InvoiceResponse second = bill(received("65.50", 1), "INV-2");
        assertThat(ledger.allocateCreditNote(second.getId(), cn.getId(), null).getAmountPaid()).isEqualByComparingTo("12");
        assertThat(invoiceService.findById(cn.getId()).getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);

        // Refunded: the supplier pays the money back
        InvoiceResponse refunded = ledger.createCreditNote(first.getId(), new PurchaseLedgerService.CreditNoteRequest(null, today, List.of(),
                List.of(new PurchaseLedgerService.CreditCharge("Overcharge", BigDecimal.ONE, new BigDecimal("5"), null)), null,
                PurchaseLedgerService.Settle.REFUND, null, "BACS in 991"));
        assertThat(refunded.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(refunded.getPayments()).singleElement().satisfies(p -> assertThat(p.getReference()).isEqualTo("BACS in 991"));
        assertThat(ledger.balance(portwest.getId())).isEqualByComparingTo("66.60");     // 78.60 − 12
    }

    @Test
    void agedCreditorsAgesWhatIsOwedBySupplierByTheBillsDueDates() {
        InvoiceResponse late = bill(received("65.50", 3), "INV-OLD");
        jdbc.update("UPDATE invoices SET issue_date = ?, due_date = ? WHERE id = ?", today.minusDays(70), today.minusDays(40), late.getId());
        bill(received("65.50", 1), "INV-NEW");
        Supplier other = new Supplier();
        other.setName("Deposits Ltd");
        Long otherId = supplierRepository.save(other).getId();
        ledger.payDeposit(otherId, new PurchaseLedgerService.DepositRequest(new BigDecimal("30"), today, null, null, null, null));

        PurchaseLedgerService.AgedCreditors r = ledger.agedCreditors(null);
        assertThat(r.suppliers()).extracting(PurchaseLedgerService.AgedCreditor::supplierName).containsExactly("Portwest Clothing Ltd", "Deposits Ltd");
        assertThat(r.suppliers().get(0).days31to60()).isEqualByComparingTo("235.80");
        assertThat(r.suppliers().get(0).total()).isEqualByComparingTo("314.40");
        assertThat(r.suppliers().get(1).credit()).isEqualByComparingTo("-30");
        assertThat(r.totals().total()).isEqualByComparingTo("284.40");
    }

    // ---- helpers ----

    private PurchaseOrderResponse received(String cost, int qty) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(portwest.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(vest.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitCost(new BigDecimal(cost));
        req.setLines(List.of(l));
        PurchaseOrderResponse po = purchaseOrderService.create(req);
        purchaseOrderService.receive(po.getId());
        return purchaseOrderService.findById(po.getId());
    }

    private InvoiceResponse bill(PurchaseOrderResponse po, String number) {
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber(number);
        return invoiceService.billPurchaseOrder(po.getId(), b);
    }
}
