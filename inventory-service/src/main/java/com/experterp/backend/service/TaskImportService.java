package com.experterp.backend.service;

import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Phase J: Cin7 Core's sale / purchase task CSV import (Sale / Purchase → Import CSV file), with Cin7's template and
 * rules (help article "Import sales tasks via CSV"), so a file made for Cin7 imports here as it is. Used at cutover for
 * the orders still open and the invoices / bills still unpaid, and afterwards for sales from other systems.
 * <p>
 * Rows are grouped in tasks by CustomerName (SupplierName) + InvoiceNumber; RecordType says what each row is:
 * {@code Invoice} (one per task), {@code InvoiceLines}, {@code InvoiceAdditionalCharges}, {@code Payment},
 * {@code CreditLines}, {@code CreditAdditionalCharges}, {@code Refund}; purchases also {@code Received} and
 * {@code Unstock}. Options as on Cin7's screen: invoice status (Draft / Authorised), pick-pack-ship mode, set order
 * backordered on low stock, export imported tasks to Xero; purchases: generate stock received from invoice lines.
 * <p>
 * As Cin7: the order is always authorised; a customer (supplier) not found by name is created, with the file's terms,
 * tax rule and currency, and its addresses and contact are added or updated; payments need an authorised invoice;
 * credit notes and refunds need an authorised invoice that was shipped (Pick + Pack + Ship); a refund needs a credit
 * note; line totals must equal price × quantity less discount; invoice, payment and credit note dates are in the past;
 * only new tasks (a number already used is refused).
 * <p>
 * Added for cutover (not in Cin7, noted): invoice status "No invoice yet" (open orders keep Cin7's SO / PO number) and
 * "already shipped / received in the old system" (lines done without moving stock: the opening stock already counts
 * them). Not like Cin7 (noted in TODO): pick and pick + pack are not separate steps here (those modes leave the order
 * to ship); no 100-task limit per file; the whole file is imported or nothing.
 */
@Service
@RequiredArgsConstructor
public class TaskImportService {

    private static final int MAX_DOCS_LISTED = 200;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SaleOrderService saleOrderService;
    private final PurchaseOrderService purchaseOrderService;
    private final InvoiceService invoiceService;
    private final CreditNoteService creditNoteService;
    private final PurchaseLedgerService purchaseLedgerService;
    private final AllocationService allocationService;
    private final SaleOrderRepository saleOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final PaymentAccountRepository paymentAccountRepository;
    private final TaxService taxService;
    private final AuditService auditService;
    private final LedgerPeriodService periodService;
    private final PickPackService pickPack;
    private final AccountingExportService accountingExportService;
    private final JdbcTemplate jdbc;
    private final PaymentTermService paymentTermService;

    public enum Kind { SALES, PURCHASES }

    /** Cin7's Invoice status (Draft / Authorised), plus NONE: no invoice yet, for orders still open at cutover. */
    public enum InvoiceStatus { NONE, DRAFT, AUTHORISED }

    /** Cin7's pick, pack and ship processing mode, plus ALREADY_SHIPPED (shipped in the old system, no stock moved). */
    public enum Fulfilment { NO_PICKING, AUTO_PICK, AUTO_PICK_PACK, AUTO_PICK_PACK_SHIP, ALREADY_SHIPPED }

    /**
     * @param backorder        sales: Cin7's "Set order backordered on low stock level"
     * @param generateReceived purchases: Cin7's "Generate stock received from invoice lines"
     * @param alreadyReceived  purchases: received in the old system, no stock moved (added for cutover)
     * @param exportToXero     Cin7's "Export imported tasks to Xero/QuickBooks"; off = recorded as already in Xero
     */
    public record Options(InvoiceStatus status, Fulfilment fulfilment, boolean backorder, boolean generateReceived,
                          boolean alreadyReceived, boolean exportToXero, Long warehouseId) {}

    public record Doc(String number, String party, String orderNumber, String invoiceNumber, String creditNoteNumber,
                      int lines, BigDecimal total, BigDecimal paid, LocalDate date) {}

    public record Result(boolean applied, Kind kind, int documents, BigDecimal total, BigDecimal paid, List<String> newParties,
                         List<Doc> docs, List<String> errors, List<String> warnings) {}

    public record Log(Long id, String kind, String fileName, String options, int documents, BigDecimal total,
                      LocalDateTime createdAt, String createdBy) {}

    // ---- reading ----

    private enum Type { INVOICE, LINE, CHARGE, PAYMENT, CREDIT_LINE, CREDIT_CHARGE, REFUND, RECEIVED, UNSTOCK }

    private record Row(int line, Type type, CSVRecord r) {}

    private static final class Task {
        final String party, number;
        Row header;
        final List<Row> rows = new ArrayList<>();
        Task(String party, String number) { this.party = party; this.number = number; }
        List<Row> of(Type t) { return rows.stream().filter(r -> r.type() == t).toList(); }
    }

    /** What happened to one task, for the result and the Xero option. */
    private record Done(Doc doc, List<Long> invoiceIds) {}

    @Transactional
    public Result importCsv(Kind kind, InputStream in, String fileName, Options o, boolean apply) throws IOException {
        Options options = new Options(o.status() == null ? InvoiceStatus.AUTHORISED : o.status(),
                o.fulfilment() == null ? Fulfilment.NO_PICKING : o.fulfilment(), o.backorder(), o.generateReceived(),
                o.alreadyReceived(), o.exportToXero(), o.warehouseId());
        if (options.generateReceived() && options.alreadyReceived()) {
            throw new IllegalArgumentException("Choose either 'Generate stock received' or 'Already received in the old system'");
        }
        Warehouse defaultWarehouse = options.warehouseId() != null
                ? warehouseRepository.findById(options.warehouseId()).orElseThrow(() -> new IllegalArgumentException("Warehouse not found"))
                : warehouseRepository.findFirstByOrderByIdAsc().orElseThrow(() -> new IllegalStateException("Add a warehouse first"));

        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        Set<String> newParties = new LinkedHashSet<>();
        CsvColumns[] cols = new CsvColumns[1];
        Map<String, Task> tasks = read(in, kind, cols, errors);
        CsvColumns c = cols[0];

        List<Doc> docs = new ArrayList<>();
        List<Long> invoiceIds = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        for (Task task : tasks.values()) {
            if (errors.size() >= 100) break;
            try {
                Done d = kind == Kind.SALES ? sale(task, c, options, defaultWarehouse, fileName, warnings, newParties)
                        : purchase(task, c, options, defaultWarehouse, fileName, warnings, newParties);
                docs.add(d.doc());
                invoiceIds.addAll(d.invoiceIds());
                total = total.add(d.doc().total());
                paid = paid.add(d.doc().paid());
            } catch (RuntimeException e) {
                errors.add(task.number + " (" + task.party + "): " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        if (tasks.isEmpty() && errors.isEmpty()) errors.add("The file has no tasks");
        newParties.forEach(p -> warnings.add("New " + (kind == Kind.SALES ? "customer" : "supplier") + " created: " + p
                + " (not found by name; check the spelling if it should be an existing one)"));
        if (options.fulfilment() == Fulfilment.ALREADY_SHIPPED && kind == Kind.SALES || options.alreadyReceived() && kind == Kind.PURCHASES) {
            warnings.add("Lines marked as already " + (kind == Kind.SALES ? "shipped" : "received") + " in the old system: no stock moved and no cost of goods recorded here");
        }
        if (!invoiceIds.isEmpty()) {
            if (options.exportToXero()) {
                LocalDate start = accountingExportService.settings().exportStartDate();
                warnings.add("'Export imported tasks to Xero' is ticked: the imported invoices and payments"
                        + (start == null ? "" : " dated from " + start) + " go in the next Xero export");
            } else {
                warnings.add("'Export imported tasks to Xero' is not ticked: the imported invoices and payments are recorded as already in Xero");
            }
        }

        boolean ok = errors.isEmpty() && !docs.isEmpty();
        Result result = new Result(apply && ok, kind, docs.size(), money(total), money(paid), List.copyOf(newParties),
                docs.subList(0, Math.min(MAX_DOCS_LISTED, docs.size())), errors, warnings);
        if (!apply || !ok) {
            // Preview, or errors: nothing is kept
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return result;
        }
        if (!options.exportToXero()) accountingExportService.markNotToExport(invoiceIds, "imported from " + source(fileName));
        String opts = describe(kind, options, defaultWarehouse);
        jdbc.update("INSERT INTO data_import_logs (tenant_id, kind, file_name, options, documents, total, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                TenantContext.get(), kind.name(), cut(fileName, 255), cut(opts, 255), docs.size(), result.total(), auditService.currentActorName());
        auditService.log("DATA_IMPORT", null, kind.name(), Map.of("file", fileName == null ? "" : fileName, "options", opts,
                "documents", docs.size(), "total", result.total(), "newParties", newParties.size()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Log> logs() {
        return jdbc.query("SELECT * FROM data_import_logs WHERE tenant_id = ? ORDER BY created_at DESC, id DESC LIMIT 100",
                (rs, i) -> new Log(rs.getLong("id"), rs.getString("kind"), rs.getString("file_name"), rs.getString("options"),
                        rs.getInt("documents"), rs.getBigDecimal("total"), rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getString("created_by")), TenantContext.get());
    }

    private static String describe(Kind kind, Options o, Warehouse w) {
        List<String> parts = new ArrayList<>(List.of("Invoice " + o.status()));
        if (kind == Kind.SALES) {
            parts.add(o.fulfilment().name());
            if (o.backorder()) parts.add("backorder on low stock");
        } else {
            if (o.generateReceived()) parts.add("generate received");
            if (o.alreadyReceived()) parts.add("already received");
        }
        parts.add(o.exportToXero() ? "to Xero" : "not to Xero");
        parts.add(w.getName());
        return String.join(" / ", parts);
    }

    private static Map<String, Task> read(InputStream in, Kind kind, CsvColumns[] cols, List<String> errors) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setAllowMissingColumnNames(true).build();
        Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '﻿') reader.reset();
        Map<String, Task> tasks = new LinkedHashMap<>();
        String partyColumn = kind == Kind.SALES ? "CustomerName" : "SupplierName";
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            // Cin7's headings mark required columns with * and ^ — ignored when matching
            CsvColumns c = new CsvColumns(parser.getHeaderNames().stream().map(h -> h.trim().replaceAll("[*^]", "")).toList());
            cols[0] = c;
            if (!c.has("RecordType") || !c.has("InvoiceNumber") || !c.has(partyColumn)) {
                throw new IllegalArgumentException("This is not a Cin7 " + (kind == Kind.SALES ? "sale" : "purchase")
                        + " task file: it needs the columns RecordType, " + partyColumn + " and InvoiceNumber (download the CSV template)");
            }
            for (CSVRecord r : parser) {
                int line = (int) r.getRecordNumber() + 1;
                String recordType = c.get(r, "RecordType"), party = c.get(r, partyColumn), number = c.get(r, "InvoiceNumber");
                if (recordType == null && party == null && number == null) continue;
                if (party == null || number == null) { errors.add("Row " + line + ": " + partyColumn + " and InvoiceNumber are required"); continue; }
                Type type = type(recordType, kind);
                if (type == null) {
                    errors.add("Row " + line + ": RecordType '" + recordType + "' is not one of Invoice, InvoiceLines, InvoiceAdditionalCharges, "
                            + "Payment, CreditLines, CreditAdditionalCharges, Refund" + (kind == Kind.PURCHASES ? ", Received, Unstock" : ""));
                    continue;
                }
                // Cin7 groups the lines of a task by party + InvoiceNumber
                Task t = tasks.computeIfAbsent(party.toLowerCase(Locale.ROOT) + "\u0000" + number, k -> new Task(party, number));
                Row row = new Row(line, type, r);
                if (type == Type.INVOICE) {
                    if (t.header != null) errors.add("Row " + line + ": " + number + " has a second Invoice row (one per task)");
                    else t.header = row;
                } else {
                    t.rows.add(row);
                }
            }
        }
        for (Task t : tasks.values()) {
            if (t.header == null) errors.add(t.number + " (" + t.party + "): no Invoice row");
            else if (t.of(Type.LINE).isEmpty()) errors.add(t.number + " (" + t.party + "): needs at least one InvoiceLines row with a product");
        }
        tasks.values().removeIf(t -> t.header == null || t.of(Type.LINE).isEmpty());
        return tasks;
    }

    private static Type type(String recordType, Kind kind) {
        if (recordType == null) return null;
        String t = recordType.toLowerCase(Locale.ROOT).replace(" ", "");
        Type type = switch (t) {
            case "invoice" -> Type.INVOICE;
            case "invoicelines", "invoiceline" -> Type.LINE;
            case "invoiceadditionalcharges", "invoiceadditionalcharge" -> Type.CHARGE;
            case "payment", "payments" -> Type.PAYMENT;
            case "creditlines", "creditline" -> Type.CREDIT_LINE;
            case "creditadditionalcharges", "creditadditionalcharge", "creditnoteadditionalcharges" -> Type.CREDIT_CHARGE;
            case "refund", "refunds" -> Type.REFUND;
            case "received" -> Type.RECEIVED;
            case "unstock" -> Type.UNSTOCK;
            default -> null;
        };
        if (kind == Kind.SALES && (type == Type.RECEIVED || type == Type.UNSTOCK)) return null;
        return type;
    }

    // ---- sales ----

    private Done sale(Task t, CsvColumns c, Options o, Warehouse defaultWarehouse, String fileName, List<String> warnings, Set<String> newParties) {
        CSVRecord h = t.header.r();
        LocalDate date = pastDate(invoiceDate(c, h), "InvoiceDate");
        if (date == null) date = LocalDate.now();
        periodService.check(date, "InvoiceDate");
        boolean taxInclusive = bool(c.get(h, "TaxInclusive"));
        String note = "Imported from " + source(fileName);
        List<Row> credits = t.of(Type.CREDIT_LINE), creditCharges = t.of(Type.CREDIT_CHARGE), refunds = t.of(Type.REFUND), payments = t.of(Type.PAYMENT);
        boolean shipped = o.fulfilment() == Fulfilment.AUTO_PICK_PACK_SHIP || o.fulfilment() == Fulfilment.ALREADY_SHIPPED;
        if (!payments.isEmpty() && o.status() != InvoiceStatus.AUTHORISED) {
            throw new IllegalArgumentException("Payment lines need Invoice status 'Authorised'");
        }
        if (!(credits.isEmpty() && creditCharges.isEmpty() && refunds.isEmpty()) && (o.status() != InvoiceStatus.AUTHORISED || !shipped)) {
            throw new IllegalArgumentException("credit notes and refunds need Invoice status 'Authorised' and 'Auto Pick + Pack + Ship' (or already shipped)");
        }
        if (!refunds.isEmpty() && credits.isEmpty() && creditCharges.isEmpty()) {
            throw new IllegalArgumentException("Refund lines need credit note lines");
        }
        String orderNumber = orderNumber(c, h, t, o.status());
        if (orderNumber != null && saleOrderRepository.existsByOrderNumber(orderNumber)) {
            throw new IllegalArgumentException("sale order " + orderNumber + " already exists (only new tasks are imported)");
        }
        if (o.status() != InvoiceStatus.NONE && invoiceRepository.existsByInvoiceNumber(t.number)) {
            throw new IllegalArgumentException("invoice " + t.number + " already exists (only new tasks are imported)");
        }

        Customer customer = customer(t, c, h, newParties);
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customer.getId());
        req.setWarehouseId(warehouse(c.get(h, "StockLocation"), defaultWarehouse).getId());
        req.setCustomerReference(cut(c.get(h, "Reference/Comment/Note"), 100));
        req.setSalesRep(cut(c.get(h, "SalesRepresentative"), 100));
        req.setPriceTier(cut(c.get(h, "PriceTier"), 100));
        req.setPaymentTerm(cut(c.get(h, "Terms"), 100));
        req.setShipBy(date(c.get(h, "ShipmentRequiredByDate"), "ShipmentRequiredByDate"));
        req.setNotes(c.get(h, "MemoOnInvoice"));
        req.setCarrier(cut(c.get(h, "CarrierCode"), 100));
        req.setShippingNotes(c.get(h, "ShippingNotes"));
        req.setShipToCompany(cut(c.get(h, "ShipToCompany"), 255));
        req.setShipToContact(cut(c.get(h, "ShipToContact"), 255));
        if (c.get(h, "ShippingAddressLine1") != null) req.setShippingAddress(address(c, h, "Shipping"));
        if (c.get(h, "BillingAddressLine1") != null) req.setBillingAddress(address(c, h, "Billing"));

        List<SaleOrderRequest.Line> lines = new ArrayList<>();
        for (Row row : t.of(Type.LINE)) {
            SaleOrderRequest.Line l = new SaleOrderRequest.Line();
            l.setProductId(product(c, row).getId());
            l.setQuantity(positive(c, row, "Quantity"));
            String rule = c.get(row.r(), "TaxRule");
            BigDecimal price = price(c, row, false);
            l.setDiscountPercent(discount(c, row));
            checkTotal(c, row, price, l.getQuantity(), l.getDiscountPercent(), taxInclusive);
            l.setUnitPrice(net(price, rule, taxInclusive, TaxRate.Usage.SALE, row));
            if (rule != null) { l.setTaxRule(rule); l.setTaxReason(note); }
            lines.add(l);
        }
        List<SaleOrderRequest.Charge> charges = new ArrayList<>();
        for (Row row : t.of(Type.CHARGE)) {
            SaleOrderRequest.Charge ch = new SaleOrderRequest.Charge();
            ch.setDescription(cut(Objects.requireNonNullElse(c.get(row.r(), "Product"), "Additional charge"), 255));
            ch.setQuantity(c.get(row.r(), "Quantity") == null ? BigDecimal.ONE : positive(c, row, "Quantity"));
            String rule = c.get(row.r(), "TaxRule");
            BigDecimal price = price(c, row, true);
            ch.setDiscountPercent(discount(c, row));
            checkTotal(c, row, price, ch.getQuantity(), ch.getDiscountPercent(), taxInclusive);
            ch.setUnitPrice(net(price, rule, taxInclusive, TaxRate.Usage.SALE, row));
            if (rule != null) { ch.setTaxRule(rule); ch.setTaxReason(note); }
            charges.add(ch);
        }
        req.setLines(lines);
        req.setCharges(charges);

        // As Cin7: the order is always authorised
        SaleOrderResponse created = saleOrderService.create(req);
        SaleOrder so = saleOrderRepository.findById(created.getId()).orElseThrow();
        if (orderNumber != null) so.setOrderNumber(orderNumber);
        so.setOrderedAt(date.atStartOfDay());
        so.setNotes(join(so.getNotes(), note));
        saleOrderRepository.save(so);
        saleOrderService.confirm(so.getId(), note);

        // The invoice before shipping, so "invoice on shipment" finds it invoiced already
        List<Long> invoiceIds = new ArrayList<>();
        Invoice invoice = null;
        if (o.status() != InvoiceStatus.NONE) {
            OrderInvoiceRequest all = new OrderInvoiceRequest();
            all.setIssue(o.status() == InvoiceStatus.AUTHORISED);
            InvoiceResponse inv = invoiceService.invoiceOrder(so.getId(), all);
            invoice = invoiceRepository.findById(inv.getId()).orElseThrow();
            invoice.setInvoiceNumber(t.number);
            invoice.setIssueDate(date);
            LocalDate due = date(c.get(h, "InvoiceDueDate"), "InvoiceDueDate");
            invoice.setDueDate(due != null ? due : paymentTermService.dueDate(so.getPaymentTerm(), date));
            invoiceRepository.save(invoice);
            invoiceIds.add(invoice.getId());
        }

        boolean shippedNow = false;
        switch (o.fulfilment()) {
            case NO_PICKING -> { }
            // Phase N3: Cin7's Auto Pick (picked, ready to pack) and Auto Pick + Pack (packed in one carton, ready to ship)
            case AUTO_PICK, AUTO_PICK_PACK -> {
                try {
                    PickPackService.PickList pl = pickPack.create(List.of(so.getId()));
                    pickPack.confirm(pl.id(), null);
                    if (o.fulfilment() == Fulfilment.AUTO_PICK_PACK) pickPack.pack(so.getId(), null);
                } catch (IllegalStateException e) {
                    if (!o.backorder()) throw new IllegalArgumentException("nothing in stock to pick (tick 'Set order backordered on low stock level' to import it backordered)");
                    warnings.add(t.number + ": not enough stock to pick, imported backordered");
                }
            }
            case AUTO_PICK_PACK_SHIP -> {
                boolean shortOfStock = allocationService.forOrders(List.of(so.getId())).values().stream()
                        .anyMatch(a -> a.backordered().signum() > 0);
                if (shortOfStock && !o.backorder()) {
                    throw new IllegalArgumentException("not enough stock to ship (tick 'Set order backordered on low stock level' to import it backordered)");
                }
                if (shortOfStock) {
                    warnings.add(t.number + ": not enough stock, imported backordered (not shipped)"
                            + (credits.isEmpty() && creditCharges.isEmpty() ? "" : "; its credit note and refund were skipped"));
                } else {
                    saleOrderService.fulfill(so.getId(), null);
                    shippedNow = true;
                }
            }
            case ALREADY_SHIPPED -> {
                SaleOrder locked = saleOrderRepository.findByIdForUpdate(so.getId()).orElseThrow();
                locked.getLines().forEach(l -> l.setFulfilledQuantity(l.getQuantity().subtract(l.getCancelledQuantity())));
                locked.refreshFulfilmentStatus();
                locked.setFulfilledAt(date.atStartOfDay());
                if (!locked.hasRemaining()) locked.setStatus(SaleOrder.Status.COMPLETED);
                saleOrderRepository.save(locked);
                shippedNow = true;
            }
        }

        BigDecimal paid = invoice == null ? BigDecimal.ZERO : payments(payments, c, invoice.getId(), "DatePaid");
        String creditNoteNumber = null;
        if (invoice != null && shippedNow && !(credits.isEmpty() && creditCharges.isEmpty())) {
            Long cnId = saleCreditNote(t, c, invoice, credits, creditCharges, refunds, note);
            invoiceIds.add(cnId);
            creditNoteNumber = invoiceRepository.findById(cnId).orElseThrow().getInvoiceNumber();
        }
        BigDecimal total = saleOrderService.findById(so.getId()).getTotal();
        return new Done(new Doc(t.number, customer.getName(), so.getOrderNumber(), invoice == null ? null : invoice.getInvoiceNumber(),
                creditNoteNumber, t.rows.size(), total, paid, date), invoiceIds);
    }

    /** Cin7: the customer is found by name, else created from the Invoice row; addresses added or updated, contact added. */
    private Customer customer(Task t, CsvColumns c, CSVRecord h, Set<String> newParties) {
        Customer customer = customerRepository.findFirstByNameIgnoreCaseOrderByIdAsc(t.party).orElse(null);
        boolean created = customer == null;
        if (customer != null && customer.getStatus() == Customer.Status.DEPRECATED) {
            throw new IllegalArgumentException("customer " + customer.getName() + " is deprecated");
        }
        if (created) {
            customer = new Customer();
            customer.setName(cut(t.party, 255));
            customer.setPaymentTerm(cut(c.get(h, "Terms"), 100));
            String rule = c.get(h, "TaxRule");
            if (rule != null) customer.setTaxRule(taxService.rate(TaxRate.Usage.SALE, rule).getName());
            customer.setCurrency(cut(c.get(h, "CustomerCurrency"), 3));
            customer.setSaleAccount(cut(c.get(h, "Account"), 50));
            customer.setSalesRepresentative(cut(c.get(h, "SalesRepresentative"), 100));
            newParties.add(customer.getName());
        }
        if (c.get(h, "ShippingAddressLine1") != null) upsertAddress(customer, address(c, h, "Shipping"), CustomerAddress.Type.SHIPPING, created);
        if (c.get(h, "BillingAddressLine1") != null) upsertAddress(customer, address(c, h, "Billing"), CustomerAddress.Type.BILLING, created);
        String contact = c.get(h, "CustomerContact");
        if (contact != null && customer.getContacts().stream().noneMatch(x -> contact.equalsIgnoreCase(x.getName()))) {
            CustomerContact cc = new CustomerContact();
            cc.setCustomer(customer);
            cc.setName(cut(contact, 255));
            cc.setPhone(cut(c.get(h, "CustomerPhone"), 100));
            cc.setEmail(cut(c.get(h, "CustomerEmail"), 255));
            cc.setDefaultContact(customer.getContacts().isEmpty());
            customer.getContacts().add(cc);
        }
        customer = customerRepository.save(customer);
        if (created) auditService.log("CUSTOMER", customer.getId(), "CREATE", Map.of("name", customer.getName(), "via", "sale task import"));
        return customer;
    }

    // Cin7: the address whose line 1 is the same is updated, else a new one is added (default for its type on a new customer)
    private static void upsertAddress(Customer customer, Address a, CustomerAddress.Type type, boolean created) {
        CustomerAddress existing = customer.getAddresses().stream()
                .filter(x -> x.getAddress() != null && a.getLine1().equalsIgnoreCase(Objects.requireNonNullElse(x.getAddress().getLine1(), "")))
                .findFirst().orElse(null);
        if (existing != null) {
            existing.setAddress(a);
            return;
        }
        CustomerAddress ca = new CustomerAddress();
        ca.setCustomer(customer);
        ca.setAddress(a);
        ca.setType(type);
        ca.setDefaultForType(created && customer.getAddresses().stream().noneMatch(x -> x.getType() == type));
        customer.getAddresses().add(ca);
    }

    /** Cin7's CreditLines / CreditAdditionalCharges / Refund rows: one credit note per task, refunded when Refund rows say so. */
    private Long saleCreditNote(Task t, CsvColumns c, Invoice invoice, List<Row> credits, List<Row> charges, List<Row> refunds, String note) {
        List<Row> all = new ArrayList<>(credits);
        all.addAll(charges);
        String number = same(c, all, "CreditNoteNumber");
        LocalDate date = pastDate(same(c, all, "CreditNoteDate"), "CreditNoteDate");
        if (invoiceRepository.existsByInvoiceNumber(number)) throw new IllegalArgumentException("credit note " + number + " already exists");

        CreditNoteRequest req = new CreditNoteRequest();
        Map<Long, BigDecimal> perItem = new LinkedHashMap<>();
        for (Row row : credits) {
            Product p = product(c, row);
            BigDecimal qty = positive(c, row, "Quantity");
            BigDecimal price = price(c, row, false);
            InvoiceItem item = invoice.getItems().stream().filter(i -> i.getProduct() != null && i.getProduct().getId().equals(p.getId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("row " + row.line() + ": " + p.getSku() + " is not on the invoice lines"));
            if (price.compareTo(item.getUnitPrice()) != 0 && price.compareTo(item.getUnitPrice().multiply(BigDecimal.ONE.add(item.getTaxRate().divide(HUNDRED)))
                    .setScale(2, RoundingMode.HALF_UP)) != 0) {
                throw new IllegalArgumentException("row " + row.line() + ": credit price " + price.toPlainString() + " is not the invoice price "
                        + item.getUnitPrice().stripTrailingZeros().toPlainString() + " (a price credit is a credit note additional charge)");
            }
            perItem.merge(item.getId(), qty, BigDecimal::add);
        }
        perItem.forEach((id, qty) -> {
            CreditNoteRequest.Line l = new CreditNoteRequest.Line();
            l.setInvoiceItemId(id);
            l.setQuantity(qty);
            req.getLines().add(l);
        });
        for (Row row : charges) {
            BigDecimal price = price(c, row, true);
            if (price.signum() < 0) throw new IllegalArgumentException("row " + row.line() + ": negative credit note charges are not taken yet");
            CreditNoteRequest.Charge ch = new CreditNoteRequest.Charge();
            ch.setDescription(cut(Objects.requireNonNullElse(c.get(row.r(), "Product"), "Credit"), 255));
            ch.setQuantity(c.get(row.r(), "Quantity") == null ? BigDecimal.ONE : positive(c, row, "Quantity"));
            ch.setUnitPrice(price);
            ch.setTaxRule(c.get(row.r(), "TaxRule"));
            req.getCharges().add(ch);
        }
        req.setReason(note);
        if (!refunds.isEmpty()) {
            req.setSettle(CreditNoteRequest.Settle.REFUND);
            req.setRefundReference(cut(c.get(refunds.get(0).r(), "Reference/Comment/Note"), 100));
        }
        Long cnId = creditNoteService.create(invoice.getId(), req).getId();
        Invoice cn = invoiceRepository.findById(cnId).orElseThrow();
        cn.setInvoiceNumber(cut(number, 50));
        cn.setIssueDate(date);
        cn.setDueDate(date);
        invoiceRepository.save(cn);
        if (!refunds.isEmpty()) refundDetails(c, refunds, cn, "DatePaid");
        return cnId;
    }

    // ---- purchases ----

    private Done purchase(Task t, CsvColumns c, Options o, Warehouse defaultWarehouse, String fileName, List<String> warnings, Set<String> newParties) {
        CSVRecord h = t.header.r();
        LocalDate date = pastDate(invoiceDate(c, h), "InvoiceDate");
        if (date == null) date = LocalDate.now();
        periodService.check(date, "InvoiceDate");
        boolean taxInclusive = bool(c.get(h, "TaxInclusive"));
        String note = "Imported from " + source(fileName);
        List<Row> credits = t.of(Type.CREDIT_LINE), creditCharges = t.of(Type.CREDIT_CHARGE), refunds = t.of(Type.REFUND),
                payments = t.of(Type.PAYMENT), received = t.of(Type.RECEIVED), unstock = t.of(Type.UNSTOCK);
        if (!payments.isEmpty() && o.status() != InvoiceStatus.AUTHORISED) throw new IllegalArgumentException("Payment lines need Invoice status 'Authorised'");
        if (!(credits.isEmpty() && creditCharges.isEmpty() && refunds.isEmpty() && unstock.isEmpty()) && o.status() != InvoiceStatus.AUTHORISED) {
            throw new IllegalArgumentException("credit notes, refunds and unstock need Invoice status 'Authorised'");
        }
        if (!refunds.isEmpty() && credits.isEmpty() && creditCharges.isEmpty()) throw new IllegalArgumentException("Refund lines need credit note lines");
        if (!received.isEmpty() && (o.generateReceived() || o.alreadyReceived())) {
            throw new IllegalArgumentException("Received lines are not used with 'Generate stock received' or 'Already received'");
        }
        String orderNumber = orderNumber(c, h, t, o.status());
        if (orderNumber != null && purchaseOrderRepository.existsByOrderNumber(orderNumber)) {
            throw new IllegalArgumentException("purchase order " + orderNumber + " already exists (only new tasks are imported)");
        }

        Supplier supplier = supplier(t, c, h, newParties);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(supplier.getId());
        Warehouse warehouse = warehouse(c.get(h, "StockLocation"), defaultWarehouse);
        req.setWarehouseId(warehouse.getId());
        req.setSupplierReference(cut(c.get(h, "Reference/Serial/Note"), 100));
        req.setPaymentTerm(cut(c.get(h, "Terms"), 100));
        req.setRequiredBy(date(c.get(h, "ShipmentRequiredByDate"), "ShipmentRequiredByDate"));
        req.setNotes(join(c.get(h, "MemoOnInvoice"), note));
        req.setOrdered(true);
        List<PurchaseOrderRequest.Line> lines = new ArrayList<>();
        for (Row row : t.of(Type.LINE)) {
            PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
            l.setProductId(product(c, row).getId());
            l.setQuantity(positive(c, row, "Quantity"));
            String rule = c.get(row.r(), "TaxRule");
            BigDecimal price = price(c, row, false);
            l.setDiscountPercent(discount(c, row));
            checkTotal(c, row, price, l.getQuantity(), l.getDiscountPercent(), taxInclusive);
            l.setUnitCost(net(price, rule, taxInclusive, TaxRate.Usage.PURCHASE, row));
            if (rule != null) { l.setTaxRule(rule); l.setTaxReason(note); }
            lines.add(l);
        }
        List<BillRequest.Charge> billCharges = new ArrayList<>();
        List<PurchaseOrderRequest.Cost> costs = new ArrayList<>();
        for (Row row : t.of(Type.CHARGE)) {
            String description = cut(Objects.requireNonNullElse(c.get(row.r(), "Product"), "Additional cost"), 255);
            BigDecimal quantity = c.get(row.r(), "Quantity") == null ? BigDecimal.ONE : positive(c, row, "Quantity");
            String rule = c.get(row.r(), "TaxRule");
            BigDecimal price = price(c, row, false);
            checkTotal(c, row, price, quantity, discount(c, row), taxInclusive);
            BigDecimal unit = net(price, rule, taxInclusive, TaxRate.Usage.PURCHASE, row);
            PurchaseOrderRequest.Cost cost = new PurchaseOrderRequest.Cost();
            cost.setDescription(description);
            cost.setQuantity(quantity);
            cost.setUnitPrice(unit);
            cost.setTaxRule(rule);
            if (rule != null) cost.setTaxReason(note);
            costs.add(cost);
            BillRequest.Charge ch = new BillRequest.Charge();
            ch.setDescription(description);
            ch.setQuantity(quantity);
            ch.setUnitPrice(unit);
            ch.setTaxRule(rule);
            billCharges.add(ch);
        }
        req.setLines(lines);
        req.setCosts(costs);

        PurchaseOrderResponse created = purchaseOrderService.create(req);
        PurchaseOrder po = purchaseOrderRepository.findById(created.getId()).orElseThrow();
        if (orderNumber != null) po.setOrderNumber(orderNumber);
        po.setOrderedAt(date.atStartOfDay());
        purchaseOrderRepository.save(po);

        // Stock received: generated from the invoice lines, from Received rows (by date), or already received elsewhere
        if (o.generateReceived()) {
            PurchaseReceiptRequest r = new PurchaseReceiptRequest();
            r.setReceivedOn(date);
            purchaseOrderService.receive(po.getId(), r, false);
        } else if (o.alreadyReceived()) {
            PurchaseOrder locked = purchaseOrderRepository.findByIdForUpdate(po.getId()).orElseThrow();
            locked.getLines().forEach(l -> l.setReceivedQuantity(l.getQuantity()));
            locked.setStatus(PurchaseOrder.Status.RECEIVED);
            locked.setReceivedAt(date.atStartOfDay());
            purchaseOrderRepository.save(locked);
        } else if (!received.isEmpty()) {
            receiveRows(c, po.getId(), received, defaultWarehouse);
        }

        List<Long> invoiceIds = new ArrayList<>();
        String billNumber = null, creditNoteNumber = null;
        BigDecimal paid = BigDecimal.ZERO;
        if (o.status() != InvoiceStatus.NONE) {
            PurchaseOrder current = purchaseOrderRepository.findById(po.getId()).orElseThrow();
            BillRequest bill = new BillRequest();
            bill.setSupplierInvoiceNumber(cut(t.number, 100));
            bill.setIssueDate(date);
            bill.setDueDate(date(c.get(h, "InvoiceDueDate"), "InvoiceDueDate"));
            bill.setDraft(o.status() == InvoiceStatus.DRAFT);
            List<BillRequest.Line> billLines = new ArrayList<>();
            for (PurchaseOrderLine l : current.getLines()) {
                BillRequest.Line bl = new BillRequest.Line();
                bl.setPurchaseOrderLineId(l.getId());
                bl.setQuantity(l.getQuantity());
                billLines.add(bl);
            }
            bill.setLines(billLines);
            bill.setCharges(billCharges);
            InvoiceResponse inv = invoiceService.billPurchaseOrder(po.getId(), bill, false);
            billNumber = inv.getInvoiceNumber();
            invoiceIds.add(inv.getId());
            paid = payments(payments, c, inv.getId(), "DatePaid/DateReceived");
            if (!(credits.isEmpty() && creditCharges.isEmpty())) {
                Long cnId = supplierCreditNote(c, inv.getId(), credits, creditCharges, refunds, unstock, o.alreadyReceived(), warnings, t, note);
                invoiceIds.add(cnId);
                creditNoteNumber = invoiceRepository.findById(cnId).orElseThrow().getInvoiceNumber();
            } else if (!unstock.isEmpty()) {
                throw new IllegalArgumentException("Unstock lines need credit note lines (the goods returned are credited)");
            }
        }
        BigDecimal total = purchaseOrderService.findById(po.getId()).getTotal();
        return new Done(new Doc(t.number, supplier.getName(), po.getOrderNumber(), billNumber, creditNoteNumber, t.rows.size(),
                total, paid, date), invoiceIds);
    }

    /** Found by name, else created from the Invoice row (as customers are in Cin7's sale import). */
    private Supplier supplier(Task t, CsvColumns c, CSVRecord h, Set<String> newParties) {
        Supplier s = supplierRepository.findFirstByNameIgnoreCaseOrderByIdAsc(t.party).orElse(null);
        if (s != null) {
            if ("Deprecated".equalsIgnoreCase(s.getStatus())) throw new IllegalArgumentException("supplier " + s.getName() + " is deprecated");
            return s;
        }
        s = new Supplier();
        s.setName(cut(t.party, 255));
        s.setPaymentTerm(cut(c.get(h, "Terms"), 100));
        String rule = c.get(h, "TaxRule");
        if (rule != null) s.setTaxRule(taxService.rate(TaxRate.Usage.PURCHASE, rule).getName());
        s.setCurrency(cut(c.get(h, "SupplierCurrency"), 3));
        s.setContactName(cut(c.get(h, "SupplierContact"), 255));
        s.setPhone(cut(c.get(h, "SupplierPhone"), 50));
        s = supplierRepository.save(s);
        newParties.add(s.getName());
        auditService.log("SUPPLIER", s.getId(), "CREATE", Map.of("name", s.getName(), "via", "purchase task import"));
        return s;
    }

    /** Cin7's Received rows: product, quantity, date; one receipt per date. */
    private void receiveRows(CsvColumns c, Long poId, List<Row> rows, Warehouse defaultWarehouse) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId).orElseThrow();
        Map<LocalDate, Map<Long, BigDecimal>> byDate = new TreeMap<>();
        Map<LocalDate, Long> warehouseByDate = new HashMap<>();
        for (Row row : rows) {
            Product p = product(c, row);
            PurchaseOrderLine line = po.getLines().stream().filter(l -> l.getProduct().getId().equals(p.getId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("row " + row.line() + ": " + p.getSku() + " is not on the invoice lines"));
            LocalDate day = pastDate(c.get(row.r(), "DatePaid/DateReceived"), "DateReceived");
            if (day == null) throw new IllegalArgumentException("row " + row.line() + ": DateReceived is required");
            byDate.computeIfAbsent(day, d -> new LinkedHashMap<>()).merge(line.getId(), positive(c, row, "Quantity"), BigDecimal::add);
            String location = c.get(row.r(), "StockLocation");
            if (location != null) warehouseByDate.put(day, warehouse(location.contains(":") ? location.substring(0, location.indexOf(':')).trim() : location, defaultWarehouse).getId());
        }
        byDate.forEach((day, lines) -> {
            PurchaseReceiptRequest r = new PurchaseReceiptRequest();
            r.setReceivedOn(day);
            r.setWarehouseId(warehouseByDate.get(day));
            lines.forEach((lineId, qty) -> {
                PurchaseReceiptRequest.Line l = new PurchaseReceiptRequest.Line();
                l.setPurchaseOrderLineId(lineId);
                l.setQuantity(qty);
                r.getLines().add(l);
            });
            purchaseOrderService.receive(poId, r, false);
        });
    }

    /** Supplier credit note from CreditLines / CreditAdditionalCharges; Unstock rows send the goods back; Refund rows refund it. */
    private Long supplierCreditNote(CsvColumns c, Long billId, List<Row> credits, List<Row> charges, List<Row> refunds, List<Row> unstock,
                                    boolean alreadyReceived, List<String> warnings, Task t, String note) {
        Invoice bill = invoiceRepository.findById(billId).orElseThrow();
        List<Row> all = new ArrayList<>(credits);
        all.addAll(charges);
        String number = same(c, all, "CreditNoteNumber");
        LocalDate date = pastDate(same(c, all, "CreditNoteDate"), "CreditNoteDate");
        Map<Long, BigDecimal> back = new HashMap<>();
        for (Row row : unstock) {
            Product p = product(c, row);
            back.merge(p.getId(), positive(c, row, "Quantity"), BigDecimal::add);
        }
        if (!back.isEmpty() && alreadyReceived) {
            warnings.add(t.number + ": goods returned in the old system: no stock moved");
            back.clear();
        }
        Map<Long, BigDecimal> perItem = new LinkedHashMap<>();
        Map<Long, Long> productOfItem = new HashMap<>();
        for (Row row : credits) {
            Product p = product(c, row);
            InvoiceItem item = bill.getItems().stream().filter(i -> i.getProduct() != null && i.getProduct().getId().equals(p.getId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("row " + row.line() + ": " + p.getSku() + " is not on the bill lines"));
            perItem.merge(item.getId(), positive(c, row, "Quantity"), BigDecimal::add);
            productOfItem.put(item.getId(), p.getId());
        }
        List<PurchaseLedgerService.CreditLine> lines = new ArrayList<>();
        perItem.forEach((id, qty) -> {
            BigDecimal returned = back.remove(productOfItem.get(id));
            if (returned != null && returned.compareTo(qty) != 0) {
                throw new IllegalArgumentException("Unstock quantity " + returned.toPlainString() + " differs from the credited " + qty.toPlainString());
            }
            lines.add(new PurchaseLedgerService.CreditLine(id, qty, returned != null));
        });
        if (!back.isEmpty()) throw new IllegalArgumentException("Unstock lines for products not credited");
        List<PurchaseLedgerService.CreditCharge> creditCharges = new ArrayList<>();
        for (Row row : charges) {
            BigDecimal price = price(c, row, true);
            if (price.signum() < 0) throw new IllegalArgumentException("row " + row.line() + ": negative credit note charges are not taken yet");
            creditCharges.add(new PurchaseLedgerService.CreditCharge(cut(Objects.requireNonNullElse(c.get(row.r(), "Product"), "Credit"), 255),
                    c.get(row.r(), "Quantity") == null ? BigDecimal.ONE : positive(c, row, "Quantity"), price, c.get(row.r(), "TaxRule")));
        }
        Long refundAccount = refunds.isEmpty() ? null : account(c, refunds.get(0));
        InvoiceResponse cn = purchaseLedgerService.createCreditNote(billId, new PurchaseLedgerService.CreditNoteRequest(cut(number, 50), date,
                lines, creditCharges, note, refunds.isEmpty() ? null : PurchaseLedgerService.Settle.REFUND, refundAccount,
                refunds.isEmpty() ? null : cut(c.get(refunds.get(0).r(), "Reference/Serial/Note"), 100)));
        if (!refunds.isEmpty()) refundDetails(c, refunds, invoiceRepository.findById(cn.getId()).orElseThrow(), "DatePaid/DateReceived");
        return cn.getId();
    }

    // ---- shared ----

    /** Payment rows: Price/Amount (Cin7; Total also read), the date, Account = payment account code or name. Only on authorised invoices. */
    private BigDecimal payments(List<Row> rows, CsvColumns c, Long invoiceId, String dateColumn) {
        BigDecimal paid = BigDecimal.ZERO;
        for (Row row : rows) {
            BigDecimal amount = amount(c, row);
            PaymentRequest p = new PaymentRequest();
            p.setAmount(amount);
            p.setPaidOn(pastDate(c.get(row.r(), dateColumn) != null ? c.get(row.r(), dateColumn) : c.get(row.r(), "DatePaid"), "DatePaid"));
            p.setAccountId(account(c, row));
            p.setReference(cut(c.get(row.r(), "Reference/Comment/Note") != null ? c.get(row.r(), "Reference/Comment/Note")
                    : c.get(row.r(), "Reference/Serial/Note"), 50));
            invoiceService.addPayment(invoiceId, p);
            paid = paid.add(amount);
        }
        return paid;
    }

    /** The refund made by the credit note: its amount must be the credit note's, dated and put on the account as the Refund rows say. */
    private void refundDetails(CsvColumns c, List<Row> refunds, Invoice cn, String dateColumn) {
        BigDecimal refunded = refunds.stream().map(r -> amount(c, r)).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (refunded.compareTo(cn.getTotalAmount()) != 0) {
            throw new IllegalArgumentException("refunds of " + refunded.toPlainString() + " differ from the credit note total "
                    + cn.getTotalAmount().setScale(2, RoundingMode.HALF_UP).toPlainString() + " (part refunds are entered by hand)");
        }
        Row first = refunds.get(0);
        LocalDate day = pastDate(c.get(first.r(), dateColumn) != null ? c.get(first.r(), dateColumn) : c.get(first.r(), "DatePaid"), "DatePaid");
        Long account = account(c, first);
        for (Payment p : cn.getPayments()) {
            if (p.getMethod() == Payment.PaymentMethod.CREDIT_NOTE) continue;
            if (day != null) p.setPaidAt(day.atTime(12, 0));
            if (account != null) p.setPaymentAccountId(account);
            paymentRepository.save(p);
        }
    }

    private Long account(CsvColumns c, Row row) {
        String account = c.get(row.r(), "Account");
        if (account == null) return null;
        return paymentAccountRepository.findAllByOrderByCodeAsc().stream()
                .filter(x -> x.getCode().equalsIgnoreCase(account) || x.getName().equalsIgnoreCase(account) || x.label().equalsIgnoreCase(account))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("row " + row.line() + ": payment account '" + account + "' not found"))
                .getId();
    }

    private static BigDecimal amount(CsvColumns c, Row row) {
        String text = c.get(row.r(), "Price/Amount") != null ? c.get(row.r(), "Price/Amount") : c.get(row.r(), "Total");
        BigDecimal amount = number(text);
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("row " + row.line() + ": amount '" + text + "' should be a non-zero positive");
        return amount;
    }

    /** The order's number: the OrderNumber column; for orders not invoiced, InvoiceNumber (Cin7's SO / PO number); else the next in the series. */
    private static String orderNumber(CsvColumns c, CSVRecord h, Task t, InvoiceStatus status) {
        String own = c.get(h, "OrderNumber");
        if (own != null) return cut(own, 50);
        return status == InvoiceStatus.NONE ? cut(t.number, 50) : null;
    }

    private static String invoiceDate(CsvColumns c, CSVRecord h) {
        return c.get(h, "InvoiceDate/ExpireDate") != null ? c.get(h, "InvoiceDate/ExpireDate") : c.get(h, "InvoiceDate");
    }

    /** A value that must be on at least one row and the same on every row that has it (CreditNoteNumber, CreditNoteDate). */
    private static String same(CsvColumns c, List<Row> rows, String column) {
        Set<String> values = new LinkedHashSet<>();
        rows.forEach(r -> { String v = c.get(r.r(), column); if (v != null) values.add(v); });
        if (values.isEmpty()) throw new IllegalArgumentException(column + " is required on the credit note lines");
        if (values.size() > 1) throw new IllegalArgumentException(column + " differs between the credit note lines: " + String.join(", ", values));
        return values.iterator().next();
    }

    private Warehouse warehouse(String name, Warehouse fallback) {
        if (name == null) return fallback;
        String n = name.contains(":") ? name.substring(0, name.indexOf(':')).trim() : name;
        return warehouseRepository.findAll().stream().filter(w -> w.getName().equalsIgnoreCase(n)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("StockLocation '" + name + "' is not a warehouse here"));
    }

    private Product product(CsvColumns c, Row row) {
        String sku = c.get(row.r(), "Product");
        if (sku == null) throw new IllegalArgumentException("row " + row.line() + ": Product (SKU) is empty");
        return productRepository.findBySku(sku)
                .orElseThrow(() -> new IllegalArgumentException("row " + row.line() + ": product " + sku + " not found"));
    }

    /** Prices including VAT (TaxInclusive = true) are brought back to net with the line's tax rule, as the ERP keeps net prices. */
    private BigDecimal net(BigDecimal price, String rule, boolean taxInclusive, TaxRate.Usage usage, Row row) {
        if (!taxInclusive) return price;
        if (rule == null) throw new IllegalArgumentException("row " + row.line() + ": prices include tax, so the line needs its TaxRule");
        BigDecimal rate = taxService.rate(usage, rule).getRate();
        return price.multiply(HUNDRED).divide(HUNDRED.add(rate), 4, RoundingMode.HALF_UP);
    }

    /** Cin7: Total = R2(R7(price × (100 − discount) / 100) × quantity); checked when given (net prices only). */
    private static void checkTotal(CsvColumns c, Row row, BigDecimal price, BigDecimal qty, BigDecimal discount, boolean taxInclusive) {
        BigDecimal total = number(c.get(row.r(), "Total"));
        if (total == null || taxInclusive) return;
        BigDecimal d = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal unit = price.multiply(HUNDRED.subtract(d)).divide(HUNDRED, 7, RoundingMode.HALF_UP);
        BigDecimal expected = unit.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        if (expected.compareTo(total.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("row " + row.line() + ": Total " + total.toPlainString() + " should be " + expected.toPlainString()
                    + " (price × quantity less discount)");
        }
    }

    private static BigDecimal price(CsvColumns c, Row row, boolean mayBeNegative) {
        String text = c.get(row.r(), "Price/Amount");
        BigDecimal v = number(text);
        if (v == null || (!mayBeNegative && v.signum() < 0)) {
            throw new IllegalArgumentException("row " + row.line() + ": Price/Amount '" + text + "' is not a price");
        }
        return v;
    }

    private static BigDecimal positive(CsvColumns c, Row row, String column) {
        String text = c.get(row.r(), column);
        BigDecimal v = number(text);
        if (v == null || v.signum() <= 0) throw new IllegalArgumentException("row " + row.line() + ": " + column + " '" + text + "' is not a quantity");
        return v;
    }

    private static BigDecimal discount(CsvColumns c, Row row) {
        String text = c.get(row.r(), "Discount");
        if (text == null) return null;
        BigDecimal v = number(text.replace("%", ""));
        if (v == null || v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("row " + row.line() + ": Discount '" + text + "' should be 0 – 100");
        }
        return v.signum() == 0 ? null : v;
    }

    private static Address address(CsvColumns c, CSVRecord h, String prefix) {
        Address a = new Address();
        a.setLine1(c.get(h, prefix + "AddressLine1"));
        a.setLine2(c.get(h, prefix + "AddressLine2"));
        a.setCity(c.get(h, prefix + "City"));
        a.setState(c.get(h, prefix + "Province"));
        a.setPostcode(c.get(h, prefix + "Postcode"));
        a.setCountry(c.get(h, prefix + "Country"));
        return a;
    }

    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/yyyy"), DateTimeFormatter.ofPattern("d/M/yy"), DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));

    /** Dates as Cin7 UK writes them (day first: 30/10/2026) or ISO (2026-10-30). */
    static LocalDate date(String text, String column) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() > 10 && t.charAt(10) == 'T') t = t.substring(0, 10);
        if (t.matches("\\d{1,2}/\\d{1,2}/\\d{2,4} .*")) t = t.substring(0, t.indexOf(' '));
        for (DateTimeFormatter f : DATES) {
            try { return LocalDate.parse(t, f); } catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException(column + " '" + text + "' is not a date (use 30/10/2026 or 2026-10-30)");
    }

    /** Cin7: invoice, payment and credit note dates are in the past (today included). */
    private static LocalDate pastDate(String text, String column) {
        LocalDate d = date(text, column);
        if (d != null && d.isAfter(LocalDate.now())) throw new IllegalArgumentException(column + " " + d + " is in the future");
        return d;
    }

    private static boolean bool(String text) {
        return text != null && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes") || text.equals("1"));
    }

    private static BigDecimal number(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", "").replace("£", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String source(String fileName) {
        return fileName == null || fileName.isBlank() ? "a CSV file" : fileName;
    }

    private static String join(String a, String b) {
        return a == null || a.isBlank() ? b : a + "\n" + b;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
