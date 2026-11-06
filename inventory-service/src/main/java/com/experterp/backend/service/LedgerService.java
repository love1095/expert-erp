package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Phase K1: the general ledger, like Xero's. Every document posts a journal (double entry, debits = credits) with the
 * account mapping of the Xero export (H5), so the ledger and the export agree:
 * <ul>
 *   <li>sales invoice: Dr receivable (total), Cr revenue (net), Cr VAT; credit note the other way</li>
 *   <li>supplier bill: Dr inventory (stock lines) or purchase charges (the rest), Dr VAT, Cr payable; supplier credit the other way</li>
 *   <li>payment: bank (the payment account) against receivable / payable; paid with customer credit: customer credits
 *       against receivable; with a supplier deposit: payable against supplier deposits; credit note allocations post nothing</li>
 *   <li>customer credit received (overpayment, prepayment): Dr bank, Cr customer credits; supplier deposit: Dr deposits, Cr bank</li>
 *   <li>stock at FIFO cost (COGS on shipment and return, adjustments, bill price differences on goods gone), as the export's journals</li>
 *   <li>manual journals and conversion (opening) balances</li>
 * </ul>
 * Journals are never edited. The ledger is brought up to date ({@link #sync}) before it is read: for each source
 * (document, payment, manual journal…) what it should post is compared with what it has posted, and a difference posts
 * a reversal of the old journal and the new one (as Xero does when an invoice is edited or voided). Nothing dated
 * before the conversion date is posted: the conversion balances stand for it.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final long LOCK_BASE = 7_300_000_000L;
    private static final long SYNC_EVERY_MS = 1_000;

    private final JdbcTemplate jdbc;
    private final ChartOfAccountsService chart;
    private final AccountingExportService exportService;
    private final AuditService auditService;
    private final LedgerPeriodService periodService;
    private final Map<Long, Long> lastSync = new ConcurrentHashMap<>();

    // ---- settings ----

    /** The accounts the ledger posts to (H5's mapping plus the ledger's own), with Xero's standard codes when not set. */
    public record Settings(LocalDate conversionDate, String revenue, String inventory, String cogs, String discrepancy,
                           String purchaseCharges, String customerCredit, String supplierDeposits, String receivable,
                           String payable, String vat, String retainedEarnings, String suspense, String rounding, String historical) {}

    @Transactional(readOnly = true)
    public Settings settings() {
        List<Settings> s = jdbc.query("SELECT * FROM accounting_settings WHERE tenant_id = ?", (rs, n) -> new Settings(
                rs.getDate("conversion_date") == null ? null : rs.getDate("conversion_date").toLocalDate(),
                or(rs.getString("revenue_account"), "200"), or(rs.getString("inventory_account"), "631"),
                or(rs.getString("cogs_account"), "310"), or(rs.getString("discrepancy_account"), "495"),
                or(rs.getString("purchase_charges_account"), "425"), or(rs.getString("customer_credit_account"), "802"),
                or(rs.getString("supplier_deposits_account"), "615"), or(rs.getString("receivable_account"), "610"),
                or(rs.getString("payable_account"), "800"), or(rs.getString("vat_account"), "820"),
                or(rs.getString("retained_earnings_account"), "960"), or(rs.getString("suspense_account"), "850"),
                or(rs.getString("rounding_account"), "860"), or(rs.getString("historical_account"), "840")), TenantContext.get());
        return s.isEmpty() ? new Settings(null, "200", "631", "310", "495", "425", "802", "615", "610", "800", "820", "960", "850", "860", "840") : s.get(0);
    }

    @Transactional
    public Settings updateSettings(Settings s) {
        Long t = TenantContext.get();
        jdbc.update("INSERT INTO accounting_settings (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING", t);
        jdbc.update("""
                UPDATE accounting_settings SET conversion_date = ?, revenue_account = ?, inventory_account = ?, cogs_account = ?,
                       discrepancy_account = ?, purchase_charges_account = ?, customer_credit_account = ?, supplier_deposits_account = ?,
                       receivable_account = ?, payable_account = ?, vat_account = ?, retained_earnings_account = ?, suspense_account = ?,
                       rounding_account = ?, historical_account = ?, updated_at = now(), updated_by = ?
                 WHERE tenant_id = ?""",
                s.conversionDate() == null ? null : Date.valueOf(s.conversionDate()), code(s.revenue()), code(s.inventory()), code(s.cogs()),
                code(s.discrepancy()), code(s.purchaseCharges()), code(s.customerCredit()), code(s.supplierDeposits()), code(s.receivable()),
                code(s.payable()), code(s.vat()), code(s.retainedEarnings()), code(s.suspense()), code(s.rounding()), code(s.historical()),
                auditService.currentActorName(), t);
        auditService.log("LEDGER", null, "SETTINGS", Map.of("conversionDate", String.valueOf(s.conversionDate())));
        lastSync.remove(t);
        return settings();
    }

    // ---- posting ----

    public record Line(String account, BigDecimal amount, String description, String contact, String taxName) {}

    private record Expected(String key, String sourceType, LocalDate date, String reference, String narration, List<Line> lines) {}

    public record SyncResult(int posted, int reversed, List<String> addedAccounts) {}

    /** Brings the ledger up to date, at most every few seconds unless forced. */
    @Transactional
    public SyncResult sync(boolean force) {
        Long t = TenantContext.get();
        long now = System.currentTimeMillis();
        Long last = lastSync.get(t);
        if (!force && last != null && now - last < SYNC_EVERY_MS) return new SyncResult(0, 0, List.of());
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, LOCK_BASE + t);
        chart.loadStandardChart();
        Settings s = settings();
        LocalDate start = s.conversionDate() != null ? s.conversionDate() : LocalDate.of(1900, 1, 1);

        Map<String, Expected> expected = new LinkedHashMap<>();
        documents(s, start, expected);
        payments(s, start, expected);
        credits(s, start, expected);
        stock(s, start, expected);
        postedManualJournals(expected);
        opening(s, expected);

        // What each source has posted so far, per account
        Map<String, Map<String, BigDecimal>> posted = new HashMap<>();
        Map<String, Object[]> lastPosted = new HashMap<>();   // key → [date, sourceType, reference, narration]
        jdbc.query("""
                SELECT j.source_key, l.account_code, SUM(l.amount) FROM journals j JOIN journal_lines l ON l.journal_id = j.id
                 WHERE j.tenant_id = ? GROUP BY j.source_key, l.account_code""", rs -> {
            BigDecimal v = rs.getBigDecimal(3);
            if (v.signum() != 0) posted.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), v);
        }, t);
        jdbc.query("""
                SELECT DISTINCT ON (source_key) source_key, journal_date, source_type, reference, narration FROM journals
                 WHERE tenant_id = ? AND NOT reversal ORDER BY source_key, number DESC""",
                rs -> { lastPosted.put(rs.getString(1), new Object[]{rs.getDate(2).toLocalDate(), rs.getString(3), rs.getString(4), rs.getString(5)}); }, t);

        Set<String> codes = new HashSet<>();
        expected.values().forEach(e -> e.lines().forEach(l -> codes.add(l.account())));
        List<String> before = chart.byCode().keySet().stream().toList();
        chart.ensure(codes);
        List<String> added = codes.stream().filter(c -> !before.contains(c.toLowerCase(Locale.ROOT))).sorted().toList();

        long number = Optional.ofNullable(jdbc.queryForObject("SELECT MAX(number) FROM journals WHERE tenant_id = ?", Long.class, t)).orElse(0L);
        List<Object[]> journalRows = new ArrayList<>();
        List<List<Line>> journalLines = new ArrayList<>();
        int postedCount = 0, reversedCount = 0;
        Set<String> keys = new LinkedHashSet<>(expected.keySet());
        keys.addAll(posted.keySet());
        for (String key : keys) {
            Expected e = expected.get(key);
            Map<String, BigDecimal> want = e == null ? Map.of() : net(e.lines());
            Map<String, BigDecimal> have = posted.getOrDefault(key, Map.of());
            if (sameAmounts(want, have)) continue;
            if (!have.isEmpty()) {
                Object[] lp = lastPosted.get(key);
                LocalDate d = lp != null ? (LocalDate) lp[0] : (e != null ? e.date() : LocalDate.now());
                List<Line> rev = have.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(x -> new Line(x.getKey(), x.getValue().negate(), "Reversal", null, null)).toList();
                journalRows.add(new Object[]{++number, d, lp != null ? lp[1] : e.sourceType(), key, lp != null ? lp[2] : e.reference(),
                        "Reversal: " + (lp != null ? lp[3] : e.narration()), true});
                journalLines.add(rev);
                reversedCount++;
            }
            if (e != null && !want.isEmpty()) {
                journalRows.add(new Object[]{++number, e.date(), e.sourceType(), key, e.reference(), e.narration(), false});
                journalLines.add(e.lines());
                postedCount++;
            }
        }
        LocalDate hardLock = periodService.periods().hardLockDate();
        if (hardLock != null) {
            for (Object[] j : journalRows) {
                LocalDate d = (LocalDate) j[1];
                if (!d.isAfter(hardLock)) {
                    j[1] = hardLock.plusDays(1);
                    j[5] = cut(j[5] + " (dated " + d + ", in the locked period)", 500);
                }
            }
        }
        write(t, journalRows, journalLines);
        lastSync.put(t, System.currentTimeMillis());
        return new SyncResult(postedCount, reversedCount, added);
    }

    private void write(Long t, List<Object[]> journals, List<List<Line>> lines) {
        if (journals.isEmpty()) return;
        String by = auditService.currentActorName();
        for (int i = 0; i < journals.size(); i++) {
            Object[] j = journals.get(i);
            Long id = jdbc.queryForObject("""
                    INSERT INTO journals (tenant_id, number, journal_date, source_type, source_key, reference, narration, reversal, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                    t, j[0], Date.valueOf((LocalDate) j[1]), j[2], j[3], cut((String) j[4], 255), cut((String) j[5], 500), j[6], by);
            List<Object[]> rows = lines.get(i).stream().filter(l -> l.amount().signum() != 0)
                    .map(l -> new Object[]{t, id, l.account(), cut(l.description(), 500), cut(l.contact(), 255), l.amount(), l.taxName()}).toList();
            jdbc.batchUpdate("INSERT INTO journal_lines (tenant_id, journal_id, account_code, description, contact, amount, tax_name) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
        }
    }

    private static Map<String, BigDecimal> net(List<Line> lines) {
        Map<String, BigDecimal> m = new HashMap<>();
        lines.forEach(l -> m.merge(l.account(), l.amount(), BigDecimal::add));
        m.values().removeIf(v -> v.signum() == 0);
        return m;
    }

    private static boolean sameAmounts(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        return a.entrySet().stream().allMatch(e -> e.getValue().compareTo(b.get(e.getKey())) == 0);
    }

    // Sales invoices / credit notes and supplier bills / credits, issued or paid (cancelled ones post nothing: reversed)
    private void documents(Settings s, LocalDate start, Map<String, Expected> out) {
        Map<Long, List<Object[]>> items = new LinkedHashMap<>();
        Map<Long, Object[]> heads = new HashMap<>();
        jdbc.query("""
                SELECT i.id, i.type, i.kind, i.invoice_number, i.issue_date, COALESCE(c.name, s.name, i.party_name), i.total_amount,
                       p.product_type, it.total_price, it.tax_amount, it.tax_name
                  FROM invoices i
                  JOIN invoice_items it ON it.invoice_id = i.id
                  LEFT JOIN products p ON p.id = it.product_id
                  LEFT JOIN customers c ON c.id = i.customer_id
                  LEFT JOIN suppliers s ON s.id = i.supplier_id
                 WHERE i.tenant_id = ? AND i.status IN ('ISSUED', 'PAID') AND i.issue_date >= ?
                 ORDER BY i.issue_date, i.id, it.id""", rs -> {
            long id = rs.getLong(1);
            heads.putIfAbsent(id, new Object[]{rs.getString(2), rs.getString(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6), nz(rs.getBigDecimal(7))});
            items.computeIfAbsent(id, k -> new ArrayList<>()).add(new Object[]{"STOCK".equals(rs.getString(8)), nz(rs.getBigDecimal(9)),
                    nz(rs.getBigDecimal(10)), rs.getString(11)});
        }, TenantContext.get(), Date.valueOf(start));
        items.forEach((id, lines) -> {
            Object[] h = heads.get(id);
            boolean sale = "SALES".equals(h[0]), credit = "CREDIT_NOTE".equals(h[1]);
            String number = (String) h[2], party = (String) h[4];
            BigDecimal total = (BigDecimal) h[5];
            // Sales: credit revenue and VAT, debit the receivable; a bill debits and credits the payable. Credit notes: the other way
            int sign = (sale ? -1 : 1) * (credit ? -1 : 1);
            Map<String, BigDecimal[]> byAccountTax = new LinkedHashMap<>();
            BigDecimal vat = BigDecimal.ZERO, sum = BigDecimal.ZERO;
            for (Object[] l : lines) {
                String account = sale ? s.revenue() : ((boolean) l[0] ? s.inventory() : s.purchaseCharges());
                String tax = (String) l[3];
                byAccountTax.computeIfAbsent(account + "\u0000" + (tax == null ? "" : tax), k -> new BigDecimal[]{BigDecimal.ZERO})[0] =
                        byAccountTax.get(account + "\u0000" + (tax == null ? "" : tax))[0].add((BigDecimal) l[1]);
                vat = vat.add((BigDecimal) l[2]);
                sum = sum.add((BigDecimal) l[1]).add((BigDecimal) l[2]);
            }
            String kind = sale ? (credit ? "CREDIT_NOTE" : "INVOICE") : (credit ? "SUPPLIER_CREDIT" : "BILL");
            String what = sale ? (credit ? "Credit note " : "Invoice ") : (credit ? "Supplier credit " : "Bill ");
            List<Line> out2 = new ArrayList<>();
            byAccountTax.forEach((k, v) -> {
                String[] p = k.split("\u0000", -1);
                out2.add(new Line(p[0], money(v[0]).multiply(BigDecimal.valueOf(sign)), what + number, party, p[1].isEmpty() ? null : p[1]));
            });
            if (vat.signum() != 0) out2.add(new Line(s.vat(), money(vat).multiply(BigDecimal.valueOf(sign)), "VAT on " + what.trim().toLowerCase() + " " + number, party, null));
            out2.add(new Line(sale ? s.receivable() : s.payable(), money(total).multiply(BigDecimal.valueOf(-sign)), what + number, party, null));
            balance(out2, s, what + number);
            out.put("DOC:" + id, new Expected("DOC:" + id, kind, (LocalDate) h[3], number, what + number + " — " + party, out2));
        });
    }

    // Money in and out on documents (not credit note allocations, which stay within the receivable / payable)
    private void payments(Settings s, LocalDate start, Map<String, Expected> out) {
        String defaultBank = defaultBank(s);
        jdbc.query("""
                SELECT p.id, p.paid_at::date, i.type, i.kind, i.invoice_number, COALESCE(c.name, s.name, i.party_name), p.amount, p.method, pa.code
                  FROM payments p
                  JOIN invoices i ON i.id = p.invoice_id
                  LEFT JOIN payment_accounts pa ON pa.id = p.payment_account_id
                  LEFT JOIN customers c ON c.id = i.customer_id
                  LEFT JOIN suppliers s ON s.id = i.supplier_id
                 WHERE p.tenant_id = ? AND p.paid_at >= ? AND p.method <> 'CREDIT_NOTE'""", rs -> {
            boolean sale = "SALES".equals(rs.getString(3)), credit = "CREDIT_NOTE".equals(rs.getString(4));
            String number = rs.getString(5), party = rs.getString(6), method = rs.getString(8);
            BigDecimal amount = money(nz(rs.getBigDecimal(7)));
            String control = sale ? s.receivable() : s.payable();
            // The other side: the bank (payment account), or the credit / deposit used
            String other = "CUSTOMER_CREDIT".equals(method) ? s.customerCredit() : "SUPPLIER_CREDIT".equals(method) ? s.supplierDeposits()
                    : rs.getString(9) != null ? rs.getString(9) : defaultBank;
            // Money in: received from a customer, or refunded by a supplier; money out: paid to a supplier, refunded to a customer
            boolean in = sale != credit;
            String narration = (sale ? (credit ? "Refund to " : "Payment from ") : (credit ? "Refund from " : "Payment to ")) + party + " on " + number;
            List<Line> lines = List.of(new Line(other, in ? amount : amount.negate(), narration, party, null),
                    new Line(control, in ? amount.negate() : amount, narration, party, null));
            out.put("PAY:" + rs.getLong(1), new Expected("PAY:" + rs.getLong(1), "PAYMENT", rs.getDate(2).toLocalDate(), number, narration, lines));
        }, TenantContext.get(), java.sql.Timestamp.valueOf(start.atStartOfDay()));
    }

    // Money received as customer credit (overpayments, prepayments) and paid as supplier deposits
    private void credits(Settings s, LocalDate start, Map<String, Expected> out) {
        String defaultBank = defaultBank(s);
        jdbc.query("""
                SELECT cc.id, cc.received_on, c.name, cc.amount, pa.code, cc.method FROM customer_credits cc
                  LEFT JOIN customers c ON c.id = cc.customer_id LEFT JOIN payment_accounts pa ON pa.id = cc.payment_account_id
                 WHERE cc.tenant_id = ? AND cc.received_on >= ?""", rs -> {
            if (isCreditMethod(rs.getString(6))) return;
            BigDecimal a = money(nz(rs.getBigDecimal(4)));
            String bank = rs.getString(5) != null ? rs.getString(5) : defaultBank, party = rs.getString(3);
            String narration = "Customer credit received from " + party;
            out.put("CC:" + rs.getLong(1), new Expected("CC:" + rs.getLong(1), "CREDIT", rs.getDate(2).toLocalDate(), null, narration,
                    List.of(new Line(bank, a, narration, party, null), new Line(s.customerCredit(), a.negate(), narration, party, null))));
        }, TenantContext.get(), Date.valueOf(start));
        jdbc.query("""
                SELECT sc.id, sc.paid_on, s.name, sc.amount, pa.code, sc.method FROM supplier_credits sc
                  LEFT JOIN suppliers s ON s.id = sc.supplier_id LEFT JOIN payment_accounts pa ON pa.id = sc.payment_account_id
                 WHERE sc.tenant_id = ? AND sc.paid_on >= ?""", rs -> {
            if (isCreditMethod(rs.getString(6))) return;
            BigDecimal a = money(nz(rs.getBigDecimal(4)));
            String bank = rs.getString(5) != null ? rs.getString(5) : defaultBank, party = rs.getString(3);
            String narration = "Supplier deposit paid to " + party;
            out.put("SC:" + rs.getLong(1), new Expected("SC:" + rs.getLong(1), "CREDIT", rs.getDate(2).toLocalDate(), null, narration,
                    List.of(new Line(s.supplierDeposits(), a, narration, party, null), new Line(bank, a.negate(), narration, party, null))));
        }, TenantContext.get(), Date.valueOf(start));
    }

    // Stock at FIFO cost: the Xero export's journals, so both agree
    private void stock(Settings s, LocalDate start, Map<String, Expected> out) {
        AccountingExportService.Settings mapping = new AccountingExportService.Settings(null, s.revenue(), s.inventory(), s.cogs(),
                s.discrepancy(), s.purchaseCharges(), s.customerCredit(), s.supplierDeposits());
        for (AccountingExportService.Journal j : exportService.journals(mapping, start, LocalDate.now().plusDays(1), Set.of())) {
            out.put(j.key(), new Expected(j.key(), "STOCK", j.date(), null, j.narration(),
                    List.of(new Line(j.debit(), j.amount(), j.narration(), null, null), new Line(j.credit(), j.amount().negate(), j.narration(), null, null))));
        }
    }

    private void postedManualJournals(Map<String, Expected> out) {
        Map<Long, List<Line>> lines = new HashMap<>();
        jdbc.query("""
                SELECT l.manual_journal_id, l.account_code, l.amount, l.description FROM manual_journal_lines l
                  JOIN manual_journals m ON m.id = l.manual_journal_id WHERE m.tenant_id = ? AND m.status = 'POSTED' ORDER BY l.line_no""",
                rs -> { lines.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Line(rs.getString(2), rs.getBigDecimal(3), rs.getString(4), null, null)); },
                TenantContext.get());
        jdbc.query("SELECT id, journal_date, narration FROM manual_journals WHERE tenant_id = ? AND status = 'POSTED'", rs -> {
            long id = rs.getLong(1);
            out.put("MJ:" + id, new Expected("MJ:" + id, "MANUAL", rs.getDate(2).toLocalDate(), "MJ-" + id, rs.getString(3), lines.getOrDefault(id, List.of())));
        }, TenantContext.get());
    }

    // Xero's conversion balances: dated the day before the conversion date; what does not balance goes to Historical Adjustment
    private void opening(Settings s, Map<String, Expected> out) {
        if (s.conversionDate() == null) return;
        List<Line> lines = new ArrayList<>();
        jdbc.query("SELECT account_code, amount FROM ledger_opening_balances WHERE tenant_id = ? AND amount <> 0 ORDER BY account_code",
                rs -> { lines.add(new Line(rs.getString(1), rs.getBigDecimal(2), "Conversion balance", null, null)); }, TenantContext.get());
        if (lines.isEmpty()) return;
        BigDecimal diff = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (diff.signum() != 0) lines.add(new Line(s.historical(), diff.negate(), "Conversion balances: difference", null, null));
        out.put("OPENING", new Expected("OPENING", "OPENING", s.conversionDate().minusDays(1), null, "Conversion balances", lines));
    }

    /** A penny left by rounding goes to the rounding account, so every journal balances. */
    private static void balance(List<Line> lines, Settings s, String what) {
        BigDecimal diff = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (diff.signum() != 0) lines.add(new Line(s.rounding(), diff.negate(), "Rounding on " + what, null, null));
    }

    private String defaultBank(Settings s) {
        List<String> d = jdbc.queryForList("SELECT code FROM payment_accounts WHERE tenant_id = ? AND is_default AND active ORDER BY code",
                String.class, TenantContext.get());
        return d.isEmpty() ? s.suspense() : d.get(0);
    }

    // ---- manual journals ----

    public record ManualLine(String accountCode, String description, BigDecimal debit, BigDecimal credit) {}

    public record ManualJournalRequest(LocalDate date, String narration, List<ManualLine> lines, boolean post) {}

    public record ManualJournal(Long id, LocalDate date, String narration, String status, BigDecimal total, List<ManualLine> lines,
                                LocalDateTime createdAt, String createdBy, LocalDateTime voidedAt, String voidedBy) {}

    @Transactional(readOnly = true)
    public List<ManualJournal> manualJournals(String status) {
        Long t = TenantContext.get();
        Map<Long, List<ManualLine>> lines = new HashMap<>();
        jdbc.query("SELECT manual_journal_id, account_code, description, amount FROM manual_journal_lines WHERE tenant_id = ? ORDER BY manual_journal_id, line_no",
                rs -> {
                    BigDecimal a = rs.getBigDecimal(4);
                    lines.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new ManualLine(rs.getString(2), rs.getString(3),
                            a.signum() > 0 ? a : null, a.signum() < 0 ? a.negate() : null));
                }, t);
        return jdbc.query("SELECT * FROM manual_journals WHERE tenant_id = ? AND (?::varchar IS NULL OR status = ?::varchar) ORDER BY journal_date DESC, id DESC LIMIT 500",
                (rs, n) -> {
                    long id = rs.getLong("id");
                    List<ManualLine> l = lines.getOrDefault(id, List.of());
                    BigDecimal total = l.stream().map(x -> x.debit() == null ? BigDecimal.ZERO : x.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new ManualJournal(id, rs.getDate("journal_date").toLocalDate(), rs.getString("narration"), rs.getString("status"), total, l,
                            rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("created_by"),
                            rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toLocalDateTime(), rs.getString("voided_by"));
                }, t, status, status);
    }

    /** Xero's manual journal: a narration, a date and lines (account, description, debit or credit); debits must equal credits. */
    @Transactional
    public ManualJournal saveManual(Long id, ManualJournalRequest req) {
        Long t = TenantContext.get();
        if (req.date() == null) throw new IllegalArgumentException("The date is required");
        periodService.check(req.date(), "a manual journal");
        chart.loadStandardChart();
        if (req.narration() == null || req.narration().isBlank()) throw new IllegalArgumentException("The narration is required");
        List<ManualLine> lines = req.lines() == null ? List.of() : req.lines().stream()
                .filter(l -> l.accountCode() != null && !l.accountCode().isBlank() && (nz(l.debit()).signum() != 0 || nz(l.credit()).signum() != 0)).toList();
        if (lines.size() < 2) throw new IllegalArgumentException("A journal needs at least two lines");
        Map<String, ChartOfAccountsService.Account> accounts = chart.byCode();
        BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
        for (ManualLine l : lines) {
            ChartOfAccountsService.Account a = accounts.get(l.accountCode().trim().toLowerCase(Locale.ROOT));
            if (a == null) throw new IllegalArgumentException("Account " + l.accountCode() + " is not in the chart of accounts");
            if (a.archived()) throw new IllegalArgumentException("Account " + a.code() + " is archived");
            if (nz(l.debit()).signum() < 0 || nz(l.credit()).signum() < 0) throw new IllegalArgumentException("Amounts are positive: a debit or a credit");
            if (nz(l.debit()).signum() > 0 && nz(l.credit()).signum() > 0) throw new IllegalArgumentException("A line is a debit or a credit, not both");
            debits = debits.add(nz(l.debit()));
            credits = credits.add(nz(l.credit()));
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Debits (" + money(debits).toPlainString() + ") and credits (" + money(credits).toPlainString() + ") must be equal");
        }
        if (id == null) {
            id = jdbc.queryForObject("INSERT INTO manual_journals (tenant_id, journal_date, narration, created_by) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, t, Date.valueOf(req.date()), req.narration().trim(), auditService.currentActorName());
        } else {
            String status = jdbc.query("SELECT status FROM manual_journals WHERE tenant_id = ? AND id = ?", rs -> rs.next() ? rs.getString(1) : null, t, id);
            if (status == null) throw new IllegalArgumentException("Manual journal not found");
            if (!"DRAFT".equals(status)) throw new IllegalStateException("Only a draft can be changed: void a posted journal and enter it again");
            jdbc.update("UPDATE manual_journals SET journal_date = ?, narration = ? WHERE id = ?", Date.valueOf(req.date()), req.narration().trim(), id);
            jdbc.update("DELETE FROM manual_journal_lines WHERE manual_journal_id = ?", id);
        }
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ManualLine l = lines.get(i);
            BigDecimal amount = nz(l.debit()).signum() > 0 ? money(l.debit()) : money(l.credit()).negate();
            rows.add(new Object[]{t, id, i + 1, accounts.get(l.accountCode().trim().toLowerCase(Locale.ROOT)).code(), cut(l.description(), 500), amount});
        }
        jdbc.batchUpdate("INSERT INTO manual_journal_lines (tenant_id, manual_journal_id, line_no, account_code, description, amount) VALUES (?, ?, ?, ?, ?, ?)", rows);
        auditService.log("MANUAL_JOURNAL", id, "SAVE", Map.of("date", req.date().toString(), "total", money(debits)));
        if (req.post()) post(id);
        Long finalId = id;
        return manualJournals((String) null).stream().filter(m -> m.id().equals(finalId)).findFirst().orElseThrow();
    }

    @Transactional
    public void post(Long id) {
        periodService.check(manualDate(id), "a manual journal");
        int n = jdbc.update("UPDATE manual_journals SET status = 'POSTED', posted_at = now() WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'",
                TenantContext.get(), id);
        if (n == 0) throw new IllegalStateException("Only a draft can be posted");
        auditService.log("MANUAL_JOURNAL", id, "POST", Map.of());
        sync(true);
    }

    /** Xero's Void: the journal is reversed (a reversing journal on its date); a draft is deleted instead. */
    @Transactional
    public void voidOrDelete(Long id) {
        Long t = TenantContext.get();
        String status = jdbc.query("SELECT status FROM manual_journals WHERE tenant_id = ? AND id = ?", rs -> rs.next() ? rs.getString(1) : null, t, id);
        if (status == null) throw new IllegalArgumentException("Manual journal not found");
        if ("DRAFT".equals(status)) {
            jdbc.update("DELETE FROM manual_journals WHERE id = ?", id);
            auditService.log("MANUAL_JOURNAL", id, "DELETE", Map.of());
            return;
        }
        if ("VOIDED".equals(status)) throw new IllegalStateException("Already voided");
        periodService.check(manualDate(id), "a manual journal");
        jdbc.update("UPDATE manual_journals SET status = 'VOIDED', voided_at = now(), voided_by = ? WHERE id = ?", auditService.currentActorName(), id);
        auditService.log("MANUAL_JOURNAL", id, "VOID", Map.of());
        sync(true);
    }

    private LocalDate manualDate(Long id) {
        return jdbc.query("SELECT journal_date FROM manual_journals WHERE tenant_id = ? AND id = ?",
                rs -> rs.next() ? rs.getDate(1).toLocalDate() : null, TenantContext.get(), id);
    }

    // ---- conversion balances ----

    public record OpeningBalance(String accountCode, BigDecimal debit, BigDecimal credit) {}

    public record Opening(LocalDate conversionDate, List<OpeningBalance> balances, BigDecimal debits, BigDecimal credits, BigDecimal historicalAdjustment) {}

    @Transactional(readOnly = true)
    public Opening opening() {
        List<OpeningBalance> b = jdbc.query("SELECT account_code, amount FROM ledger_opening_balances WHERE tenant_id = ? ORDER BY account_code",
                (rs, n) -> {
                    BigDecimal a = rs.getBigDecimal(2);
                    return new OpeningBalance(rs.getString(1), a.signum() > 0 ? a : null, a.signum() < 0 ? a.negate() : null);
                }, TenantContext.get());
        BigDecimal d = b.stream().map(x -> nz(x.debit())).reduce(ZERO, BigDecimal::add), c = b.stream().map(x -> nz(x.credit())).reduce(ZERO, BigDecimal::add);
        return new Opening(settings().conversionDate(), b, d, c, c.subtract(d));
    }

    @Transactional
    public Opening saveOpening(List<OpeningBalance> balances) {
        if (settings().conversionDate() == null) throw new IllegalStateException("Set the conversion date first");
        periodService.check(settings().conversionDate().minusDays(1), "the conversion balances");
        chart.loadStandardChart();
        Long t = TenantContext.get();
        Map<String, ChartOfAccountsService.Account> accounts = chart.byCode();
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (OpeningBalance b : balances) {
            if (b.accountCode() == null || b.accountCode().isBlank()) continue;
            ChartOfAccountsService.Account a = accounts.get(b.accountCode().trim().toLowerCase(Locale.ROOT));
            if (a == null) throw new IllegalArgumentException("Account " + b.accountCode() + " is not in the chart of accounts");
            amounts.merge(a.code(), money(nz(b.debit())).subtract(money(nz(b.credit()))), BigDecimal::add);
        }
        jdbc.update("DELETE FROM ledger_opening_balances WHERE tenant_id = ?", t);
        jdbc.batchUpdate("INSERT INTO ledger_opening_balances (tenant_id, account_code, amount) VALUES (?, ?, ?)",
                amounts.entrySet().stream().filter(e -> e.getValue().signum() != 0).map(e -> new Object[]{t, e.getKey(), e.getValue()}).toList());
        auditService.log("LEDGER", null, "CONVERSION_BALANCES", Map.of("accounts", amounts.size()));
        sync(true);
        return opening();
    }

    // ---- reports ----

    public record TrialBalanceRow(String code, String name, String type, String accountClass, BigDecimal debit, BigDecimal credit) {}

    public record TrialBalance(LocalDate asAt, List<TrialBalanceRow> rows, BigDecimal debits, BigDecimal credits, List<String> warnings) {}

    /** Xero's Trial Balance: each account's balance on a day, as a debit or a credit; the two columns must be equal. */
    @Transactional
    public TrialBalance trialBalance(LocalDate asAt) {
        SyncResult r = sync(false);
        LocalDate day = asAt == null ? LocalDate.now() : asAt;
        Map<String, ChartOfAccountsService.Account> accounts = chart.byCode();
        List<TrialBalanceRow> rows = new ArrayList<>();
        jdbc.query("""
                SELECT l.account_code, SUM(l.amount) FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                 WHERE j.tenant_id = ? AND j.journal_date <= ? GROUP BY l.account_code HAVING SUM(l.amount) <> 0""", rs -> {
            ChartOfAccountsService.Account a = accounts.get(rs.getString(1).toLowerCase(Locale.ROOT));
            BigDecimal v = rs.getBigDecimal(2);
            rows.add(new TrialBalanceRow(rs.getString(1), a == null ? "Unknown account" : a.name(), a == null ? "" : a.typeLabel(),
                    a == null ? "ASSET" : a.accountClass(), v.signum() > 0 ? v : null, v.signum() < 0 ? v.negate() : null));
        }, TenantContext.get(), Date.valueOf(day));
        List<String> classes = List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");
        rows.sort(Comparator.comparing((TrialBalanceRow x) -> classes.indexOf(x.accountClass())).thenComparing(TrialBalanceRow::code));
        BigDecimal d = rows.stream().map(x -> nz(x.debit())).reduce(ZERO, BigDecimal::add);
        BigDecimal c = rows.stream().map(x -> nz(x.credit())).reduce(ZERO, BigDecimal::add);
        List<String> warnings = new ArrayList<>();
        if (!r.addedAccounts().isEmpty()) warnings.add("Accounts added to the chart because documents post to them: " + String.join(", ", r.addedAccounts()));
        if (d.compareTo(c) != 0) warnings.add("Debits and credits differ by " + money(d.subtract(c)).toPlainString());
        return new TrialBalance(day, rows, d, c, warnings);
    }

    public record TransactionRow(LocalDate date, long journalNumber, String sourceType, String sourceKey, String reference,
                                 String narration, String description, String contact, BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    public record AccountTransactions(String code, String name, String type, LocalDate from, LocalDate to, BigDecimal opening,
                                      List<TransactionRow> rows, BigDecimal debits, BigDecimal credits, BigDecimal closing) {}

    /** Xero's Account Transactions: an account's lines in a period, with the balance before, a running balance and after. */
    @Transactional
    public AccountTransactions accountTransactions(String code, LocalDate from, LocalDate to) {
        sync(false);
        ChartOfAccountsService.Account a = chart.byCode().get(code.trim().toLowerCase(Locale.ROOT));
        if (a == null) throw new IllegalArgumentException("Account " + code + " is not in the chart of accounts");
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Give the period (from – to)");
        Long t = TenantContext.get();
        BigDecimal opening = money(Optional.ofNullable(jdbc.queryForObject("""
                SELECT SUM(l.amount) FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                 WHERE j.tenant_id = ? AND lower(l.account_code) = lower(?) AND j.journal_date < ?""", BigDecimal.class, t, a.code(), Date.valueOf(from)))
                .orElse(BigDecimal.ZERO));
        BigDecimal[] running = {opening};
        List<TransactionRow> rows = jdbc.query("""
                SELECT j.journal_date, j.number, j.source_type, j.source_key, j.reference, j.narration, l.description, l.contact, l.amount
                  FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                 WHERE j.tenant_id = ? AND lower(l.account_code) = lower(?) AND j.journal_date BETWEEN ? AND ?
                 ORDER BY j.journal_date, j.number, l.id LIMIT 20000""", (rs, n) -> {
            BigDecimal v = rs.getBigDecimal(9);
            running[0] = running[0].add(v);
            return new TransactionRow(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getString(8), v.signum() > 0 ? v : null, v.signum() < 0 ? v.negate() : null, running[0]);
        }, t, a.code(), Date.valueOf(from), Date.valueOf(to));
        BigDecimal d = rows.stream().map(x -> nz(x.debit())).reduce(ZERO, BigDecimal::add), c = rows.stream().map(x -> nz(x.credit())).reduce(ZERO, BigDecimal::add);
        return new AccountTransactions(a.code(), a.name(), a.typeLabel(), from, to, opening, rows, d, c, opening.add(d).subtract(c));
    }

    public record JournalView(long number, LocalDate date, String sourceType, String sourceKey, String reference, String narration,
                              boolean reversal, LocalDateTime createdAt, List<Line> lines) {}

    /** Xero's Journal Report: every journal posted in a period with its lines (newest first). */
    @Transactional
    public List<JournalView> journalReport(LocalDate from, LocalDate to, String sourceKey) {
        sync(false);
        Long t = TenantContext.get();
        List<Object[]> heads = jdbc.query("""
                SELECT id, number, journal_date, source_type, source_key, reference, narration, reversal, created_at FROM journals
                 WHERE tenant_id = ? AND (?::date IS NULL OR journal_date >= ?::date) AND (?::date IS NULL OR journal_date <= ?::date)
                   AND (?::varchar IS NULL OR source_key = ?::varchar)
                 ORDER BY number DESC LIMIT 500""", (rs, n) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate(), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getTimestamp(9).toLocalDateTime()},
                t, d(from), d(from), d(to), d(to), sourceKey, sourceKey);
        if (heads.isEmpty()) return List.of();
        Map<Long, List<Line>> lines = new HashMap<>();
        jdbc.query("SELECT journal_id, account_code, amount, description, contact, tax_name FROM journal_lines WHERE journal_id = ANY(?::bigint[]) ORDER BY id",
                rs -> { lines.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Line(rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getString(6))); },
                (Object) heads.stream().map(h -> (Long) h[0]).toArray(Long[]::new));
        return heads.stream().map(h -> new JournalView((Long) h[1], (LocalDate) h[2], (String) h[3], (String) h[4], (String) h[5], (String) h[6],
                (Boolean) h[7], (LocalDateTime) h[8], lines.getOrDefault((Long) h[0], List.of()))).collect(Collectors.toList());
    }

    // ---- shared ----

    private static boolean isCreditMethod(String method) {
        return "CREDIT_NOTE".equals(method) || "CUSTOMER_CREDIT".equals(method) || "SUPPLIER_CREDIT".equals(method);
    }

    private static Date d(LocalDate v) {
        return v == null ? null : Date.valueOf(v);
    }

    private static String or(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String code(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
