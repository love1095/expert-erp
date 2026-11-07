package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Export to the accounting package (phase H5, business rules §6), first as Xero's CSV import files. The ERP is not the
 * general ledger: it hands over its documents, like Cin7 Core does through its Xero link:
 * <ul>
 *   <li>sales invoices and credit notes (credit notes as negative lines), revenue account, the line's tax rule;</li>
 *   <li>bills and supplier credit notes: stock products on the inventory account (perpetual stock, like Cin7), bill
 *       charges on the purchase charges account;</li>
 *   <li>manual journals, one per stock transaction and day (Cin7: COGS and stock adjustment journals): shipments
 *       Dr COGS / Cr inventory at FIFO cost, restocked returns the other way, stock adjustments against the inventory
 *       discrepancy account, and the part of a bill's price difference that falls on goods already sold;</li>
 *   <li>payments and refunds: Xero cannot import them from a file, so they are listed to match in Xero (the Xero link
 *       will send them).</li>
 * </ul>
 * Only what is dated on or after the organisation's export start date is exported (the package already holds what
 * Cin7 sent before), and each document only once: what is exported is recorded per batch; undoing a batch lets it be
 * exported again.
 */
@Service
@RequiredArgsConstructor
public class AccountingExportService {

    private static final DateTimeFormatter XERO_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public record Settings(LocalDate exportStartDate, String revenueAccount, String inventoryAccount, String cogsAccount,
                           String discrepancyAccount, String purchaseChargesAccount, String customerCreditAccount,
                           String supplierDepositsAccount) {}

    /** Something to export: a document, a journal or a payment. */
    public record Item(String key, String kind, LocalDate date, String number, String party, BigDecimal amount) {}

    public record Pending(LocalDate startDate, List<String> missing, List<Item> items, Map<String, Integer> counts) {}

    public record Batch(Long id, LocalDateTime createdAt, String createdBy, int invoices, int creditNotes, int bills,
                        int supplierCredits, int journals, int payments, LocalDateTime undoneAt, String undoneBy) {}

    /** Exported, then changed here: to correct in the package by hand. */
    public record Changed(String key, String number, String party, String what, LocalDate date) {}

    public record Export(Long batchId, byte[] zip, String filename) {}

    // ---- the Xero API link (L3): one way on at a time, one shared record ----

    /** CSV (files, the default) or API (the Xero link sends everything). */
    @Transactional(readOnly = true)
    public String xeroLink() {
        List<String> r = jdbc.queryForList("SELECT xero_link FROM accounting_settings WHERE tenant_id = ?", String.class, TenantContext.get());
        return r.isEmpty() ? "CSV" : r.get(0);
    }

    @Transactional
    public String setXeroLink(String link) {
        if (!"CSV".equals(link) && !"API".equals(link)) throw new IllegalArgumentException("CSV or API");
        jdbc.update("INSERT INTO accounting_settings (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING", TenantContext.get());
        jdbc.update("UPDATE accounting_settings SET xero_link = ?, updated_at = now(), updated_by = ? WHERE tenant_id = ?", link, auditService.currentActorName(), TenantContext.get());
        auditService.log("ACCOUNTING", null, "XERO_LINK", Map.of("link", link));
        return link;
    }

