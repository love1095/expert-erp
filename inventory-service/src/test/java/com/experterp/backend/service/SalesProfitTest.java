package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.SalesProfitService.CogsMode;
import com.experterp.backend.service.SalesProfitService.GroupBy;
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

/** Phase H4 (spec §6): sales profit like Cin7's Sales Profit Summary, and the inventory movement summary at FIFO cost. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SalesProfitTest {

    @Autowired SalesProfitService profit;
    @Autowired InventoryMovementService movements;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired CostLayerRebuildService rebuildService;
    @Autowired StockMovementService movementService;
    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Warehouse warehouse;
    Product helmet;
    String id;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        id = UUID.randomUUID().toString();
        t.setName("Profit " + id);
        t.setSlug("profit-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELMET-" + id.substring(0, 6));
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * 5 helmets in at £6, then 5 at £8. Abbey (Stephen) buys 4 at £10 + £5 carriage: shipped, invoiced, FIFO cost 4 × 6.
     * AE Yates (Dwaine) gets 3 at £12: shipped, not invoiced yet, cost 1 × 6 + 2 × 8 = 22. Abbey sends one helmet back on a
     * credit note: £10 off the sale, back into stock at the £6 it left at.
     */
    @Test
    void profitLikeCin7WithCogsWithinTheDatesOrAllCogsOfTheInvoices() {
        stockIn("6.00", 5);
        stockIn("8.00", 5);
        InvoiceResponse abbey = shipAndInvoice("Abbey Attachments " + id, "Stephen Seddon", 4, "10.00", true);
        shipAndInvoice("AE Yates " + id, "Dwaine Silverblade", 3, "12.00", false);
        CreditNoteRequest cn = new CreditNoteRequest();
        CreditNoteRequest.Line back = new CreditNoteRequest.Line();
        back.setInvoiceItemId(abbey.getItems().stream().filter(i -> i.getProductId() != null).findFirst().orElseThrow().getId());
        back.setQuantity(BigDecimal.ONE);
        back.setRestock(true);
        cn.setLines(List.of(back));
        cn.setReason("Wrong size");
        creditNoteService.create(abbey.getId(), cn);

        // Cin7's default: COGS of the goods that moved in the dates. AE Yates has cost and no invoice yet (like A Timmins)
        SalesProfitService.Report within = profit.report(today, today, CogsMode.WITHIN_DATES, GroupBy.CUSTOMER);
        assertThat(within.rows()).hasSize(2);
        SalesProfitService.Row a = within.rows().get(0);
        assertThat(a.customer()).startsWith("Abbey");
        assertThat(a.salesRep()).isEqualTo("Stephen Seddon");
        assertThat(a.sale()).isEqualByComparingTo("30.00");          // 40 − 10; carriage is not a product: left out
        assertThat(a.invoiced()).isEqualByComparingTo("36.00");      // with VAT
        assertThat(a.cogs()).isEqualByComparingTo("18.00");          // 24 shipped − 6 back
        assertThat(a.profit()).isEqualByComparingTo("12.00");
        assertThat(a.grossMargin()).isEqualByComparingTo("40.00");
        assertThat(a.markup()).isEqualByComparingTo("66.67");
        SalesProfitService.Row y = within.rows().get(1);
        assertThat(y.salesRep()).isEqualTo("Dwaine Silverblade");
        assertThat(y.sale()).isEqualByComparingTo("0.00");
        assertThat(y.cogs()).isEqualByComparingTo("22.00");
        assertThat(y.grossMargin()).isNull();                        // empty when the sale is zero, as in Cin7
        assertThat(within.totals().cogs()).isEqualByComparingTo("40.00");
        assertThat(within.totals().profit()).isEqualByComparingTo("-10.00");

        // All COGS: each invoice carries its own cost, the credit note the cost of what came back; no invoice, no row
        SalesProfitService.Report all = profit.report(today, today, CogsMode.ALL, GroupBy.ORDER);
        assertThat(all.rows()).singleElement().satisfies(r -> {
            assertThat(r.orderNumber()).isNotBlank();
            assertThat(r.invoices()).isEqualTo(abbey.getInvoiceNumber());
            assertThat(r.creditNotes()).startsWith("CR-");
            assertThat(r.cogs()).isEqualByComparingTo("18.00");
            assertThat(r.grossMargin()).isEqualByComparingTo("40.00");
        });

        // By rep and by product
        assertThat(profit.report(today, today, CogsMode.WITHIN_DATES, GroupBy.REP).rows())
                .extracting(SalesProfitService.Row::salesRep).containsExactly("Dwaine Silverblade", "Stephen Seddon");
        assertThat(profit.report(today, today, CogsMode.ALL, GroupBy.PRODUCT).rows()).singleElement().satisfies(r -> {
            assertThat(r.sku()).isEqualTo(helmet.getSku());
            assertThat(r.quantity()).isEqualByComparingTo("3");
            assertThat(r.sale()).isEqualByComparingTo("30.00");
        });

        // Another period: nothing
        assertThat(profit.report(today.plusDays(1), today.plusDays(30), CogsMode.WITHIN_DATES, GroupBy.CUSTOMER).rows()).isEmpty();
    }

    @Test
    void inventoryMovementSummaryAddsUpToTheFifoStockValue() {
        stockIn("6.00", 5);
        stockIn("8.00", 5);
        InvoiceResponse abbey = shipAndInvoice("Abbey Attachments " + id, "Stephen Seddon", 4, "10.00", true);
        shipAndInvoice("AE Yates " + id, "Dwaine Silverblade", 3, "12.00", false);
        CreditNoteRequest cn = new CreditNoteRequest();
        CreditNoteRequest.Line back = new CreditNoteRequest.Line();
        back.setInvoiceItemId(abbey.getItems().stream().filter(i -> i.getProductId() != null).findFirst().orElseThrow().getId());
        back.setQuantity(BigDecimal.ONE);
        back.setRestock(true);
        cn.setLines(List.of(back));
        creditNoteService.create(abbey.getId(), cn);

        InventoryMovementService.Row r = movements.report(today, today, null, null).rows().stream()
                .filter(x -> x.productId().equals(helmet.getId())).findFirst().orElseThrow();
        assertThat(r.openingQuantity()).isEqualByComparingTo("0");
        assertThat(r.inQuantity()).isEqualByComparingTo("11");         // 5 + 5 + 1 back
        assertThat(r.inValue()).isEqualByComparingTo("76.00");         // 30 + 40 + 6
        assertThat(r.outQuantity()).isEqualByComparingTo("7");
        assertThat(r.outValue()).isEqualByComparingTo("46.00");        // 24 + 22
        assertThat(r.closingQuantity()).isEqualByComparingTo("4");
        assertThat(r.closingValue()).isEqualByComparingTo("30.00");    // 3 × 8 + 1 × 6
        assertThat(r.closingValue()).isEqualByComparingTo(productService.stock(helmet.getId()).stockValue());

        // The next month opens with what this one closed with; nothing moved
        InventoryMovementService.Row next = movements.report(today.plusDays(1), today.plusDays(30), warehouse.getId(), null).rows()
                .stream().filter(x -> x.productId().equals(helmet.getId())).findFirst().orElseThrow();
        assertThat(next.openingQuantity()).isEqualByComparingTo("4");
        assertThat(next.openingValue()).isEqualByComparingTo("30.00");
        assertThat(next.inQuantity()).isEqualByComparingTo("0");
        assertThat(next.closingValue()).isEqualByComparingTo("30.00");
        assertThat(next.unitCost()).isEqualByComparingTo("7.50");
        // Another warehouse: not there
        assertThat(movements.report(today, today, warehouse.getId() + 999_999, null).rows()).isEmpty();
    }

    @Test
    void aBillAtAnotherPriceCorrectsCogsOnTheDayOfTheBillAlsoAfterARebuild() {
        Supplier supplier = new Supplier();
        supplier.setName("Portwest " + id);
        supplier = supplierRepository.save(supplier);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(supplier.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(new BigDecimal("2"));
        l.setUnitCost(new BigDecimal("6.00"));
        req.setLines(List.of(l));
        PurchaseOrderResponse po = purchaseOrderService.create(req);
        purchaseOrderService.receive(po.getId());
        shipAndInvoice("Abbey Attachments " + id, "Stephen Seddon", 1, "10.00", true);

        // Billed at £7: the helmet sold costs £1 more, the one in stock too
        po = purchaseOrderService.findById(po.getId());
        BillRequest bill = new BillRequest();
        bill.setSupplierInvoiceNumber("B-1");
        BillRequest.Line bl = new BillRequest.Line();
        bl.setPurchaseOrderLineId(po.getLines().get(0).getId());
        bl.setQuantity(new BigDecimal("2"));
        bl.setUnitPrice(new BigDecimal("7.00"));
        bill.setLines(List.of(bl));
        invoiceService.billPurchaseOrder(po.getId(), bill);
        assertThat(profit.report(today, today, CogsMode.WITHIN_DATES, GroupBy.CUSTOMER).totals().cogs()).isEqualByComparingTo("7.00");

        // A rebuild dates the corrections when the bill was entered, not when the rebuild ran
        rebuildService.rebuild();
        Long late = jdbc.queryForObject("SELECT COUNT(*) FROM cost_adjustments a JOIN invoice_items it ON it.id = a.invoice_item_id" +
                " JOIN invoices i ON i.id = it.invoice_id WHERE a.tenant_id = ? AND a.created_at <> i.created_at", Long.class, TenantContext.get());
        assertThat(late).isZero();
        assertThat(profit.report(today, today, CogsMode.ALL, GroupBy.CUSTOMER).totals().cogs()).isEqualByComparingTo("7.00");
        assertThat(movements.report(today, today, null, null).totals().closingValue()).isEqualByComparingTo("7.00");
    }

    private void stockIn(String cost, int qty) {
        movementService.createMovement(helmet, warehouse, BigDecimal.valueOf(qty), MovementType.ADJUSTMENT_IN,
                ReferenceType.MANUAL, "IN", null, null, new BigDecimal(cost));
    }

    private InvoiceResponse shipAndInvoice(String customer, String rep, int qty, String price, boolean invoice) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName(customer);
        req.setSalesRep(rep);
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        req.setLines(List.of(l));
        if (invoice) {
            SaleOrderRequest.Charge c = new SaleOrderRequest.Charge();
            c.setDescription("Carriage");
            c.setUnitPrice(new BigDecimal("5"));
            req.setCharges(List.of(c));
        }
        SaleOrderResponse order = saleOrderService.confirm(saleOrderService.create(req).getId());
        saleOrderService.fulfill(order.getId(), null);
        if (!invoice) return null;
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        return invoiceService.invoiceOrder(order.getId(), all);
    }
}
