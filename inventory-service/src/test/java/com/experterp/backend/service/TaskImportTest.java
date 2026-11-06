package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.TaskImportService.Kind;
import com.experterp.backend.service.TaskImportService.Options;
import com.experterp.backend.service.TaskImportService.Fulfilment;
import com.experterp.backend.service.TaskImportService.InvoiceStatus;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase J: Cin7's sale / purchase task CSV import (open orders, unpaid invoices and bills) and the go-live check. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskImportTest {

    @Autowired TaskImportService imports;
    @Autowired CutoverCheckService cutover;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PaymentAccountRepository paymentAccountRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;
    Warehouse warehouse;
    String id;
    String day = LocalDate.now().minusDays(7).toString();
    String paidDay = LocalDate.now().minusDays(2).toString();

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Import " + id);
        t.setSlug("import-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        Product helmet = new Product();
        helmet.setSku("HELMET-" + id);
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL,
                "OPENING", null, null, new BigDecimal("6.00"));
        Customer abbey = new Customer();
        abbey.setName("Abbey " + id);
        customerRepository.save(abbey);
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        supplierRepository.save(portwest);
        PaymentAccount bank = new PaymentAccount();
        bank.setCode("650");
        bank.setName("Current Account");
        paymentAccountRepository.save(bank);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void openOrdersAndUnpaidInvoicesComeOverFromCin7sSaleFile() throws Exception {
        // An open order (no invoice yet) keeps Cin7's number and reserves its stock; nothing is written by the preview
        String orders = """
                RecordType*,CustomerName*,InvoiceNumber*,Reference/Comment/Note,Product*,Quantity*,Price/Amount*,Discount,Total*,InvoiceDate*/ExpireDate,TaxInclusive*
                Invoice,Abbey %1$s,SO-9%1$s,PO-123,,,,,,%2$s,FALSE
                InvoiceLines,Abbey %1$s,SO-9%1$s,,HELMET-%1$s,3,10.00,,30.00,,
                """.formatted(id, day);
        Options openOrders = new Options(InvoiceStatus.NONE, Fulfilment.NO_PICKING, false, false, false, true, warehouse.getId());
        TaskImportService.Result preview = sales(orders, openOrders, false);
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.applied()).isFalse();
        assertThat(preview.docs()).singleElement().satisfies(d -> assertThat(d.orderNumber()).isEqualTo("SO-9" + id));
        assertThat(count("SELECT COUNT(*) FROM sale_orders WHERE tenant_id = ?")).isZero();

        TaskImportService.Result done = sales(orders, openOrders, true);
        assertThat(done.applied()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM sale_orders WHERE tenant_id = ? AND order_number = ?", String.class,
                tenantId, "SO-9" + id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT customer_reference FROM sale_orders WHERE tenant_id = ?", String.class, tenantId)).isEqualTo("PO-123");
        assertThat(jdbc.queryForObject("SELECT ordered_at::date FROM sale_orders WHERE tenant_id = ?", LocalDate.class, tenantId))
                .isEqualTo(LocalDate.parse(day));

        // Only new tasks (as Cin7): the same file again is refused, nothing doubled
        TaskImportService.Result again = sales(orders, openOrders, true);
        assertThat(again.applied()).isFalse();
        assertThat(again.errors()).singleElement().asString().contains("already exists");
        assertThat(count("SELECT COUNT(*) FROM sale_orders WHERE tenant_id = ?")).isEqualTo(1);

        // An unpaid invoice shipped in Cin7, "Export imported tasks to Xero" unticked: Cin7's number and dates,
        // £10 paid into 650 (Payment's Price/Amount), no stock moved, recorded as already in Xero
        String invoices = """
                RecordType*,CustomerName*,InvoiceNumber*,Product*,Quantity*,Price/Amount*,Discount,Total*,Account,DatePaid*,InvoiceDate*/ExpireDate,InvoiceDueDate,TaxInclusive*
                Invoice,Abbey %1$s,INV-8%1$s,,,,,,,,%2$s,%3$s,no
                InvoiceLines,Abbey %1$s,INV-8%1$s,HELMET-%1$s,2,12.50,20,20.00,,,,,
                InvoiceAdditionalCharges,Abbey %1$s,INV-8%1$s,Carriage,1,5.00,,5.00,,,,,
                Payment,Abbey %1$s,INV-8%1$s,,,10.00,,,650,%4$s,,,
                """.formatted(id, day, LocalDate.now().plusDays(23), paidDay);
        TaskImportService.Result inv = sales(invoices,
                new Options(InvoiceStatus.AUTHORISED, Fulfilment.ALREADY_SHIPPED, false, false, false, false, warehouse.getId()), true);
        assertThat(inv.errors()).isEmpty();
        assertThat(inv.paid()).isEqualByComparingTo("10.00");
        assertThat(inv.warnings()).anyMatch(w -> w.contains("recorded as already in Xero"));
        Long invoiceId = jdbc.queryForObject("SELECT id FROM invoices WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(jdbc.queryForMap("SELECT invoice_number, issue_date, due_date, status, subtotal FROM invoices WHERE id = ?", invoiceId))
                .containsEntry("invoice_number", "INV-8" + id).containsEntry("status", "ISSUED")
                .containsEntry("issue_date", java.sql.Date.valueOf(day))
                .containsEntry("due_date", java.sql.Date.valueOf(LocalDate.now().plusDays(23)))
                .satisfies(m -> assertThat((BigDecimal) m.get("subtotal")).isEqualByComparingTo("25.00")); // 2 × 12.50 less 20 % + 5
        assertThat(count("SELECT COUNT(*) FROM stock_movements WHERE tenant_id = ?")).isEqualTo(1);   // opening only
        assertThat(jdbc.queryForObject("SELECT status FROM sale_orders WHERE tenant_id = ? AND order_number <> ?", String.class,
                tenantId, "SO-9" + id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("SELECT doc_key FROM accounting_exported WHERE tenant_id = ?", String.class, tenantId))
                .contains("DOC:" + invoiceId).anyMatch(k -> k.startsWith("PAY:"));

        // The go-live check: 10 units at £6, one open order (3 × £10 to ship), the invoice less £10 owed
        CutoverCheckService.Check check = cutover.check();
        assertThat(figure(check, "units-" + warehouse.getId())).isEqualByComparingTo("10");
        assertThat(figure(check, "value-" + warehouse.getId())).isEqualByComparingTo("60.00");
        assertThat(figure(check, "open-sales")).isEqualByComparingTo("1");
        assertThat(figure(check, "open-sales-value")).isEqualByComparingTo("30.00");
        BigDecimal total = jdbc.queryForObject("SELECT total_amount FROM invoices WHERE id = ?", BigDecimal.class, invoiceId);
        assertThat(figure(check, "receivables")).isEqualByComparingTo(total.subtract(BigDecimal.TEN));
        assertThat(check.warnings()).anyMatch(w -> w.contains("No Xero export start date"));
    }

    /**
     * Cin7's rules: a customer not found is created with its address and contact; Pick + Pack + Ship ships; a credit note
     * with a refund needs that; short of stock is an error unless "backordered on low stock" is ticked.
     */
    @Test
    void cin7sSaleRulesNewCustomerShippingCreditNoteRefundBackorder() throws Exception {
        String file = """
                RecordType*,CustomerName*,InvoiceNumber*,Product*,Quantity*,Price/Amount*,Total*,Account,DatePaid*,CustomerContact,CustomerPhone,Terms,InvoiceDate*/ExpireDate,TaxInclusive*,ShippingAddressLine1*,ShippingCity*,ShippingPostcode*,ShippingCountry*,CreditNoteNumber,CreditNoteDate
                Invoice,Newco %1$s,INV-7%1$s,,,,,,,Jo Bloggs,0161 000,30 days,%2$s,false,1 Mill Lane,Bolton,BL1 1AA,United Kingdom,,
                InvoiceLines,Newco %1$s,INV-7%1$s,HELMET-%1$s,2,10.00,20.00,,,,,,,,,,,,,
                CreditLines,Newco %1$s,INV-7%1$s,HELMET-%1$s,1,10.00,10.00,,,,,,,,,,,,CR-7%1$s,%3$s
                Refund,Newco %1$s,INV-7%1$s,,,12.00,,650,%3$s,,,,,,,,,,,
                """.formatted(id, day, paidDay);
        Options ship = new Options(InvoiceStatus.AUTHORISED, Fulfilment.AUTO_PICK_PACK_SHIP, false, false, false, true, warehouse.getId());
        TaskImportService.Result r = sales(file, ship, true);
        assertThat(r.errors()).isEmpty();
        assertThat(r.newParties()).containsExactly("Newco " + id);
        assertThat(r.docs()).singleElement().satisfies(d -> assertThat(d.creditNoteNumber()).isEqualTo("CR-7" + id));
        Customer newco = customerRepository.findFirstByNameIgnoreCaseOrderByIdAsc("Newco " + id).orElseThrow();
        assertThat(newco.getPaymentTerm()).isEqualTo("30 days");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_addresses WHERE customer_id = ? AND type = 'SHIPPING' AND default_for_type",
                Integer.class, newco.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phone FROM customer_contacts WHERE customer_id = ?", String.class, newco.getId())).isEqualTo("0161 000");
        // Shipped: 2 out of 10; the credit note (1 × £10 + VAT) refunded into 650 on the Refund's date
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN type = 'SALE_OUT' THEN quantity END), 0) FROM stock_movements WHERE tenant_id = ?",
                BigDecimal.class, tenantId)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForMap("""
                SELECT i.total_amount, i.status, i.issue_date, p.paid_at::date AS paid, p.payment_account_id
                  FROM invoices i JOIN payments p ON p.invoice_id = i.id WHERE i.tenant_id = ? AND i.invoice_number = ?""", tenantId, "CR-7" + id))
                .satisfies(m -> assertThat((BigDecimal) m.get("total_amount")).isEqualByComparingTo("12.00"))
                .containsEntry("status", "PAID").containsEntry("issue_date", java.sql.Date.valueOf(paidDay))
                .containsEntry("paid", java.sql.Date.valueOf(paidDay));

        // 50 more when 8 are left: an error, unless backordered on low stock is ticked
        String big = """
                RecordType,CustomerName,InvoiceNumber,Product,Quantity,Price/Amount,InvoiceDate*/ExpireDate
                Invoice,Abbey %1$s,INV-6%1$s,,,,%2$s
                InvoiceLines,Abbey %1$s,INV-6%1$s,HELMET-%1$s,50,10,
                """.formatted(id, day);
        assertThat(sales(big, ship, true).errors()).singleElement().asString().contains("not enough stock");
        TaskImportService.Result backordered = sales(big,
                new Options(InvoiceStatus.AUTHORISED, Fulfilment.AUTO_PICK_PACK_SHIP, true, false, false, true, warehouse.getId()), true);
        assertThat(backordered.applied()).isTrue();
        assertThat(backordered.warnings()).anyMatch(w -> w.contains("imported backordered"));
    }

    @Test
    void errorsStopTheWholeFile() throws Exception {
        String file = """
                RecordType,CustomerName,InvoiceNumber,Product,Quantity,Price/Amount,Total,InvoiceDate*/ExpireDate,CreditNoteNumber,CreditNoteDate
                Invoice,Abbey %1$s,SO-A%1$s,,,,,%2$s,,
                InvoiceLines,Abbey %1$s,SO-A%1$s,HELMET-%1$s,1,10,,,,
                Invoice,Abbey %1$s,SO-B%1$s,,,,,%3$s,,
                InvoiceLines,Abbey %1$s,SO-B%1$s,HELMET-%1$s,1,10,,,,
                Invoice,Abbey %1$s,SO-C%1$s,,,,,%2$s,,
                InvoiceLines,Abbey %1$s,SO-C%1$s,NOPE-%1$s,1,10,,,,
                Invoice,Abbey %1$s,SO-D%1$s,,,,,%2$s,,
                InvoiceLines,Abbey %1$s,SO-D%1$s,HELMET-%1$s,3,10,31.00,,,
                Invoice,Abbey %1$s,SO-E%1$s,,,,,%2$s,,
                InvoiceLines,Abbey %1$s,SO-E%1$s,HELMET-%1$s,1,10,,,,
                CreditLines,Abbey %1$s,SO-E%1$s,HELMET-%1$s,1,10,,,CR-E,%2$s
                Restock,Abbey %1$s,SO-E%1$s,HELMET-%1$s,1,10,,,,
                """.formatted(id, day, LocalDate.now().plusDays(3));
        TaskImportService.Result r = sales(file, new Options(InvoiceStatus.NONE, Fulfilment.NO_PICKING, false, false, false, true, null), true);
        assertThat(r.applied()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("Restock"))
                .anyMatch(e -> e.contains("SO-B" + id) && e.contains("in the future"))
                .anyMatch(e -> e.contains("NOPE-" + id + " not found"))
                .anyMatch(e -> e.contains("Total 31.00 should be 30.00"))
                .anyMatch(e -> e.contains("SO-E" + id) && e.contains("credit notes and refunds need"));
        assertThat(count("SELECT COUNT(*) FROM sale_orders WHERE tenant_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM data_import_logs WHERE tenant_id = ?")).isZero();
    }

    @Test
    void openPurchaseOrdersAndBillsWithReceivedCreditAndUnstock() throws Exception {
        String orders = """
                RecordType*,SupplierName*,InvoiceNumber*,Product*,Quantity*,Price/Amount*,Total*,InvoiceDate*/ExpireDate,TaxInclusive*
                Invoice,Portwest %1$s,PO-7%1$s,,,,,%2$s,false
                InvoiceLines,Portwest %1$s,PO-7%1$s,HELMET-%1$s,20,5.00,100.00,,
                """.formatted(id, day);
        TaskImportService.Result po = imports.importCsv(Kind.PURCHASES, in(orders), "PurchaseTasks.csv",
                new Options(InvoiceStatus.NONE, null, false, false, false, true, null), true);
        assertThat(po.errors()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE tenant_id = ? AND order_number = ?", String.class,
                tenantId, "PO-7" + id)).isEqualTo("ORDERED");

        // A bill: 4 received on a day (Received rows), 2 credited and sent back (CreditLines + Unstock); a new supplier is created
        String bills = """
                RecordType*,SupplierName*,InvoiceNumber*,OrderNumber,Product*,Quantity*,Price/Amount*,Total*,DatePaid/DateReceived*,InvoiceDate*/ExpireDate,InvoiceDueDate,TaxInclusive*,CreditNoteNumber^,CreditNoteDate^
                Invoice,Arco %1$s,006387333,PO-6%1$s,,,,,,%2$s,%2$s,false,,
                InvoiceLines,Arco %1$s,006387333,,HELMET-%1$s,4,5.00,20.00,,,,,,
                Received,Arco %1$s,006387333,,HELMET-%1$s,4,,,%2$s,,,,,
                CreditLines,Arco %1$s,006387333,,HELMET-%1$s,2,5.00,10.00,,,,,SC-1,%3$s
                Unstock,Arco %1$s,006387333,,HELMET-%1$s,2,,,%3$s,,,,,
                """.formatted(id, day, paidDay);
        TaskImportService.Result bill = imports.importCsv(Kind.PURCHASES, in(bills), "Bills.csv",
                new Options(InvoiceStatus.AUTHORISED, null, false, false, false, true, warehouse.getId()), true);
        assertThat(bill.errors()).isEmpty();
        assertThat(bill.newParties()).containsExactly("Arco " + id);
        assertThat(bill.docs()).singleElement().satisfies(d -> {
            assertThat(d.orderNumber()).isEqualTo("PO-6" + id);
            assertThat(d.invoiceNumber()).isEqualTo("006387333");
            assertThat(d.creditNoteNumber()).isEqualTo("SC-1");
        });
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE tenant_id = ? AND order_number = ?", String.class,
                tenantId, "PO-6" + id)).isEqualTo("RECEIVED");
        // 10 opening + 4 received − 2 sent back
        assertThat(jdbc.queryForObject("SELECT SUM(CASE WHEN type IN (" + com.experterp.backend.repository.StockMovementRepository.IN_TYPES
                + ") THEN quantity ELSE -quantity END) FROM stock_movements WHERE tenant_id = ?", BigDecimal.class, tenantId)).isEqualByComparingTo("12");
        assertThat(imports.logs()).hasSize(2);

        CutoverCheckService.Check check = cutover.check();
        assertThat(figure(check, "open-purchases")).isEqualByComparingTo("1");
        assertThat(figure(check, "open-purchases-value")).isEqualByComparingTo("100.00");
        BigDecimal owed = jdbc.queryForObject("""
                SELECT SUM(CASE WHEN kind = 'CREDIT_NOTE' THEN -total_amount ELSE total_amount END) FROM invoices WHERE tenant_id = ?""",
                BigDecimal.class, tenantId);
        assertThat(figure(check, "payables")).isEqualByComparingTo(owed);
    }

    @Test
    void cin7sDatesAndHeadingsAreRead() {
        assertThat(TaskImportService.date("30/10/2026", "x")).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(TaskImportService.date("2026-10-30T00:00:00", "x")).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(TaskImportService.date("30/10/2026 14:05", "x")).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    private TaskImportService.Result sales(String csv, Options options, boolean apply) throws Exception {
        return imports.importCsv(Kind.SALES, in(csv), "SaleTasks.csv", options, apply);
    }

    private static ByteArrayInputStream in(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, tenantId);
    }

    private static BigDecimal figure(CutoverCheckService.Check c, String key) {
        return c.figures().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow().value();
    }
}
