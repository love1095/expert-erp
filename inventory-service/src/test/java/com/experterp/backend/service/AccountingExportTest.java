package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase H5 (spec §6): Xero CSV export of invoices, credit notes, bills, COGS / stock journals and payments, each once. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountingExportTest {

    @Autowired AccountingExportService export;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired SalesLedgerService salesLedger;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;
    String id;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        id = UUID.randomUUID().toString();
        t.setName("Xero " + id);
        t.setSlug("xero-" + id);
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

    @Test
    void nothingIsExportedUntilTheStartDateAndAccountsAreSet() {
        AccountingExportService.Pending p = export.pending();
        assertThat(p.missing()).contains("Export start date", "Inventory control account");
        assertThat(p.items()).isEmpty();
        assertThatThrownBy(() -> export.export()).hasMessageContaining("Set up the export first");
    }

    /**
     * Like Expert Safety's Cin7 mapping: 5 helmets counted in at £6 (stock adjustment), 2 bought at £6 from Portwest and
     * billed with £3 carriage, 4 sold at £10 + £5 carriage, shipped (COGS £24), invoiced, £20 paid; 1 credited back to stock.
     */
    @Test
    void xeroFilesHoldEachDocumentJournalAndPaymentOnce() throws IOException {
        export.updateSettings(new AccountingExportService.Settings(today, "200", "631", "310", "495", "407", "802", "615"));
        movementService.createMovement(helmet, warehouse, BigDecimal.valueOf(5), MovementType.ADJUSTMENT_IN,
                ReferenceType.ADJUSTMENT, "ADJ-1", null, null, new BigDecimal("6.00"));
        InvoiceResponse bill = billed();
        InvoiceResponse invoice = soldAndInvoiced();
        PaymentRequest pay = new PaymentRequest();
        pay.setAmount(new BigDecimal("20"));
        pay.setMethod(Payment.PaymentMethod.BANK);
        pay.setReference("BACS 991");
        invoiceService.addPayment(invoice.getId(), pay);
        CreditNoteRequest cn = new CreditNoteRequest();
        CreditNoteRequest.Line back = new CreditNoteRequest.Line();
        back.setInvoiceItemId(invoice.getItems().stream().filter(i -> i.getProductId() != null).findFirst().orElseThrow().getId());
        back.setQuantity(BigDecimal.ONE);
        back.setRestock(true);
        cn.setLines(List.of(back));
        creditNoteService.create(invoice.getId(), cn);

        AccountingExportService.Pending pending = export.pending();
        assertThat(pending.missing()).isEmpty();
        assertThat(pending.counts()).containsEntry("INVOICE", 1).containsEntry("CREDIT_NOTE", 1).containsEntry("BILL", 1)
                .containsEntry("JOURNAL", 3).containsEntry("PAYMENT", 1);   // the credit note settles by allocation: not money

        AccountingExportService.Export e = export.export();
        Map<String, String> files = unzip(e.zip());
        assertThat(files).containsKeys("SalesInvoices.csv", "Bills.csv", "ManualJournals.csv", "Payments.csv", "README.txt");

        // Sales: 4 helmets + carriage on revenue 200; the credit note's line negative
        List<CSVRecord> sales = read(files.get("SalesInvoices.csv"));
        List<CSVRecord> inv = sales.stream().filter(r -> r.get("*InvoiceNumber").equals(invoice.getInvoiceNumber())).toList();
        assertThat(inv).hasSize(2);
        assertThat(inv.get(0).get("*Quantity")).isEqualTo("4");
        assertThat(inv.get(0).get("*UnitAmount")).isEqualTo("10");
        assertThat(inv.get(0).get("*AccountCode")).isEqualTo("200");
        assertThat(inv.get(0).get("*InvoiceDate")).isEqualTo(today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        assertThat(inv.get(0).get("Reference")).isNotBlank();                                  // the order number, like Cin7
        CSVRecord credit = sales.stream().filter(r -> !r.get("*InvoiceNumber").equals(invoice.getInvoiceNumber())).findFirst().orElseThrow();
        assertThat(credit.get("*UnitAmount")).isEqualTo("-10");
        assertThat(credit.get("TaxAmount")).isEqualTo("-2");

        // Bill: the helmets on inventory 631, the carriage on purchase charges 407
        List<CSVRecord> bills = read(files.get("Bills.csv"));
        assertThat(bills).extracting(r -> r.get("*AccountCode")).containsExactlyInAnyOrder("631", "407");
        assertThat(bills).allSatisfy(r -> assertThat(r.get("*InvoiceNumber")).isEqualTo(bill.getInvoiceNumber()));

        // Journals: stock counted in (Dr 631 / Cr 495 £30), shipment (Dr 310 / Cr 631 £24), return (Dr 631 / Cr 310 £6); each balances
        List<CSVRecord> journals = read(files.get("ManualJournals.csv"));
        assertThat(journals).hasSize(6);
        assertThat(journals.stream().map(r -> new BigDecimal(r.get("*Amount"))).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
        assertThat(journals).anySatisfy(r -> {
            assertThat(r.get("*Narration")).startsWith("COGS: ").endsWith(" shipped");
            assertThat(r.get("*AccountCode")).isEqualTo("310");
            assertThat(r.get("*Amount")).isEqualTo("24");
        });
        assertThat(journals).anySatisfy(r -> {
            assertThat(r.get("*AccountCode")).isEqualTo("495");
            assertThat(r.get("*Amount")).isEqualTo("-30");
        });

        // Payments to match in Xero; allocations (credit note against the invoice) are not money and are left out
        List<CSVRecord> payments = read(files.get("Payments.csv"));
        assertThat(payments).anySatisfy(r -> {
            assertThat(r.get("Document")).isEqualTo(invoice.getInvoiceNumber());
            assertThat(r.get("Amount")).isEqualTo("20.00");
            assertThat(r.get("Reference")).isEqualTo("BACS 991");
        });

        // Each once: nothing left; undo puts it back
        assertThat(export.pending().items()).isEmpty();
        assertThatThrownBy(() -> export.export()).hasMessageContaining("Nothing new to export");
        assertThat(export.file(e.batchId()).zip()).isEqualTo(e.zip());
        export.undo(e.batchId());
        assertThat(export.pending().items()).isNotEmpty();
        assertThat(export.batches()).singleElement().satisfies(b -> assertThat(b.undoneAt()).isNotNull());
        AccountingExportService.Export again = export.export();
        assertThat(unzip(again.zip()).get("SalesInvoices.csv")).isEqualTo(files.get("SalesInvoices.csv"));

        // A payment voided after it was exported is flagged to put right in Xero
        Long paymentId = invoiceService.findById(invoice.getId()).getPayments().stream()
                .filter(p -> p.getMethod() == Payment.PaymentMethod.BANK).findFirst().orElseThrow().getId();
        salesLedger.voidPayment(invoice.getId(), paymentId, "Bounced");
        assertThat(export.changedAfterExport()).singleElement().satisfies(c -> assertThat(c.what()).contains("voided"));
    }

    private InvoiceResponse billed() {
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        portwest = supplierRepository.save(portwest);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(portwest.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(new BigDecimal("2"));
        l.setUnitCost(new BigDecimal("6.00"));
        req.setLines(List.of(l));
        PurchaseOrderResponse po = purchaseOrderService.create(req);
        purchaseOrderService.receive(po.getId());
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber("PW-" + id.substring(0, 6));
        BillRequest.Charge c = new BillRequest.Charge();
        c.setDescription("Carriage");
        c.setUnitPrice(new BigDecimal("3"));
        b.setCharges(List.of(c));
        return invoiceService.billPurchaseOrder(po.getId(), b);
    }

    private InvoiceResponse soldAndInvoiced() {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("Abbey Attachments " + id);
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
        SaleOrderResponse order = saleOrderService.confirm(saleOrderService.create(req).getId());
        saleOrderService.fulfill(order.getId(), null);
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        return invoiceService.invoiceOrder(order.getId(), all);
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) files.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        return files;
    }

    private static List<CSVRecord> read(String csv) throws IOException {
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(new StringReader(csv)).getRecords();
    }
}
