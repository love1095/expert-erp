package com.experterp.backend.integration.xero;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.integration.xero.XeroApi.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase L3: everything the CSV export sends, sent through the Xero API (fake), sharing one record with the CSV export. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class XeroSendTest {

    @Autowired XeroSyncService sync;
    @Autowired XeroConnectionService connection;
    @Autowired XeroClient xero;
    @Autowired FakeXeroClient fake;
    @Autowired AccountingExportService export;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PaymentAccountRepository paymentAccountRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;
    String id;
    Warehouse warehouse;
    Product helmet;
    PaymentAccount bank;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Send " + id);
        t.setSlug("send-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELMET-" + id);
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        bank = new PaymentAccount();
        bank.setCode("650");
        bank.setName("Current Account - RBS");
        bank.setDefaultAccount(true);
        bank = paymentAccountRepository.save(bank);
        // H5's mapping (Expert Safety's codes) and the export start date
        export.updateSettings(new AccountingExportService.Settings(LocalDate.now().minusDays(30), "200", "631", "310", "310", "425", "802", "615"));
        String url = connection.connect();
        connection.callback(param(url, "code"), param(url, "state"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /** A stock count, a bill paid, a sale shipped, invoiced, part paid and credited (credit applied): all of it to Xero. */
    @Test
    void everyDocumentTypeGoesThroughTheApiOnceWithTheExportsMapping() {
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "COUNT", null, null, new BigDecimal("6.00"));
        InvoiceResponse bill = bill("B-" + id);
        invoiceService.addPayment(bill.getId(), pay(bill.getTotalAmount()));
        InvoiceResponse invoice = sale("Abbey " + id);
        invoiceService.addPayment(invoice.getId(), pay(new BigDecimal("10.00")));
        CreditNoteRequest cr = new CreditNoteRequest();
        CreditNoteRequest.Line line = new CreditNoteRequest.Line();
        line.setInvoiceItemId(invoice.getItems().get(0).getId());
        line.setQuantity(BigDecimal.ONE);
        cr.getLines().add(line);
        InvoiceResponse creditNote = creditNoteService.create(invoice.getId(), cr);          // applied to the invoice

        // On the CSV link the queue sends nothing; switched to the API, everything goes
        assertThat(sync.process().sent()).isZero();
        export.setXeroLink("API");
        fake.failNext(connection.session(false).xeroTenantId(), FakeXeroClient.Failure.RATE_LIMIT);   // the first call waits Xero's 429
        sync.process();
        jdbc.update("UPDATE xero_sync_items SET next_attempt_at = now() WHERE tenant_id = ? AND status = 'PENDING'", tenantId);
        XeroSyncService.RunResult r = sync.process();
        assertThat(sync.counts().get("FAILED")).as(String.valueOf(sync.items("FAILED", 20))).isZero();
        assertThat(sync.counts().get("PENDING")).isZero();

        FakeXeroClient.Org org = fake.org(tenantId);
        assertThat(org.contacts.values()).extracting(Contact::name).containsExactlyInAnyOrder("Abbey " + id, "Portwest " + id);
        XeroApi.Invoice xInvoice = org.invoices.values().stream().filter(i -> "ACCREC".equals(i.type())).findFirst().orElseThrow();
        assertThat(xInvoice.invoiceNumber()).isEqualTo(invoice.getInvoiceNumber());
        assertThat(xInvoice.total()).isEqualByComparingTo(invoice.getTotalAmount());              // 2 × £10 + VAT = £24
        assertThat(xInvoice.lineItems()).singleElement().satisfies(l -> {
            assertThat(l.accountCode()).isEqualTo("200");
            assertThat(l.taxType()).isEqualTo("OUTPUT2");
            assertThat(l.description()).startsWith("HELMET-" + id);
        });
        XeroApi.Invoice xBill = org.invoices.values().stream().filter(i -> "ACCPAY".equals(i.type())).findFirst().orElseThrow();
        assertThat(xBill.invoiceNumber()).isEqualTo("B-" + id);
        assertThat(xBill.lineItems().get(0).accountCode()).isEqualTo("631");                      // stock on the inventory account
        assertThat(xBill.lineItems().get(0).taxType()).isEqualTo("INPUT2");
        assertThat(xBill.status()).isEqualTo("PAID");
        assertThat(org.creditNotes.values()).singleElement().satisfies(c -> {
            assertThat(c.creditNoteNumber()).isEqualTo(creditNote.getInvoiceNumber());
            assertThat(c.remainingCredit()).isEqualByComparingTo("0");                           // applied to the invoice
        });
        assertThat(org.allocations).hasSize(1);
        assertThat(org.payments.values()).hasSize(2).allSatisfy(p -> assertThat(p.account().code()).isEqualTo("650"));
        assertThat(org.invoices.get(xInvoice.invoiceID()).amountDue()).isEqualByComparingTo("2.00");  // 24 − 10 paid − 12 credited
        assertThat(org.journals.values()).extracting(ManualJournal::narration).anyMatch(n -> n.startsWith("COGS")).anyMatch(n -> n.startsWith("Stock adjustment in"));

        // One record with the CSV export: nothing left for a CSV file; a second run sends nothing
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounting_exported WHERE tenant_id = ? AND via = 'API'", Integer.class, tenantId)).isGreaterThanOrEqualTo(6);
        assertThatThrownBy(() -> export.export()).hasMessageContaining("API link");
        export.setXeroLink("CSV");
        assertThatThrownBy(() -> export.export()).hasMessageContaining("Nothing new");
        export.setXeroLink("API");
        int calls = org.calls.size();
        assertThat(sync.process().sent()).isZero();
        assertThat(org.calls.stream().skip(calls).filter(c -> !c.contains("?where") && !c.equals("TaxRates"))).isEmpty();
    }

    /** Sent in a CSV file first (imported into Xero by hand), then the API: nothing twice, and a later payment finds its invoice. */
    @Test
    void switchingFromCsvToTheApiSendsNothingTwice() {
        InvoiceResponse invoice = sale("Bolton " + id);
        AccountingExportService.Export file = export.export();                                    // CSV
        assertThat(file.zip()).isNotEmpty();
        // …the accountant imports the file into Xero
        Session s = connection.session(false);
        Contact c = xero.saveContact(s, new Contact(null, "Bolton " + id, null, true, null, null, null));
        xero.saveInvoice(s, new XeroApi.Invoice(null, "ACCREC", Contact.ref(c.contactID()), LocalDate.now().toString(), LocalDate.now().plusDays(30).toString(),
                invoice.getInvoiceNumber(), null, "Exclusive", List.of(new LineItem("Helmet", new BigDecimal("2"), BigDecimal.TEN, "200", "OUTPUT2",
                new BigDecimal("4.00"), null, null, null)), "AUTHORISED", "GBP", null, null, null, null, null));

        export.setXeroLink("API");
        invoiceService.addPayment(invoice.getId(), pay(new BigDecimal("24.00")));
        sync.process();
        FakeXeroClient.Org org = fake.org(tenantId);
        assertThat(org.invoices).hasSize(1);                                                        // not sent again
        assertThat(org.payments.values()).singleElement().satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("24.00"));
        assertThat(org.invoices.values().iterator().next().status()).isEqualTo("PAID");             // found by its number

        // Another organisation: nothing of this one's
        Tenant other = new Tenant();
        other.setName("Other " + id);
        other.setSlug("other-" + id);
        TenantContext.set(tenantRepository.save(other).getId());
        assertThat(sync.items(null, 100)).isEmpty();
        assertThat(export.xeroLink()).isEqualTo("CSV");
    }

    // ---- helpers ----

    private InvoiceResponse sale(String customer) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName(customer);
        so.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(2));
        l.setUnitPrice(BigDecimal.TEN);
        so.setLines(List.of(l));
        SaleOrderResponse order = saleOrderService.confirm(saleOrderService.create(so).getId());
        if (jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN type IN ('ADJUSTMENT_IN','PURCHASE_IN') THEN quantity ELSE 0 END),0) FROM stock_movements WHERE tenant_id = ?",
                BigDecimal.class, tenantId).signum() > 0) {
            saleOrderService.fulfill(order.getId(), null);
        }
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        return invoiceService.invoiceOrder(order.getId(), all);
    }

    private InvoiceResponse bill(String number) {
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        portwest = supplierRepository.save(portwest);
        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierId(portwest.getId());
        po.setWarehouseId(warehouse.getId());
        po.setOrdered(true);
        PurchaseOrderRequest.Line pl = new PurchaseOrderRequest.Line();
        pl.setProductId(helmet.getId());
        pl.setQuantity(BigDecimal.TEN);
        pl.setUnitCost(new BigDecimal("5.00"));
        po.setLines(List.of(pl));
        PurchaseOrderResponse purchase = purchaseOrderService.create(po);
        purchaseOrderService.receive(purchase.getId());
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber(number);
        return invoiceService.billPurchaseOrder(purchase.getId(), b);
    }

    private PaymentRequest pay(BigDecimal amount) {
        PaymentRequest p = new PaymentRequest();
        p.setAmount(amount);
        p.setAccountId(bank.getId());
        return p;
    }

    private static String param(String url, String name) {
        for (String p : url.substring(url.indexOf('?') + 1).split("&")) if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }
}
