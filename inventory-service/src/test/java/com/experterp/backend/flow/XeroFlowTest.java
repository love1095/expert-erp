package com.experterp.backend.flow;

import com.experterp.backend.dto.BillRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.ReorderRequest;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.integration.xero.FakeXeroClient;
import com.experterp.backend.integration.xero.XeroApi;
import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.integration.xero.XeroConnectionService;
import com.experterp.backend.integration.xero.XeroPaymentPuller;
import com.experterp.backend.integration.xero.XeroSyncService;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.service.AccountingExportService;
import com.experterp.backend.service.LedgerPeriodService;
import com.experterp.backend.service.PurchaseOrderService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review flow test F7, Xero (fake Xero, docs/review-and-test-plan.md, Part 3): the first weeks on CSV files, imported
 * into Xero by hand, then the switch to the API link. Nothing may reach Xero twice, in either direction.
 *
 * On CSV: 100 gloves bought from Portwest at £2.00 (bill £240.00), 10 sold to Abbey at £5.00 (£60.00, paid here).
 * One export: the sales invoice, the bill, the cost-of-goods journal and Abbey's payment; the files are imported into Xero.
 * Then the API link: nothing of the export is sent again and Abbey's payment, now seen in Xero, is not recorded again.
 * After the switch: 5 gloves to Baxter at £6.00 (£36.00) sent once and paid in Xero (pulled back once);
 * the bill paid here goes to Xero against the bill imported from the file.
 */
class XeroFlowTest extends FlowSupport {

    @Autowired AccountingExportService export;
    @Autowired XeroSyncService sync;
    @Autowired XeroConnectionService connection;
    @Autowired XeroPaymentPuller puller;
    @Autowired FakeXeroClient fake;
    @Autowired com.experterp.backend.integration.xero.XeroClient xero;
    @Autowired PurchaseOrderService purchaseOrders;
    @Autowired SupplierRepository supplierRepository;
    @Autowired LedgerPeriodService periods;

