package com.experterp.backend.flow;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.*;
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

/**
 * Review flow test F2, procure to pay (docs/review-and-test-plan.md, Part 3): one purchase from the reorder list to
 * the last payment on fixed figures, then the stock, its cost and what the supplier is owed must agree everywhere.
 *
 * Reorder: 100 gloves at £2.00 and 20 vests at £10.00 from Portwest, one purchase order; £100.00 deposit paid.
 * Received: 60 gloves and 22 vests (2 over), then 30 gloves; the last 10 gloves closed short. 10 gloves sold meanwhile.
 * Bill: 90 gloves at £2.20 (not £2.00), 22 vests at £10.00, freight £41.80 spread by value (landed cost on):
 * gloves £2.42, vests £11.00 a unit. £459.80 + VAT £91.96 = £551.76.
 * Supplier credit for the price of 2 vests, goods kept (£20.00 + VAT); 5 gloves sent back (£11.00 + VAT).
 * The deposit is used on the bill and the rest paid: the supplier is owed nothing; stock £403.50, cost of goods £25.30.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProcureToPayFlowTest {

    @Autowired DashboardService dashboardService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired PurchaseLedgerService purchaseLedger;
    @Autowired SalesLedgerService salesLedger;
    @Autowired StockAdjustmentService stockSettings;
    @Autowired SaleOrderService saleOrderService;
    @Autowired ProductService productService;
    @Autowired BinService bins;
    @Autowired LedgerService ledger;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    String id;
    Long tenantId;
    Warehouse main;
    Supplier portwest;
    Product gloves, vest;
    Long bank;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Flow F2 " + id);
        t.setSlug("flow-f2-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("flow", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        portwest = new Supplier();
        portwest.setName("Portwest " + id);
        portwest = supplierRepository.save(portwest);
        gloves = product("GLOVES", 50, 100, "2.00");
        vest = product("VEST", 10, 20, "10.00");
        BinService.Bin a = bins.saveBin(null, new BinService.BinRequest(main.getId(), "A-01", 10, null));
        bins.setDefaultBin(gloves.getId(), main.getId(), a.id());
        bank = salesLedger.saveAccounts(List.of(new SalesLedgerService.AccountRequest(null, "650", "Current Account - RBS",
                PaymentAccount.Kind.BANK, true, true))).get(0).id();
        stockSettings.setLandedCost("VALUE");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void fromTheReorderListToTheLastPaymentTheStockItsCostAndWhatIsOwedAgree() {
        // ---- reorder: both products are below their minimum; one draft order at the supplier's prices ----
        assertThat(dashboardService.reorder(null, portwest.getId(), false, 0, 50).getContent())
                .extracting(DashboardResponse.ReorderLine::sku).containsExactlyInAnyOrder(gloves.getSku(), vest.getSku());
        ReorderRequest reorder = new ReorderRequest();
        reorder.setWarehouseId(main.getId());
        reorder.setLines(List.of(reorderLine(gloves, 100), reorderLine(vest, 20)));
        PurchaseOrderResponse po = purchaseOrderService.createFromReorder(reorder).get(0);
        Long poId = po.getId();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrder.Status.DRAFT);
        assertThat(po.getLines()).extracting(l -> l.getUnitCost().toPlainString()).allMatch(c -> c.startsWith("2.0") || c.startsWith("10.0"));

        // ---- £100 deposit with the order, then ordered ----
        purchaseLedger.payDeposit(portwest.getId(), new PurchaseLedgerService.DepositRequest(new BigDecimal("100.00"), today, bank, "BACS 1", poId, null));
        assertThat(purchaseLedger.balance(portwest.getId())).isEqualByComparingTo("-100.00");
        po = purchaseOrderService.markOrdered(poId);
        Long glovesLine = lineOf(po, gloves), vestLine = lineOf(po, vest);

        // ---- received in parts: 60 gloves and 22 vests (2 over), 30 gloves, the last 10 closed short ----
        purchaseOrderService.receive(poId, receipt(qty(glovesLine, 60), qty(vestLine, 22)));
        assertThat(onHand(gloves)).isEqualByComparingTo("60");
        assertThat(onHand(vest)).isEqualByComparingTo("22");
        purchaseOrderService.receive(poId, receipt(qty(glovesLine, 30)));
        PurchaseOrderResponse closed = purchaseOrderService.closeShort(poId);
        assertThat(closed.getStatus()).isEqualTo(PurchaseOrder.Status.RECEIVED);
        assertThat(closed.getLines().stream().filter(l -> l.getId().equals(glovesLine)).findFirst().orElseThrow().getClosedShortQuantity()).isEqualByComparingTo("10");
        assertThat(binQuantity(gloves, "A-01")).isEqualByComparingTo("90");               // into the product's own bin
        assertThat(value(gloves)).isEqualByComparingTo("180.00");                         // at the order's £2.00 until the bill

        // ---- 10 gloves are sold before the supplier's bill arrives ----
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName("Abbey " + id);
        so.setWarehouseId(main.getId());
        SaleOrderRequest.Line sl = new SaleOrderRequest.Line();
        sl.setProductId(gloves.getId());
        sl.setQuantity(BigDecimal.TEN);
        sl.setUnitPrice(new BigDecimal("5.00"));
        so.setLines(List.of(sl));
        Long saleId = saleOrderService.confirm(saleOrderService.create(so).getId()).getId();
        saleOrderService.fulfill(saleId, null);
        assertThat(saleOrderService.findById(saleId).getCostOfGoods()).isEqualByComparingTo("20.00");

        // ---- the bill: gloves at £2.20, freight £41.80 spread by value (10 % on top of each line) ----
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber("PW-" + id);
        b.setLines(List.of(billLine(glovesLine, 90, "2.20"), billLine(vestLine, 22, "10.00")));
        BillRequest.Charge freight = new BillRequest.Charge();
        freight.setDescription("Freight");
        freight.setUnitPrice(new BigDecimal("41.80"));
        b.setCharges(List.of(freight));
        InvoiceResponse bill = invoiceService.billPurchaseOrder(poId, b);
        assertThat(bill.getSubtotal()).isEqualByComparingTo("459.80");
        assertThat(bill.getTaxTotal()).isEqualByComparingTo("91.96");
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("551.76");
        // The cost follows the bill: gloves £2.42, vests £11.00; the 10 gloves already sold take their £4.20 into cost of goods
        assertThat(value(gloves)).isEqualByComparingTo("193.60");                         // 80 × 2.42
        assertThat(value(vest)).isEqualByComparingTo("242.00");                           // 22 × 11.00
        assertThat(saleOrderService.findById(saleId).getCostOfGoods()).isEqualByComparingTo("24.20");

        // ---- supplier credit for the price of 2 vests, the goods kept: their cost goes down by £20.00 ----
        Long vestItem = itemOf(bill, vest), glovesItem = itemOf(bill, gloves);
        InvoiceResponse priceCredit = purchaseLedger.createCreditNote(bill.getId(), new PurchaseLedgerService.CreditNoteRequest("PW-CR1-" + id, null,
                List.of(new PurchaseLedgerService.CreditLine(vestItem, new BigDecimal("2"), false)), List.of(), "Price agreed lower", null, null, null));
        assertThat(priceCredit.getTotalAmount()).isEqualByComparingTo("24.00");
        assertThat(onHand(vest)).isEqualByComparingTo("22");
        assertThat(value(vest)).isEqualByComparingTo("222.00");

        // ---- 5 gloves sent back to the supplier ----
        InvoiceResponse goodsCredit = purchaseLedger.createCreditNote(bill.getId(), new PurchaseLedgerService.CreditNoteRequest("PW-CR2-" + id, null,
                List.of(new PurchaseLedgerService.CreditLine(glovesItem, new BigDecimal("5"), true)), List.of(), "Damaged in transit", null, null, null));
        assertThat(goodsCredit.getTotalAmount()).isEqualByComparingTo("13.20");
        assertThat(onHand(gloves)).isEqualByComparingTo("75");
        assertThat(value(gloves)).isEqualByComparingTo("181.50");                         // 75 × 2.42: they leave at what they cost

        // ---- the deposit is used on the bill and the rest is paid ----
        assertThat(invoiceService.findById(bill.getId()).getAmountPaid()).isEqualByComparingTo("37.20");   // the two credit notes
        assertThat(purchaseLedger.useSupplierCredit(bill.getId(), null).getAmountPaid()).isEqualByComparingTo("137.20");
        PaymentRequest rest = new PaymentRequest();
        rest.setAmount(new BigDecimal("414.56"));
        rest.setAccountId(bank);
        assertThat(invoiceService.addPayment(bill.getId(), rest).getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);

        // ==== what must agree at the end ====

        // Stock: the warehouse figure is the bins plus what is in no bin
        BinService.ProductBins g = bins.productBins(gloves.getId(), main.getId());
        assertThat(g.warehouseStock()).isEqualByComparingTo("75");
        assertThat(g.bins().stream().map(BinService.BinStock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add).add(g.unbinned())).isEqualByComparingTo("75");

        // Cost: the ledger's stock account is what the FIFO layers hold and what the product pages show
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
        assertThat(tb.warnings()).isEmpty();
        BigDecimal stockValue = value(gloves).add(value(vest));
        assertThat(stockValue).isEqualByComparingTo("403.50");                           // 181.50 + 222.00
        assertThat(balance(tb, "631")).isEqualByComparingTo(stockValue);
        assertThat(jdbc.queryForObject("SELECT ROUND(COALESCE(SUM(remaining * unit_cost), 0), 2) FROM cost_layers WHERE tenant_id = ?", BigDecimal.class, tenantId))
                .isEqualByComparingTo(stockValue);
        // Cost of goods: the 10 gloves sold (£24.20) and the freight on the 5 sent back that the supplier did not credit
        // (5 × £0.22 = £1.10: they left at £2.42, the credit note gave £2.20)
        assertThat(saleOrderService.findById(saleId).getCostOfGoods()).isEqualByComparingTo("24.20");
        assertThat(balance(tb, "310")).isEqualByComparingTo("25.30");

        // Money: input VAT on the bill less the two credit notes; the bank paid the deposit and the rest
        assertThat(balance(tb, "820")).isEqualByComparingTo("85.76");                    // 91.96 − 4.00 − 2.20
        assertThat(balance(tb, "650")).isEqualByComparingTo("-514.56");                  // 100.00 + 414.56

        // The supplier: owed nothing, on the supplier, the statement, aged creditors and the ledger
        assertThat(purchaseLedger.balance(portwest.getId())).isEqualByComparingTo("0");
        assertThat(purchaseLedger.statement(portwest.getId(), today).balance()).isEqualByComparingTo("0");
        assertThat(purchaseLedger.agedCreditors(null).totals().total()).isEqualByComparingTo("0");
        assertThat(balance(tb, "800").add(balance(tb, "615"))).isEqualByComparingTo("0"); // creditors + supplier deposits

        List<String> actions = jdbc.queryForList("SELECT DISTINCT entity_type || ':' || action FROM audit_log WHERE tenant_id = ?", String.class, tenantId);
        assertThat(actions).contains("INVENTORY:LANDED_COST", "PURCHASE_ORDER:CREATE", "SUPPLIER:SUPPLIER_DEPOSIT", "PURCHASE_ORDER:ORDER",
                "PURCHASE_ORDER:RECEIVE", "PURCHASE_ORDER:CLOSE_SHORT", "INVOICE:CREATE", "INVOICE:SUPPLIER_CREDIT_NOTE",
                "INVOICE:SUPPLIER_CREDIT_USED", "INVOICE:PAYMENT");
    }

    // ---- helpers ----

    private Product product(String sku, int min, int reorderQty, String supplierPrice) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        p.setDefaultSupplier(portwest);
        p.setMinStock(BigDecimal.valueOf(min));
        p.setReorderQuantity(BigDecimal.valueOf(reorderQty));
        p.getDetails().setSupplierPrice(new BigDecimal(supplierPrice));
        return productRepository.save(p);
    }

    private static ReorderRequest.Line reorderLine(Product p, int qty) {
        ReorderRequest.Line l = new ReorderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private static Long lineOf(PurchaseOrderResponse po, Product p) {
        return po.getLines().stream().filter(l -> p.getId().equals(l.getProductId())).findFirst().orElseThrow().getId();
    }

    private static Long itemOf(InvoiceResponse bill, Product p) {
        return bill.getItems().stream().filter(i -> p.getId().equals(i.getProductId())).findFirst().orElseThrow().getId();
    }

    private static PurchaseReceiptRequest.Line qty(Long lineId, int qty) {
        PurchaseReceiptRequest.Line l = new PurchaseReceiptRequest.Line();
        l.setPurchaseOrderLineId(lineId);
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private static PurchaseReceiptRequest receipt(PurchaseReceiptRequest.Line... lines) {
        PurchaseReceiptRequest r = new PurchaseReceiptRequest();
        r.setLines(List.of(lines));
        return r;
    }

    private static BillRequest.Line billLine(Long lineId, int qty, String price) {
        BillRequest.Line l = new BillRequest.Line();
        l.setPurchaseOrderLineId(lineId);
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        return l;
    }

    private BigDecimal onHand(Product p) {
        return productService.stock(p.getId()).onHand();
    }

    private BigDecimal value(Product p) {
        return productService.stock(p.getId()).stockValue();
    }

    private BigDecimal binQuantity(Product p, String code) {
        return bins.productBins(p.getId(), main.getId()).bins().stream().filter(b -> b.code().equals(code)).findFirst().orElseThrow().quantity();
    }

    private static BigDecimal balance(LedgerService.TrialBalance tb, String code) {
        return tb.rows().stream().filter(r -> r.code().equals(code)).findFirst()
                .map(r -> r.debit() != null ? r.debit() : r.credit().negate()).orElse(BigDecimal.ZERO);
    }
}