    /** Whether Xero has this item already (exported in a CSV file or sent by the API). */
    @Transactional(readOnly = true)
    public boolean isExported(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM accounting_exported WHERE tenant_id = ? AND doc_key = ?)",
                Boolean.class, TenantContext.get(), key));
    }

    /** Recorded as in Xero, sent by the API (no file batch): never put in a CSV file afterwards. */
    @Transactional
    public void recordSentByApi(String key, LocalDate date, BigDecimal amount) {
        jdbc.update("INSERT INTO accounting_exported (tenant_id, batch_id, doc_key, doc_date, amount, via) VALUES (?, NULL, ?, ?, ?, 'API') ON CONFLICT (tenant_id, doc_key) DO NOTHING",
                TenantContext.get(), key, Date.valueOf(date == null ? LocalDate.now() : date), amount == null ? BigDecimal.ZERO : money(amount));
    }

    /** One stock journal by its key (JNL:…:date), worked out as the CSV export does. */
    @Transactional(readOnly = true)
    public Optional<Journal> journal(String key) {
        Settings s = settings();
        LocalDate d;
        try {
            d = LocalDate.parse(key.substring(key.lastIndexOf(':') + 1));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return journals(s, d, d, Set.of()).stream().filter(j -> j.key().equals(key)).findFirst();
    }

    // ---- settings ----

    @Transactional(readOnly = true)
    public Settings settings() {
        List<Settings> s = jdbc.query("SELECT * FROM accounting_settings WHERE tenant_id = ?", (rs, n) -> new Settings(
                rs.getDate("export_start_date") == null ? null : rs.getDate("export_start_date").toLocalDate(),
                rs.getString("revenue_account"), rs.getString("inventory_account"), rs.getString("cogs_account"),
                rs.getString("discrepancy_account"), rs.getString("purchase_charges_account"),
                rs.getString("customer_credit_account"), rs.getString("supplier_deposits_account")), TenantContext.get());
        return s.isEmpty() ? new Settings(null, null, null, null, null, null, null, null) : s.get(0);
    }

    @Transactional
    public Settings updateSettings(Settings s) {
        Long t = TenantContext.get();
        for (String code : Arrays.asList(s.revenueAccount(), s.inventoryAccount(), s.cogsAccount(), s.discrepancyAccount(),
                s.purchaseChargesAccount(), s.customerCreditAccount(), s.supplierDepositsAccount())) {
            if (code != null && code.trim().length() > 20) throw new IllegalArgumentException("An account code is at most 20 characters");
        }
        jdbc.update("""
                INSERT INTO accounting_settings (tenant_id, export_start_date, revenue_account, inventory_account, cogs_account,
                    discrepancy_account, purchase_charges_account, customer_credit_account, supplier_deposits_account, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
                ON CONFLICT (tenant_id) DO UPDATE SET export_start_date = EXCLUDED.export_start_date,
                    revenue_account = EXCLUDED.revenue_account, inventory_account = EXCLUDED.inventory_account,
                    cogs_account = EXCLUDED.cogs_account, discrepancy_account = EXCLUDED.discrepancy_account,
                    purchase_charges_account = EXCLUDED.purchase_charges_account,
                    customer_credit_account = EXCLUDED.customer_credit_account,
                    supplier_deposits_account = EXCLUDED.supplier_deposits_account, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                t, s.exportStartDate() == null ? null : Date.valueOf(s.exportStartDate()), code(s.revenueAccount()),
                code(s.inventoryAccount()), code(s.cogsAccount()), code(s.discrepancyAccount()), code(s.purchaseChargesAccount()),
                code(s.customerCreditAccount()), code(s.supplierDepositsAccount()), auditService.currentActorName());
        Settings saved = settings();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exportStartDate", String.valueOf(saved.exportStartDate()));
        details.put("revenue", String.valueOf(saved.revenueAccount()));
        details.put("inventory", String.valueOf(saved.inventoryAccount()));
        details.put("cogs", String.valueOf(saved.cogsAccount()));
        details.put("discrepancy", String.valueOf(saved.discrepancyAccount()));
        details.put("purchaseCharges", String.valueOf(saved.purchaseChargesAccount()));
        auditService.log("ACCOUNTING", 0L, "SETTINGS", details);
        return saved;
    }

    private static String code(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** What the export needs before it can make the files. */
    private static List<String> missing(Settings s) {
        List<String> m = new ArrayList<>();
        if (s.exportStartDate() == null) m.add("Export start date");
        if (s.revenueAccount() == null) m.add("Default revenue account");
        if (s.inventoryAccount() == null) m.add("Inventory control account");
        if (s.cogsAccount() == null) m.add("Cost of goods sold account");
        if (s.discrepancyAccount() == null) m.add("Inventory discrepancy account");
        if (s.purchaseChargesAccount() == null) m.add("Purchase charges account");
        return m;
    }

    // ---- what is ready ----

    /** One line of an invoice / bill / credit note as it is exported (also sent by the Xero API link, L3). */
    public record DocLine(long invoiceId, String type, String kind, String number, String reference, LocalDate date, LocalDate due,
                           String party, boolean stock, String sku, String description, BigDecimal qty, BigDecimal unitPrice,
                           BigDecimal discount, BigDecimal net, String taxName, BigDecimal tax, BigDecimal total) {}

    /** A stock journal at FIFO cost (also posted by the general ledger, phase K1). */
    public record Journal(String key, LocalDate date, String narration, String debit, String credit, BigDecimal amount) {}

    private record PaymentRow(String key, LocalDate date, String side, String number, String party, BigDecimal amount,
                              String method, String account, String reference) {}

    private static final String DOC_LINES = """
                SELECT i.id, i.type, i.kind, i.invoice_number, so.order_number, i.issue_date, i.due_date,
                       COALESCE(c.name, s.name, i.party_name), CASE WHEN it.landed THEN 'STOCK' ELSE p.product_type END, p.sku, COALESCE(it.description, p.name),
                       it.quantity, it.unit_price, it.discount_percent, it.total_price, it.tax_name, it.tax_amount, i.total_amount
                FROM invoices i
                JOIN invoice_items it ON it.invoice_id = i.id
                LEFT JOIN products p ON p.id = it.product_id
                LEFT JOIN customers c ON c.id = i.customer_id
                LEFT JOIN suppliers s ON s.id = i.supplier_id
                LEFT JOIN sale_orders so ON so.id = i.sale_order_id
                WHERE i.tenant_id = ? AND i.status IN ('ISSUED', 'PAID') AND """;

    /** The lines of one issued document, as the CSV export takes them (the Xero API link, L3). */
    @Transactional(readOnly = true)
    public List<DocLine> documentLines(long invoiceId) {
        return jdbc.query(DOC_LINES + " i.id = ? ORDER BY it.id", this::docLine, TenantContext.get(), invoiceId);
    }

    private DocLine docLine(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DocLine(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getDate(6).toLocalDate(),
                rs.getDate(7) == null ? rs.getDate(6).toLocalDate() : rs.getDate(7).toLocalDate(), nn(rs.getString(8)),
                "STOCK".equals(rs.getString(9)), rs.getString(10), nn(rs.getString(11)), nz(rs.getBigDecimal(12)),
                nz(rs.getBigDecimal(13)), nz(rs.getBigDecimal(14)), nz(rs.getBigDecimal(15)), rs.getString(16),
                nz(rs.getBigDecimal(17)), nz(rs.getBigDecimal(18)));
    }

    private List<DocLine> documentLines(LocalDate start, LocalDate end, Set<String> exported) {
        return jdbc.query(DOC_LINES + " i.issue_date BETWEEN ? AND ? ORDER BY i.issue_date, i.id, it.id", (rs, n) -> new DocLine(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getDate(6).toLocalDate(),
                rs.getDate(7) == null ? rs.getDate(6).toLocalDate() : rs.getDate(7).toLocalDate(), nn(rs.getString(8)),
                "STOCK".equals(rs.getString(9)), rs.getString(10), nn(rs.getString(11)), nz(rs.getBigDecimal(12)),
                nz(rs.getBigDecimal(13)), nz(rs.getBigDecimal(14)), nz(rs.getBigDecimal(15)), rs.getString(16),
                nz(rs.getBigDecimal(17)), nz(rs.getBigDecimal(18))), TenantContext.get(), Date.valueOf(start), Date.valueOf(end))
                .stream().filter(l -> !exported.contains("DOC:" + l.invoiceId())).toList();
    }

    List<Journal> journals(Settings s, LocalDate start, LocalDate end, Set<String> exported) {
        Long t = TenantContext.get();
        Timestamp from = Timestamp.valueOf(start.atStartOfDay()), to = Timestamp.valueOf(end.plusDays(1).atStartOfDay());
        Map<String, Journal> byKey = new LinkedHashMap<>();
        // Stock transactions at FIFO cost: what left (layers taken) or came in (layer opened)
        jdbc.query("""
                SELECT m.type, m.reference_id, m.created_at::date, so.order_number,
                       SUM(CASE WHEN m.type IN ('SALE_RETURN_IN', 'ADJUSTMENT_IN')
                                THEN (SELECT COALESCE(SUM(l.quantity * l.original_unit_cost), 0) FROM cost_layers l WHERE l.movement_id = m.id)
                                ELSE (SELECT COALESCE(SUM(c.quantity * c.unit_cost), 0) FROM cost_consumptions c WHERE c.movement_id = m.id) END)
                FROM stock_movements m
                LEFT JOIN sale_orders so ON m.reference_type IN ('SALE_ORDER') AND m.reference_id = 'SO-' || so.id
                WHERE m.tenant_id = ? AND m.created_at >= ? AND m.created_at < ?
                  AND m.type IN ('SALE_OUT', 'SALE_RETURN_IN', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT')
                GROUP BY m.type, m.reference_id, m.created_at::date, so.order_number
                ORDER BY m.created_at::date, m.type, m.reference_id""", rs -> {
            String type = rs.getString(1), ref = nn(rs.getString(2));
            LocalDate d = rs.getDate(3).toLocalDate();
            String order = rs.getString(4);
            BigDecimal amount = nz(rs.getBigDecimal(5)).setScale(2, RoundingMode.HALF_UP);
            String key = "JNL:" + type + ":" + ref + ":" + d;
            if (amount.signum() == 0 || exported.contains(key)) return;
            Journal j = switch (type) {
                case "SALE_OUT" -> new Journal(key, d, "COGS: " + (order != null ? order : ref) + " shipped", s.cogsAccount(), s.inventoryAccount(), amount);
                case "SALE_RETURN_IN" -> new Journal(key, d, "COGS: " + ref + " returned to stock", s.inventoryAccount(), s.cogsAccount(), amount);
                case "ADJUSTMENT_IN" -> new Journal(key, d, "Stock adjustment in: " + ref, s.inventoryAccount(), s.discrepancyAccount(), amount);
                default -> new Journal(key, d, "Stock adjustment out: " + ref, s.discrepancyAccount(), s.inventoryAccount(), amount);
            };
            byKey.put(key, j);
        }, t, from, to);
        // A bill at another price than the receipt: the part on goods already gone moves out of inventory (the bill put it there)
        jdbc.query("""
                SELECT b.id, b.invoice_number, m.type, a.created_at::date, SUM(a.amount)
                FROM cost_adjustments a
                JOIN stock_movements m ON m.id = a.movement_id
                JOIN invoice_items it ON it.id = a.invoice_item_id
                JOIN invoices b ON b.id = it.invoice_id
                WHERE a.tenant_id = ? AND a.created_at >= ? AND a.created_at < ? AND m.type IN ('SALE_OUT', 'ADJUSTMENT_OUT')
                GROUP BY b.id, b.invoice_number, m.type, a.created_at::date""", rs -> {
            String type = rs.getString(3);
            LocalDate d = rs.getDate(4).toLocalDate();
            BigDecimal amount = nz(rs.getBigDecimal(5)).setScale(2, RoundingMode.HALF_UP);
            String key = "JNL:BILLCOST:" + rs.getLong(1) + ":" + type + ":" + d;
            if (amount.signum() == 0 || exported.contains(key)) return;
            String to2 = "SALE_OUT".equals(type) ? s.cogsAccount() : s.discrepancyAccount();
            byKey.put(key, new Journal(key, d, "Bill " + rs.getString(2) + ": price difference on goods already sold", to2, s.inventoryAccount(), amount));
        }, t, from, to);
        return new ArrayList<>(byKey.values());
    }

    private List<PaymentRow> payments(LocalDate start, LocalDate end, Set<String> exported) {
        Timestamp from = Timestamp.valueOf(start.atStartOfDay()), to = Timestamp.valueOf(end.plusDays(1).atStartOfDay());
        return jdbc.query("""
                SELECT p.id, p.paid_at::date, i.type, i.kind, i.invoice_number, COALESCE(c.name, s.name, i.party_name), p.amount,
                       p.method, pa.code, p.reference
                FROM payments p
                JOIN invoices i ON i.id = p.invoice_id
                LEFT JOIN payment_accounts pa ON pa.id = p.payment_account_id
                LEFT JOIN customers c ON c.id = i.customer_id
                LEFT JOIN suppliers s ON s.id = i.supplier_id
                WHERE p.tenant_id = ? AND p.paid_at >= ? AND p.paid_at < ?
                  AND p.method NOT IN ('CREDIT_NOTE', 'CUSTOMER_CREDIT', 'SUPPLIER_CREDIT')
                ORDER BY p.paid_at, p.id""", (rs, n) -> {
            boolean sale = "SALES".equals(rs.getString(3)), credit = "CREDIT_NOTE".equals(rs.getString(4));
            String side = sale ? (credit ? "Refund to customer" : "Received from customer") : (credit ? "Refund from supplier" : "Paid to supplier");
            return new PaymentRow("PAY:" + rs.getLong(1), rs.getDate(2).toLocalDate(), side, rs.getString(5), nn(rs.getString(6)),
                    nz(rs.getBigDecimal(7)), rs.getString(8), rs.getString(9), rs.getString(10));
        }, TenantContext.get(), from, to).stream().filter(p -> !exported.contains(p.key())).toList();
    }

    private Set<String> exportedKeys() {
        return new HashSet<>(jdbc.queryForList("SELECT doc_key FROM accounting_exported WHERE tenant_id = ?", String.class, TenantContext.get()));
    }

    @Transactional(readOnly = true)
    public Pending pending() {
        Settings s = settings();
        List<String> missing = missing(s);
        if (s.exportStartDate() == null) return new Pending(null, missing, List.of(), Map.of());
        LocalDate end = LocalDate.now();
        Set<String> exported = exportedKeys();
        List<Item> items = new ArrayList<>();
        Map<Long, DocLine> firstLine = new LinkedHashMap<>();
        documentLines(s.exportStartDate(), end, exported).forEach(l -> firstLine.putIfAbsent(l.invoiceId(), l));
        for (DocLine l : firstLine.values()) {
            items.add(new Item("DOC:" + l.invoiceId(), docKind(l), l.date(), l.number(), l.party(), money(l.total())));
        }
        if (missing.isEmpty()) {
            for (Journal j : journals(s, s.exportStartDate(), end, exported)) {
                items.add(new Item(j.key(), "JOURNAL", j.date(), j.narration(), "", j.amount()));
            }
        }
        for (PaymentRow p : payments(s.exportStartDate(), end, exported)) {
            items.add(new Item(p.key(), "PAYMENT", p.date(), p.number(), p.party(), money(p.amount())));
        }
        items.sort(Comparator.comparing(Item::date).thenComparing(Item::kind));
        Map<String, Integer> counts = new TreeMap<>();
        items.forEach(i -> counts.merge(i.kind(), 1, Integer::sum));
        return new Pending(s.exportStartDate(), missing, items, counts);
    }

    private static String docKind(DocLine l) {
        boolean credit = "CREDIT_NOTE".equals(l.kind());
        return "SALES".equals(l.type()) ? (credit ? "CREDIT_NOTE" : "INVOICE") : (credit ? "SUPPLIER_CREDIT" : "BILL");
    }

    // ---- export ----

    /** Makes Xero's import files for everything ready, records it as exported, and returns them in a zip. */
    @Transactional
    public Export export() {
        Settings s = settings();
        List<String> missing = missing(s);
        if (!missing.isEmpty()) throw new IllegalStateException("Set up the export first: " + String.join(", ", missing));
        if ("API".equals(xeroLink())) throw new IllegalStateException("This organisation sends to Xero through the API link (Finance → Xero): no CSV files");
        Long t = TenantContext.get();
        // One export at a time per organisation
        jdbc.queryForList("SELECT tenant_id FROM accounting_settings WHERE tenant_id = ? FOR UPDATE", t);
        LocalDate end = LocalDate.now();
        Set<String> exported = exportedKeys();
        List<DocLine> lines = documentLines(s.exportStartDate(), end, exported);
        List<Journal> journals = journals(s, s.exportStartDate(), end, exported);
        List<PaymentRow> payments = payments(s.exportStartDate(), end, exported);
        if (lines.isEmpty() && journals.isEmpty() && payments.isEmpty()) throw new IllegalStateException("Nothing new to export");

        List<DocLine> sales = lines.stream().filter(l -> "SALES".equals(l.type())).toList();
        List<DocLine> bills = lines.stream().filter(l -> !"SALES".equals(l.type())).toList();
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (!sales.isEmpty()) files.put("SalesInvoices.csv", salesCsv(s, sales));
        if (!bills.isEmpty()) files.put("Bills.csv", billsCsv(s, bills));
        if (!journals.isEmpty()) files.put("ManualJournals.csv", journalsCsv(journals));
        if (!payments.isEmpty()) files.put("Payments.csv", paymentsCsv(payments));
        files.put("README.txt", readme(s, end).getBytes(StandardCharsets.UTF_8));
        byte[] zip = zip(files);

        Map<Long, DocLine> docs = new LinkedHashMap<>();
        lines.forEach(l -> docs.putIfAbsent(l.invoiceId(), l));
        Map<String, Integer> counts = new HashMap<>();
        docs.values().forEach(l -> counts.merge(docKind(l), 1, Integer::sum));
        Long batchId = jdbc.queryForObject("""
                INSERT INTO accounting_export_batches (tenant_id, created_by, invoices, credit_notes, bills, supplier_credits, journals, payments, file)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, t, auditService.currentActorName(),
                counts.getOrDefault("INVOICE", 0), counts.getOrDefault("CREDIT_NOTE", 0), counts.getOrDefault("BILL", 0),
                counts.getOrDefault("SUPPLIER_CREDIT", 0), journals.size(), payments.size(), zip);
        List<Object[]> rows = new ArrayList<>();
        docs.values().forEach(l -> rows.add(new Object[]{t, batchId, "DOC:" + l.invoiceId(), Date.valueOf(l.date()), money(l.total())}));
        journals.forEach(j -> rows.add(new Object[]{t, batchId, j.key(), Date.valueOf(j.date()), j.amount()}));
        payments.forEach(p -> rows.add(new Object[]{t, batchId, p.key(), Date.valueOf(p.date()), money(p.amount())}));
        jdbc.batchUpdate("INSERT INTO accounting_exported (tenant_id, batch_id, doc_key, doc_date, amount) VALUES (?, ?, ?, ?, ?)", rows);
        auditService.log("ACCOUNTING", batchId, "EXPORT", Map.of("documents", docs.size(), "journals", journals.size(),
                "payments", payments.size()));
        return new Export(batchId, zip, filename(batchId, end));
    }

    /**
     * Phase J: Cin7's "Export imported tasks to Xero" unticked. The imported documents and their payments are recorded as
     * exported in a batch of their own (no file to send) so no export sends them; undoing that batch makes them exportable.
     */
    @Transactional
    public void markNotToExport(Collection<Long> invoiceIds, String note) {
        if (invoiceIds.isEmpty()) return;
        Long t = TenantContext.get();
        Long[] ids = invoiceIds.toArray(Long[]::new);
        List<Object[]> docs = jdbc.query("""
                SELECT id, issue_date, total_amount, type, kind FROM invoices
                 WHERE tenant_id = ? AND id = ANY(?) AND status <> 'DRAFT' AND status <> 'CANCELLED'""",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getDate(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5)}, t, ids);
        List<Object[]> pays = jdbc.query("""
                SELECT p.id, p.paid_at::date, p.amount FROM payments p JOIN invoices i ON i.id = p.invoice_id
                 WHERE i.tenant_id = ? AND i.id = ANY(?)""",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getDate(2), rs.getBigDecimal(3)}, t, ids);
        if (docs.isEmpty() && pays.isEmpty()) return;
        int invoices = 0, creditNotes = 0, bills = 0, supplierCredits = 0;
        for (Object[] d : docs) {
            boolean credit = "CREDIT_NOTE".equals(d[4]);
            if ("SALES".equals(d[3])) { if (credit) creditNotes++; else invoices++; }
            else if (credit) supplierCredits++; else bills++;
        }
        byte[] readme = zip(Map.of("README.txt", ("Not exported: " + note + "\r\nThese documents were imported with "
                + "'Export imported tasks to Xero' unticked (the accounting package has them already).\r\n").getBytes(StandardCharsets.UTF_8)));
        Long batchId = jdbc.queryForObject("""
                INSERT INTO accounting_export_batches (tenant_id, package, created_by, invoices, credit_notes, bills, supplier_credits, journals, payments, file)
                VALUES (?, 'NOT_EXPORTED', ?, ?, ?, ?, ?, 0, ?, ?) RETURNING id""", Long.class, t, auditService.currentActorName(),
                invoices, creditNotes, bills, supplierCredits, pays.size(), readme);
        List<Object[]> rows = new ArrayList<>();
        docs.forEach(d -> rows.add(new Object[]{t, batchId, "DOC:" + d[0], d[1], money((BigDecimal) d[2])}));
        pays.forEach(p -> rows.add(new Object[]{t, batchId, "PAY:" + p[0], p[1], money((BigDecimal) p[2])}));
        jdbc.batchUpdate("INSERT INTO accounting_exported (tenant_id, batch_id, doc_key, doc_date, amount) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id, doc_key) DO NOTHING", rows);
        auditService.log("ACCOUNTING", batchId, "NOT_EXPORTED", Map.of("documents", docs.size(), "payments", pays.size(), "note", note));
    }

    private static String filename(Long batchId, LocalDate day) {
        return "Xero_export_" + day + "_" + batchId + ".zip";
    }

    @Transactional(readOnly = true)
    public List<Batch> batches() {
        return jdbc.query("""
                SELECT id, created_at, created_by, invoices, credit_notes, bills, supplier_credits, journals, payments, undone_at, undone_by
                FROM accounting_export_batches WHERE tenant_id = ? ORDER BY created_at DESC, id DESC LIMIT 100""", (rs, n) -> new Batch(
                rs.getLong(1), rs.getTimestamp(2).toLocalDateTime(), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toLocalDateTime(),
                rs.getString(11)), TenantContext.get());
    }

    @Transactional(readOnly = true)
    public Export file(Long batchId) {
        List<Object[]> r = jdbc.query("SELECT file, created_at FROM accounting_export_batches WHERE tenant_id = ? AND id = ?",
                (rs, n) -> new Object[]{rs.getBytes(1), rs.getTimestamp(2).toLocalDateTime().toLocalDate()}, TenantContext.get(), batchId);
        if (r.isEmpty()) throw new com.experterp.backend.exception.ResourceNotFoundException("Export not found: " + batchId);
        return new Export(batchId, (byte[]) r.get(0)[0], filename(batchId, (LocalDate) r.get(0)[1]));
    }

    /** The package refused the files (or they were not imported): what the batch held can be exported again. */
    @Transactional
    public Batch undo(Long batchId) {
        Long t = TenantContext.get();
        int n = jdbc.update("UPDATE accounting_export_batches SET undone_at = now(), undone_by = ? WHERE tenant_id = ? AND id = ? AND undone_at IS NULL",
                auditService.currentActorName(), t, batchId);
        if (n == 0) throw new IllegalStateException("This export is not there or was undone already");
        jdbc.update("DELETE FROM accounting_exported WHERE tenant_id = ? AND batch_id = ?", t, batchId);
        auditService.log("ACCOUNTING", batchId, "EXPORT_UNDONE", Map.of());
        return batches().stream().filter(b -> b.id().equals(batchId)).findFirst().orElseThrow();
    }

    /** Exported documents cancelled here since, and exported payments voided since: to put right in the package. */
    @Transactional(readOnly = true)
    public List<Changed> changedAfterExport() {
        Long t = TenantContext.get();
        List<Changed> out = new ArrayList<>(jdbc.query("""
                SELECT e.doc_key, i.invoice_number, i.party_name, e.doc_date FROM accounting_exported e
                JOIN invoices i ON e.doc_key = 'DOC:' || i.id
                WHERE e.tenant_id = ? AND i.status = 'CANCELLED'""", (rs, n) -> new Changed(rs.getString(1), rs.getString(2),
                nn(rs.getString(3)), "Cancelled after it was exported", rs.getDate(4).toLocalDate()), t));
        out.addAll(jdbc.query("""
                SELECT e.doc_key, i.invoice_number, i.party_name, e.doc_date FROM accounting_exported e
                JOIN voided_payments v ON e.doc_key = 'PAY:' || v.payment_id
                JOIN invoices i ON i.id = v.invoice_id
                WHERE e.tenant_id = ?""", (rs, n) -> new Changed(rs.getString(1), rs.getString(2), nn(rs.getString(3)),
                "Payment voided after it was exported", rs.getDate(4).toLocalDate()), t));
        return out;
    }

    // ---- Xero's files ----

    // Xero's sales invoice import template; a credit note is imported from the same file with negative lines
    private byte[] salesCsv(Settings s, List<DocLine> lines) {
        return csv(List.of("*ContactName", "EmailAddress", "POAddressLine1", "POAddressLine2", "POAddressLine3", "POAddressLine4",
                "POCity", "PORegion", "POPostalCode", "POCountry", "*InvoiceNumber", "Reference", "*InvoiceDate", "*DueDate",
                "InventoryItemCode", "*Description", "*Quantity", "*UnitAmount", "Discount", "*AccountCode", "*TaxType", "TaxAmount",
                "TrackingName1", "TrackingOption1", "TrackingName2", "TrackingOption2", "Currency", "BrandingTheme"), p -> {
            for (DocLine l : lines) {
                BigDecimal sign = "CREDIT_NOTE".equals(l.kind()) ? BigDecimal.ONE.negate() : BigDecimal.ONE;
                p.printRecord(l.party(), "", "", "", "", "", "", "", "", "", l.number(), nn(l.reference()), xero(l.date()), xero(l.due()),
                        nn(l.sku()), description(l), plain(l.qty()), plain(l.unitPrice().multiply(sign)),
                        l.discount().signum() == 0 ? "" : plain(l.discount()), s.revenueAccount(), nn(l.taxName()),
                        plain(money(l.tax().multiply(sign))), "", "", "", "", "GBP", "");
            }
        });
    }

    // Xero's bill import template: stock on the inventory account, charges on the purchase charges account
    private byte[] billsCsv(Settings s, List<DocLine> lines) {
        return csv(List.of("*ContactName", "EmailAddress", "POAddressLine1", "POAddressLine2", "POAddressLine3", "POAddressLine4",
                "POCity", "PORegion", "POPostalCode", "POCountry", "*InvoiceNumber", "*InvoiceDate", "*DueDate", "InventoryItemCode",
                "Description", "*Quantity", "*UnitAmount", "*AccountCode", "*TaxType", "TaxAmount", "TrackingName1", "TrackingOption1",
                "TrackingName2", "TrackingOption2", "Currency"), p -> {
            for (DocLine l : lines) {
                BigDecimal sign = "CREDIT_NOTE".equals(l.kind()) ? BigDecimal.ONE.negate() : BigDecimal.ONE;
                // Net per unit after any discount (bills have no discount column)
                BigDecimal unit = l.qty().signum() == 0 ? l.net() : l.net().divide(l.qty(), 4, RoundingMode.HALF_UP);
                BigDecimal fullVat = l.net().signum() == 0 && l.tax().signum() != 0 ? l.tax() : null;
                if (fullVat != null) unit = l.qty().signum() == 0 ? fullVat : fullVat.divide(l.qty(), 4, RoundingMode.HALF_UP);
                p.printRecord(l.party(), "", "", "", "", "", "", "", "", "", l.number(), xero(l.date()), xero(l.due()), nn(l.sku()),
                        description(l), plain(l.qty()), plain(unit.multiply(sign)),
                        l.stock() ? s.inventoryAccount() : s.purchaseChargesAccount(), nn(l.taxName()),
                        plain(money(l.tax().multiply(sign))), "", "", "", "", "GBP");
            }
        });
    }

    // Xero's manual journal import: positive = debit, negative = credit; each journal balances
    private byte[] journalsCsv(List<Journal> journals) {
        return csv(List.of("*Narration", "*Date", "Description", "*AccountCode", "*TaxRate", "*Amount", "TrackingName1",
                "TrackingOption1", "TrackingName2", "TrackingOption2"), p -> {
            for (Journal j : journals) {
                p.printRecord(j.narration(), xero(j.date()), j.narration(), j.debit(), "No VAT", plain(j.amount()), "", "", "", "");
                p.printRecord(j.narration(), xero(j.date()), j.narration(), j.credit(), "No VAT", plain(j.amount().negate()), "", "", "", "");
            }
        });
    }

    // Not a Xero import: the payments to match to the bank feed in Xero (the Xero link will send them)
    private byte[] paymentsCsv(List<PaymentRow> payments) {
        return csv(List.of("Date", "Type", "Document", "Contact", "Amount", "Method", "Account", "Reference"), p -> {
            for (PaymentRow r : payments) {
                p.printRecord(xero(r.date()), r.side(), r.number(), r.party(), money(r.amount()).toPlainString(), r.method(), nn(r.account()), nn(r.reference()));
            }
        });
    }

    private static String readme(Settings s, LocalDate end) {
        return """
                Expert ERP export for Xero, documents dated %s to %s.

                SalesInvoices.csv  -> Xero: Business > Invoices > Import. Amounts are tax exclusive. Credit notes are the
                                      documents with negative lines; Xero imports them as credit notes.
                Bills.csv          -> Xero: Business > Bills to pay > Import. Amounts are tax exclusive. Supplier credit
                                      notes have negative lines.
                ManualJournals.csv -> Xero: Accounting > Manual journals > Import. COGS and stock adjustments at FIFO cost.
                Payments.csv       -> not an import: payments and refunds recorded in the ERP, to match in Xero
                                      (bank reconciliation) until the Xero link sends them.

                Accounts: revenue %s, inventory %s, COGS %s, inventory discrepancy %s, purchase charges %s.
                """.formatted(xero(s.exportStartDate()), xero(end), s.revenueAccount(), s.inventoryAccount(), s.cogsAccount(),
                s.discrepancyAccount(), s.purchaseChargesAccount());
    }

    @FunctionalInterface
    private interface Rows { void write(CSVPrinter p) throws IOException; }

    private static byte[] csv(List<String> header, Rows rows) {
        try {
            StringWriter w = new StringWriter();
            CSVPrinter p = new CSVPrinter(w, CSVFormat.DEFAULT.builder().setHeader(header.toArray(String[]::new)).build());
            rows.write(p);
            p.flush();
            return w.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] zip(Map<String, byte[]> files) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                for (Map.Entry<String, byte[]> f : files.entrySet()) {
                    z.putNextEntry(new ZipEntry(f.getKey()));
                    z.write(f.getValue());
                    z.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A line's description as exported (SKU: name). */
    public static String description(DocLine l) {
        String d = l.description().isBlank() ? (l.sku() != null ? l.sku() : "Item") : l.description();
        return d.length() > 4000 ? d.substring(0, 4000) : d;
    }

    private static String xero(LocalDate d) {
        return d == null ? "" : d.format(XERO_DATE);
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }
}