    @Test
    void csvFirstThenTheApiLinkNothingIsSentOrRecordedTwice() throws Exception {
        export.updateSettings(new AccountingExportService.Settings(today.minusDays(30), "200", "631", "310", "310", "425", "802", "615"));
        assertThat(export.xeroLink()).isEqualTo("CSV");
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        portwest = supplierRepository.save(portwest);
        Product gloves = product("GLOVES");
        gloves.setDefaultSupplier(portwest);
        gloves.getDetails().setSupplierPrice(new BigDecimal("2.00"));
        gloves = productRepository.save(gloves);

        // ---- the CSV weeks: a bill, a sale paid here ----
        InvoiceResponse bill = buy(gloves, 100, "2.00");
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("240.00");
        Long abbeyOrder = saleOrderService.confirm(saleOrderService.create(order("Abbey", line(gloves, "10", "5.00"))).getId()).getId();
        saleOrderService.fulfill(abbeyOrder, null);
        InvoiceResponse abbey = invoiceAll(abbeyOrder);
        assertThat(abbey.getTotalAmount()).isEqualByComparingTo("60.00");
        pay(abbey.getId(), "60.00");
        // Nothing goes to Xero by itself while the link is CSV
        assertThat(sync.process().sent()).isZero();

        AccountingExportService.Pending pending = export.pending();
        assertThat(pending.counts()).containsEntry("INVOICE", 1).containsEntry("BILL", 1).containsEntry("PAYMENT", 1);
        AccountingExportService.Export file = export.export();
        Map<String, List<CSVRecord>> files = unzip(file.zip());
        assertThat(files).containsKeys("SalesInvoices.csv", "Bills.csv", "ManualJournals.csv", "Payments.csv");
        assertThat(export.batches().get(0).invoices()).isEqualTo(1);
        assertThat(export.batches().get(0).bills()).isEqualTo(1);
        assertThat(export.batches().get(0).payments()).isEqualTo(1);
        assertThat(export.pending().items()).isEmpty();
        assertThatThrownBy(() -> export.export()).hasMessageContaining("Nothing new");

        // ---- the switch: connected to Xero, the owner imports the files there first ----
        String url = connection.connect();
        connection.callback(param(url, "code"), param(url, "state"));
        Session s = connection.session(false);
        String org = s.xeroTenantId();
        Map<String, XeroApi.Invoice> imported = importIntoXero(s, files);
        assertThat(imported).containsKeys(abbey.getInvoiceNumber(), bill.getInvoiceNumber());
        int callsBefore = fake.org(tenantId).calls.size();
        export.setXeroLink("API");

        // Nothing of the export is sent again, and Abbey's payment, which Xero now shows, is not recorded twice
        XeroSyncService.RunResult first = sync.process();
        assertThat(first.sent()).isZero();
        assertThat(first.failed()).isZero();
        assertThat(fake.org(tenantId).invoices).hasSize(2);
        assertThat(fake.org(tenantId).payments).hasSize(1);
        assertThat(fake.org(tenantId).journals).isEmpty();
        assertThat(fake.org(tenantId).calls.subList(callsBefore, fake.org(tenantId).calls.size())).doesNotContain("Invoices", "Payments", "ManualJournals");
        XeroPaymentPuller.PullResult pulled = puller.pull();
        assertThat(pulled.recorded()).isZero();
        assertThat(pulled.errors()).isZero();
        assertThat(invoiceService.findById(abbey.getId()).getAmountPaid()).isEqualByComparingTo("60.00");
        assertThat(invoiceService.findById(abbey.getId()).getPayments()).hasSize(1);
        assertThat(salesLedger.agedDebtors(today).totals().credit()).isEqualByComparingTo("0");
        assertThat(puller.pulled(10)).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo("LINKED");
            assertThat(p.number()).isEqualTo(abbey.getInvoiceNumber());
        });

        // ---- after the switch: Baxter's sale goes once; the bill paid here goes against the bill from the file ----
        Long baxterOrder = saleOrderService.confirm(saleOrderService.create(order("Baxter", line(gloves, "5", "6.00"))).getId()).getId();
        saleOrderService.fulfill(baxterOrder, null);
        InvoiceResponse baxter = invoiceAll(baxterOrder);
        pay(bill.getId(), "240.00");
        XeroSyncService.RunResult after = sync.process();
        assertThat(after.failed()).isZero();
        String xeroBaxter = sync.xeroId("DOC:" + baxter.getId());
        assertThat(fake.org(tenantId).invoices.get(xeroBaxter).total()).isEqualByComparingTo("36.00");
        assertThat(fake.org(tenantId).invoices).hasSize(3);
        assertThat(fake.org(tenantId).journals).hasSize(1);                             // Baxter's cost of goods
        assertThat(fake.org(tenantId).payments.values()).filteredOn(p -> p.invoice() != null
                && imported.get(bill.getInvoiceNumber()).invoiceID().equals(p.invoice().invoiceID())).singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("240.00"));
        int calls = fake.org(tenantId).calls.size();
        assertThat(sync.process().sent()).isZero();
        assertThat(fake.org(tenantId).calls).hasSize(calls);

        // Baxter pays in Xero while the books here are hard locked: refused, kept as an error, the run goes on;
        // once unlocked it is recorded here once, and not sent back
        LedgerPeriodService.Periods open = periods.periods();
        periods.update(new LedgerPeriodService.Periods(open.yearEndDay(), open.yearEndMonth(), null, today));
        fake.payInXero(org, xeroBaxter, new BigDecimal("36.00"), "650", today.toString());
        XeroPaymentPuller.PullResult refused = puller.pull();
        assertThat(refused.errors()).isEqualTo(1);
        assertThat(refused.recorded()).isZero();
        assertThat(puller.pulled(10)).anySatisfy(p -> {
            assertThat(p.status()).isEqualTo("ERROR");
            assertThat(p.message()).contains("books are locked");
        });
        periods.update(open);
        assertThat(puller.pull().recorded()).isEqualTo(1);
        assertThat(puller.pull().recorded()).isZero();
        assertThat(invoiceService.findById(baxter.getId()).getAmountPaid()).isEqualByComparingTo("36.00");
        int payments = fake.org(tenantId).payments.size();
        assertThat(sync.process().sent()).isZero();
        assertThat(fake.org(tenantId).payments).hasSize(payments);

        // One way at a time: no CSV files on the API link, and nothing waits for either
        assertThatThrownBy(() -> export.export()).hasMessageContaining("API link");
        assertThat(export.pending().items()).isEmpty();
        assertThat(export.changedAfterExport()).isEmpty();

        // Xero and the ERP agree: what is owed on each side
        BigDecimal xeroOwed = fake.org(tenantId).invoices.values().stream().filter(i -> "ACCREC".equals(i.type()))
                .map(i -> i.total().subtract(paidInXero(org, i.invoiceID()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(xeroOwed).isEqualByComparingTo(salesLedger.agedDebtors(today).totals().total()).isEqualByComparingTo("0");
        assertThat(auditActions()).contains("ACCOUNTING:EXPORT", "ACCOUNTING:XERO_LINK");
    }

    // ---- helpers ----

    private InvoiceResponse buy(Product p, int qty, String price) {
        ReorderRequest reorder = new ReorderRequest();
        reorder.setWarehouseId(main.getId());
        ReorderRequest.Line rl = new ReorderRequest.Line();
        rl.setProductId(p.getId());
        rl.setQuantity(BigDecimal.valueOf(qty));
        reorder.setLines(List.of(rl));
        Long poId = purchaseOrders.createFromReorder(reorder).get(0).getId();
        PurchaseOrderResponse po = purchaseOrders.markOrdered(poId);
        purchaseOrders.receive(poId);
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber("PW-" + id);
        BillRequest.Line bl = new BillRequest.Line();
        bl.setPurchaseOrderLineId(po.getLines().get(0).getId());
        bl.setQuantity(BigDecimal.valueOf(qty));
        bl.setUnitPrice(new BigDecimal(price));
        b.setLines(List.of(bl));
        return invoiceService.billPurchaseOrder(poId, b);
    }

    private static Map<String, List<CSVRecord>> unzip(byte[] zip) throws Exception {
        Map<String, List<CSVRecord>> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                byte[] bytes = z.readAllBytes();
                if (!e.getName().endsWith(".csv")) continue;
                try (CSVParser p = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                        .parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
                    out.put(e.getName(), p.getRecords());
                }
            }
        }
        return out;
    }

    /** What Xero's own import does with the files: the invoices and bills by their numbers, then the payments. */
    private Map<String, XeroApi.Invoice> importIntoXero(Session s, Map<String, List<CSVRecord>> files) {
        Map<String, XeroApi.Invoice> imported = new LinkedHashMap<>();
        for (String name : List.of("SalesInvoices.csv", "Bills.csv")) {
            Map<String, List<CSVRecord>> byNumber = new LinkedHashMap<>();
            files.get(name).forEach(r -> byNumber.computeIfAbsent(r.get("*InvoiceNumber"), k -> new java.util.ArrayList<>()).add(r));
            byNumber.forEach((number, rows) -> {
                XeroApi.Contact c = xero.saveContact(s, new XeroApi.Contact(null, rows.get(0).get("*ContactName"), null, null, null, null, null));
                List<XeroApi.LineItem> items = rows.stream().map(r -> new XeroApi.LineItem(r.isMapped("*Description") ? r.get("*Description") : r.get("Description"), new BigDecimal(r.get("*Quantity")),
                        new BigDecimal(r.get("*UnitAmount")), r.get("*AccountCode"), "OUTPUT2", new BigDecimal(r.get("TaxAmount")), null, null, null)).toList();
                imported.put(number, xero.saveInvoice(s, new XeroApi.Invoice(null, name.startsWith("Sales") ? "ACCREC" : "ACCPAY", XeroApi.Contact.ref(c.contactID()),
                        today.toString(), today.toString(), number, null, "Exclusive", items, "AUTHORISED", "GBP", null, null, null, null, null)));
            });
        }
        for (CSVRecord r : files.get("Payments.csv")) {
            String number = r.get(2);
            fake.payInXero(s.xeroTenantId(), imported.get(number).invoiceID(), new BigDecimal(r.get(4)), "650", today.toString());
        }
        return imported;
    }

    private BigDecimal paidInXero(String org, String invoiceId) {
        return fake.org(tenantId).payments.values().stream().filter(p -> p.invoice() != null && invoiceId.equals(p.invoice().invoiceID())
                && !"DELETED".equals(p.status())).map(XeroApi.Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String param(String url, String name) {
        for (String p : url.substring(url.indexOf('?') + 1).split("&")) if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }
}
