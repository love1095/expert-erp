package com.experterp.backend.service;

import com.experterp.backend.entity.TaxRate;
import com.experterp.backend.multitenancy.TenantContext;
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
import java.security.MessageDigest;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase K3: bank accounts, statements and reconciliation, like Xero's. A statement (CSV or OFX) is imported into a
 * bank account; each statement line is reconciled by <b>matching</b> what the ledger already has on that account
 * (payments, customer credit, supplier deposits, manual journals, bank transactions — one or several summing to the
 * line), by <b>creating</b> a spend / receive money transaction, or by a <b>transfer</b> to another bank account.
 * Reconciling can be undone; a reconciled payment or bank transaction cannot be voided until it is unreconciled.
 */
@Service
@RequiredArgsConstructor
public class BankService {

    private static final int SUGGEST_DAYS = 60;

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final ChartOfAccountsService chart;
    private final LedgerPeriodService periods;
    private final TaxService taxService;
    private final AuditService auditService;
    private final ReconciliationGuard guard;

    // ---- accounts ----

    public record BankAccount(String code, String name, BigDecimal ledgerBalance, BigDecimal statementBalance, LocalDate statementDate,
                              int toReconcile, BigDecimal unreconciledStatement, BigDecimal unmatchedLedger) {}

    /** Xero's dashboard bank cards: balance in the ERP, statement balance, items to reconcile. */
    @Transactional
    public List<BankAccount> accounts() {
        ledger.sync(false);
        Long t = TenantContext.get();
        List<BankAccount> out = new ArrayList<>();
        for (ChartOfAccountsService.Account a : chart.list(false)) {
            if (a.type() != ChartOfAccountsService.Type.BANK) continue;
            BigDecimal ledgerBalance = nz(jdbc.queryForObject("""
                    SELECT SUM(l.amount) FROM journal_lines l WHERE l.tenant_id = ? AND lower(l.account_code) = lower(?)""", BigDecimal.class, t, a.code()));
            Object[] last = jdbc.query("""
                    SELECT balance, line_date FROM bank_statement_lines WHERE tenant_id = ? AND lower(account_code) = lower(?)
                     ORDER BY line_date DESC, id DESC LIMIT 1""", rs -> rs.next() ? new Object[]{rs.getBigDecimal(1), rs.getDate(2).toLocalDate()} : null, t, a.code());
            Object[] open = jdbc.queryForObject("""
                    SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM bank_statement_lines WHERE tenant_id = ? AND lower(account_code) = lower(?) AND status = 'UNRECONCILED'""",
                    (rs, n) -> new Object[]{rs.getInt(1), rs.getBigDecimal(2)}, t, a.code());
            BigDecimal unmatched = unmatchedItems(a.code()).stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new BankAccount(a.code(), a.name(), money(ledgerBalance), last == null ? null : (BigDecimal) last[0],
                    last == null ? null : (LocalDate) last[1], (Integer) open[0], money((BigDecimal) open[1]), money(unmatched)));
        }
        return out;
    }

    // ---- statement import ----

    public record ParsedLine(LocalDate date, BigDecimal amount, String payee, String reference, String description, BigDecimal balance, boolean duplicate) {}

    public record ImportResult(boolean applied, String format, int lines, int duplicates, BigDecimal moneyIn, BigDecimal moneyOut,
                               LocalDate from, LocalDate to, List<ParsedLine> preview, List<String> warnings) {}

    /** A CSV (Xero's template, or a bank's own: Date, Amount or Paid in / Paid out, Payee / Description, Reference, Balance) or an OFX file. */
    @Transactional
    public ImportResult importStatement(String accountCode, InputStream in, String fileName, boolean apply) throws IOException {
        String code = bankCode(accountCode);
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        List<String> warnings = new ArrayList<>();
        boolean ofx = text.contains("<OFX>") || text.startsWith("OFXHEADER");
        List<ParsedLine> parsed = ofx ? parseOfx(text, warnings) : parseCsv(text, warnings);
        if (parsed.isEmpty()) throw new IllegalArgumentException("No statement lines found in the file");

        Long t = TenantContext.get();
        Set<String> seen = new HashSet<>(jdbc.queryForList("SELECT fingerprint FROM bank_statement_lines WHERE tenant_id = ? AND lower(account_code) = lower(?)",
                String.class, t, code));
        List<ParsedLine> lines = new ArrayList<>();
        int dup = 0;
        BigDecimal in2 = BigDecimal.ZERO, out = BigDecimal.ZERO;
        for (ParsedLine p : parsed) {
            String f = fingerprint(p);
            boolean d = !seen.add(f);
            if (d) dup++;
            else if (p.amount().signum() > 0) in2 = in2.add(p.amount());
            else out = out.add(p.amount().negate());
            lines.add(new ParsedLine(p.date(), p.amount(), p.payee(), p.reference(), p.description(), p.balance(), d));
        }
        LocalDate from = lines.stream().map(ParsedLine::date).min(Comparator.naturalOrder()).orElse(null);
        LocalDate to = lines.stream().map(ParsedLine::date).max(Comparator.naturalOrder()).orElse(null);
        if (dup > 0) warnings.add(dup + (dup == 1 ? " line was" : " lines were") + " imported before and will be skipped");
        ImportResult result = new ImportResult(apply, ofx ? "OFX" : "CSV", lines.size() - dup, dup, money(in2), money(out), from, to,
                lines.subList(0, Math.min(200, lines.size())), warnings);
        if (!apply) return result;
        if (lines.size() == dup) throw new IllegalStateException("Every line in this file was imported before");

        Long importId = jdbc.queryForObject("""
                INSERT INTO bank_statement_imports (tenant_id, account_code, file_name, lines, duplicates, created_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, t, code, cut(fileName, 255), lines.size() - dup, dup, auditService.currentActorName());
        jdbc.batchUpdate("""
                INSERT INTO bank_statement_lines (tenant_id, account_code, import_id, line_date, amount, payee, reference, description, balance, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", lines.stream().filter(l -> !l.duplicate()).map(l -> new Object[]{t, code, importId,
                Date.valueOf(l.date()), l.amount(), cut(l.payee(), 255), cut(l.reference(), 255), cut(l.description(), 500), l.balance(), fingerprint(l)}).toList());
        auditService.log("BANK_STATEMENT", importId, "IMPORT", Map.of("account", code, "lines", lines.size() - dup, "duplicates", dup));
        return result;
    }

    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ofPattern("d/M/yyyy"), DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/yy"), DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH), DateTimeFormatter.ofPattern("yyyyMMdd"));

    static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() > 10 && t.charAt(10) == 'T') t = t.substring(0, 10);
        for (DateTimeFormatter f : DATES) {
            try { return LocalDate.parse(t, f); } catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    static BigDecimal amount(String s) {
        if (s == null) return null;
        String t = s.trim().replace("£", "").replace(",", "").replace(" ", "");
        if (t.isEmpty()) return null;
        boolean negative = t.startsWith("(") && t.endsWith(")") || t.endsWith("-") || t.toUpperCase().endsWith("DR");
        t = t.replaceAll("[()]", "").replaceAll("(?i)(CR|DR)$", "").replaceAll("-$", "");
        try {
            BigDecimal v = new BigDecimal(t);
            return (negative ? v.negate() : v).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<ParsedLine> parseCsv(String text, List<String> warnings) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setAllowMissingColumnNames(true)
                .setIgnoreSurroundingSpaces(true).build();
        List<ParsedLine> out = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            CsvColumns c = new CsvColumns(parser.getHeaderNames().stream().map(h -> h.trim().replace("*", "")).toList());
            String dateCol = first(c, "Date", "Transaction Date", "Posting Date", "Posted Date", "Value Date");
            String amountCol = first(c, "Amount", "Value", "Amount (GBP)");
            String inCol = first(c, "Paid in", "Money in", "Credit", "Credit Amount", "Paid In (GBP)");
            String outCol = first(c, "Paid out", "Money out", "Debit", "Debit Amount", "Paid Out (GBP)");
            String payeeCol = first(c, "Payee", "Name", "Counter Party", "Counterparty");
            String descCol = first(c, "Description", "Transaction Description", "Narrative", "Details", "Memo");
            String refCol = first(c, "Reference", "Check Number", "Cheque Number", "Ref");
            String balCol = first(c, "Balance", "Running Balance", "Balance (GBP)");
            if (dateCol == null || (amountCol == null && inCol == null && outCol == null)) {
                throw new IllegalArgumentException("The file needs a Date column and an Amount column (or Paid in / Paid out)");
            }
            for (CSVRecord r : parser) {
                LocalDate d = date(c.get(r, dateCol));
                BigDecimal a = amountCol != null ? amount(c.get(r, amountCol)) : null;
                if (a == null && (inCol != null || outCol != null)) {
                    BigDecimal i = amount(inCol == null ? null : c.get(r, inCol)), o = amount(outCol == null ? null : c.get(r, outCol));
                    if (i != null || o != null) a = nz(i).abs().subtract(nz(o).abs());
                }
                if (d == null || a == null) {
                    if (r.size() > 1 && c.get(r, dateCol) != null) warnings.add("Row " + (r.getRecordNumber() + 1) + " skipped: no date or amount");
                    continue;
                }
                if (a.signum() == 0) continue;
                String payee = payeeCol == null ? null : c.get(r, payeeCol), desc = descCol == null ? null : c.get(r, descCol);
                if (payee == null) payee = desc;
                out.add(new ParsedLine(d, a, payee, refCol == null ? null : c.get(r, refCol), desc, balCol == null ? null : amount(c.get(r, balCol)), false));
            }
        }
        return out;
    }

    private static String first(CsvColumns c, String... names) {
        for (String n : names) if (c.has(n)) return n;
        return null;
    }

    private static final Pattern STMTTRN = Pattern.compile("<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** OFX 1 (SGML) and 2 (XML): STMTTRN entries with DTPOSTED, TRNAMT, NAME, MEMO, CHECKNUM / REFNUM. */
    private static List<ParsedLine> parseOfx(String text, List<String> warnings) {
        List<ParsedLine> out = new ArrayList<>();
        Matcher m = STMTTRN.matcher(text);
        while (m.find()) {
            String t = m.group(1);
            LocalDate d = date(tag(t, "DTPOSTED") == null ? null : tag(t, "DTPOSTED").substring(0, Math.min(8, tag(t, "DTPOSTED").length())));
            BigDecimal a = amount(tag(t, "TRNAMT"));
            if (d == null || a == null) { warnings.add("An OFX entry without a date or amount was skipped"); continue; }
            String ref = tag(t, "CHECKNUM") != null ? tag(t, "CHECKNUM") : tag(t, "REFNUM");
            out.add(new ParsedLine(d, a, tag(t, "NAME"), ref, tag(t, "MEMO"), null, false));
        }
        return out;
    }

    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        return m.find() ? (m.group(1).trim().isEmpty() ? null : m.group(1).trim()) : null;
    }

    private static String fingerprint(ParsedLine p) {
        String s = p.date() + "|" + p.amount().toPlainString() + "|" + norm(p.payee()) + "|" + norm(p.reference()) + "|" + norm(p.description());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 64);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- reconcile ----

    /** Something on the bank account in the ledger, not reconciled yet (money in positive). */
    public record Item(String sourceKey, String sourceType, LocalDate date, BigDecimal amount, String reference, String narration, String contact) {}

    public record Suggestion(Item item, int score) {}

    public record StatementLine(Long id, LocalDate date, BigDecimal amount, String payee, String reference, String description, BigDecimal balance,
                                String status, List<Suggestion> suggestions, List<Item> matched, LocalDateTime reconciledAt, String reconciledBy) {}

    /** The statement lines of an account (unreconciled with suggestions, or reconciled with what they matched). */
    @Transactional
    public List<StatementLine> lines(String accountCode, String status) {
        String code = bankCode(accountCode);
        ledger.sync(false);
        Long t = TenantContext.get();
        boolean open = !"RECONCILED".equals(status);
        List<Item> candidates = open ? unmatchedItems(code) : List.of();
        Map<Long, List<Item>> matched = new HashMap<>();
        if (!open) {
            Map<String, Item> all = new HashMap<>();
            items(code).forEach(i -> all.put(i.sourceKey(), i));
            jdbc.query("SELECT m.statement_line_id, m.source_key, m.amount FROM bank_matches m WHERE m.tenant_id = ? AND lower(m.account_code) = lower(?)", rs -> {
                Item i = all.get(rs.getString(2));
                matched.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(i != null ? i
                        : new Item(rs.getString(2), "", null, rs.getBigDecimal(3), null, "No longer in the ledger: unreconcile and match again", null));
            }, t, code);
        }
        return jdbc.query("""
                SELECT * FROM bank_statement_lines WHERE tenant_id = ? AND lower(account_code) = lower(?) AND status = ?
                 ORDER BY line_date""" + (open ? "" : " DESC") + ", id LIMIT 500", (rs, n) -> {
            LocalDate d = rs.getDate("line_date").toLocalDate();
            BigDecimal a = rs.getBigDecimal("amount");
            String payee = rs.getString("payee"), ref = rs.getString("reference");
            List<Suggestion> s = open ? suggest(d, a, payee, ref, candidates) : List.of();
            return new StatementLine(rs.getLong("id"), d, a, payee, ref, rs.getString("description"), rs.getBigDecimal("balance"), rs.getString("status"),
                    s, matched.getOrDefault(rs.getLong("id"), List.of()),
                    rs.getTimestamp("reconciled_at") == null ? null : rs.getTimestamp("reconciled_at").toLocalDateTime(), rs.getString("reconciled_by"));
        }, t, code, open ? "UNRECONCILED" : "RECONCILED");
    }

    /** Ledger items on the account not reconciled yet, for "Find & match". */
    @Transactional
    public List<Item> unmatched(String accountCode) {
        ledger.sync(false);
        return unmatchedItems(bankCode(accountCode));
    }

    // Same amount first, then nearest date, then payee / reference words in common; at most three
    private static List<Suggestion> suggest(LocalDate d, BigDecimal a, String payee, String ref, List<Item> candidates) {
        Set<String> words = words(payee + " " + ref);
        return candidates.stream().filter(i -> i.amount().compareTo(a) == 0 && Math.abs(ChronoUnit.DAYS.between(i.date(), d)) <= SUGGEST_DAYS)
                .map(i -> {
                    long days = Math.abs(ChronoUnit.DAYS.between(i.date(), d));
                    Set<String> w = words(i.contact() + " " + i.reference() + " " + i.narration());
                    w.retainAll(words);
                    return new Suggestion(i, (int) (100 - Math.min(days, 60) + 20 * w.size()));
                })
                .sorted(Comparator.comparingInt(Suggestion::score).reversed()).limit(3).toList();
    }

    private static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        for (String x : norm(s).split("[^a-z0-9]+")) if (x.length() >= 3 && !x.equals("null")) w.add(x);
        return w;
    }

    /** Xero's Match: one or several ledger items whose total is the statement line's amount. */
    @Transactional
    public StatementLine match(Long lineId, List<String> sourceKeys) {
        Object[] line = line(lineId);
        String code = (String) line[0];
        BigDecimal amount = (BigDecimal) line[1];
        if (!"UNRECONCILED".equals(line[3])) throw new IllegalStateException("This statement line is reconciled already");
        if (sourceKeys == null || sourceKeys.isEmpty()) throw new IllegalArgumentException("Choose what the statement line matches");
        ledger.sync(true);
        Map<String, Item> open = new HashMap<>();
        unmatchedItems(code).forEach(i -> open.put(i.sourceKey(), i));
        BigDecimal total = BigDecimal.ZERO;
        for (String k : new LinkedHashSet<>(sourceKeys)) {
            Item i = open.get(k);
            if (i == null) throw new IllegalArgumentException(k + " is not on this bank account or is reconciled already");
            total = total.add(i.amount());
        }
        if (total.compareTo(amount) != 0) {
            throw new IllegalArgumentException("The items total " + money(total).toPlainString() + ", the statement line " + money(amount).toPlainString()
                    + ": they must be equal");
        }
        Long t = TenantContext.get();
        jdbc.batchUpdate("INSERT INTO bank_matches (tenant_id, statement_line_id, account_code, source_key, amount) VALUES (?, ?, ?, ?, ?)",
                new LinkedHashSet<>(sourceKeys).stream().map(k -> new Object[]{t, lineId, code, k, open.get(k).amount()}).toList());
        jdbc.update("UPDATE bank_statement_lines SET status = 'RECONCILED', reconciled_at = now(), reconciled_by = ? WHERE id = ?", auditService.currentActorName(), lineId);
        auditService.log("BANK_STATEMENT_LINE", lineId, "RECONCILE", Map.of("account", code, "amount", amount, "matched", String.join(", ", sourceKeys)));
        return reconciled(code, lineId);
    }

    public record CreateLine(String accountCode, String description, BigDecimal amount, String taxRule) {}

    public record CreateRequest(String contact, String reference, List<CreateLine> lines) {}

    /** Xero's Create: a spend money (money out) or receive money (money in) transaction for the line, then matched. */
    @Transactional
    public StatementLine create(Long lineId, CreateRequest req) {
        Object[] line = line(lineId);
        if (!"UNRECONCILED".equals(line[3])) throw new IllegalStateException("This statement line is reconciled already");
        BigDecimal amount = (BigDecimal) line[1];
        Long id = saveTransaction(new TransactionRequest(amount.signum() < 0 ? "SPEND" : "RECEIVE", (LocalDate) line[2], (String) line[0], null,
                blank(req.contact()) == null ? (String) line[4] : req.contact(), blank(req.reference()) == null ? (String) line[5] : req.reference(),
                true, req.lines()), amount.abs());
        return match(lineId, List.of("BT:" + id));
    }

    /** Xero's Transfer: the money went to (or came from) another bank account. */
    @Transactional
    public StatementLine transfer(Long lineId, String otherAccount, String reference) {
        Object[] line = line(lineId);
        if (!"UNRECONCILED".equals(line[3])) throw new IllegalStateException("This statement line is reconciled already");
        BigDecimal amount = (BigDecimal) line[1];
        String code = (String) line[0], other = bankCode(otherAccount);
        if (other.equalsIgnoreCase(code)) throw new IllegalArgumentException("Choose another bank account");
        // Money out of this account goes to the other; money in came from it
        String from = amount.signum() < 0 ? code : other, to = amount.signum() < 0 ? other : code;
        Long id = saveTransaction(new TransactionRequest("TRANSFER", (LocalDate) line[2], from, to, null,
                blank(reference) == null ? (String) line[5] : reference, true, List.of()), amount.abs());
        return match(lineId, List.of("BT:" + id));
    }

    /** Xero's Unreconcile: the line goes back to be reconciled; what it matched stays in the ledger. */
    @Transactional
    public void unreconcile(Long lineId) {
        Object[] line = line(lineId);
        if (!"RECONCILED".equals(line[3])) throw new IllegalStateException("This statement line is not reconciled");
        jdbc.update("DELETE FROM bank_matches WHERE statement_line_id = ?", lineId);
        jdbc.update("UPDATE bank_statement_lines SET status = 'UNRECONCILED', reconciled_at = NULL, reconciled_by = NULL WHERE id = ?", lineId);
        auditService.log("BANK_STATEMENT_LINE", lineId, "UNRECONCILE", Map.of("account", line[0]));
    }

    /** Xero's Delete on a statement line not reconciled (e.g. imported twice from different files). */
    @Transactional
    public void deleteLine(Long lineId) {
        Object[] line = line(lineId);
        if (!"UNRECONCILED".equals(line[3])) throw new IllegalStateException("Unreconcile the line before deleting it");
        jdbc.update("DELETE FROM bank_statement_lines WHERE id = ?", lineId);
        auditService.log("BANK_STATEMENT_LINE", lineId, "DELETE", Map.of("account", line[0], "amount", line[1], "date", String.valueOf(line[2])));
    }

    // ---- spend money / receive money / transfers ----

    public record TransactionRequest(String kind, LocalDate date, String accountCode, String toAccountCode, String contact, String reference,
                                     boolean taxInclusive, List<CreateLine> lines) {}

    public record TransactionLine(String accountCode, String description, BigDecimal net, String taxRule, BigDecimal tax) {}

    public record Transaction(Long id, String kind, LocalDate date, String accountCode, String toAccountCode, String contact, String reference,
                              BigDecimal total, String status, boolean reconciled, List<TransactionLine> lines) {}

    @Transactional
    public Transaction createTransaction(TransactionRequest req) {
        Long id = saveTransaction(req, null);
        return transactions(null).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Transaction> transactions(String accountCode) {
        Long t = TenantContext.get();
        Map<Long, List<TransactionLine>> lines = new HashMap<>();
        jdbc.query("SELECT bank_transaction_id, account_code, description, net, tax_name, tax_amount FROM bank_transaction_lines WHERE tenant_id = ? ORDER BY id",
                rs -> { lines.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new TransactionLine(rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getBigDecimal(6))); }, t);
        Set<String> reconciled = new HashSet<>(jdbc.queryForList("SELECT source_key FROM bank_matches WHERE tenant_id = ? AND source_key LIKE 'BT:%'", String.class, t));
        return jdbc.query("""
                SELECT * FROM bank_transactions WHERE tenant_id = ? AND (?::varchar IS NULL OR lower(account_code) = lower(?::varchar) OR lower(to_account_code) = lower(?::varchar))
                 ORDER BY txn_date DESC, id DESC LIMIT 500""", (rs, n) -> new Transaction(rs.getLong("id"), rs.getString("kind"), rs.getDate("txn_date").toLocalDate(),
                rs.getString("account_code"), rs.getString("to_account_code"), rs.getString("contact"), rs.getString("reference"), rs.getBigDecimal("total"),
                rs.getString("status"), reconciled.contains("BT:" + rs.getLong("id")), lines.getOrDefault(rs.getLong("id"), List.of())),
                t, accountCode, accountCode, accountCode);
    }

    /** Void a spend / receive money or transfer (reversed in the ledger); not while it is reconciled. */
    @Transactional
    public void voidTransaction(Long id) {
        Long t = TenantContext.get();
        Object[] b = jdbc.query("SELECT txn_date, status FROM bank_transactions WHERE tenant_id = ? AND id = ?",
                rs -> rs.next() ? new Object[]{rs.getDate(1).toLocalDate(), rs.getString(2)} : null, t, id);
        if (b == null) throw new IllegalArgumentException("Bank transaction not found");
        if (!"ACTIVE".equals(b[1])) throw new IllegalStateException("Already voided");
        guard.requireNotReconciled("BT:" + id);
        periods.check((LocalDate) b[0], "a bank transaction");
        jdbc.update("UPDATE bank_transactions SET status = 'VOIDED', voided_at = now(), voided_by = ? WHERE id = ?", auditService.currentActorName(), id);
        auditService.log("BANK_TRANSACTION", id, "VOID", Map.of());
        ledger.sync(true);
    }

    /** total: when given (a statement line), the lines must add up to it, VAT included. */
    private Long saveTransaction(TransactionRequest req, BigDecimal total) {
        String kind = req.kind() == null ? "" : req.kind().toUpperCase(Locale.ROOT);
        if (!Set.of("SPEND", "RECEIVE", "TRANSFER").contains(kind)) throw new IllegalArgumentException("Spend, receive or transfer");
        if (req.date() == null) throw new IllegalArgumentException("The date is required");
        if (req.date().isAfter(LocalDate.now())) throw new IllegalArgumentException("A bank transaction cannot be dated in the future");
        periods.check(req.date(), "a bank transaction");
        String bank = bankCode(req.accountCode());
        Long t = TenantContext.get();
        if (kind.equals("TRANSFER")) {
            String to = bankCode(req.toAccountCode());
            if (to.equalsIgnoreCase(bank)) throw new IllegalArgumentException("Transfer to another bank account");
            BigDecimal amount = total != null ? total : req.lines() == null || req.lines().isEmpty() ? null : req.lines().get(0).amount();
            if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Give the amount transferred");
            Long id = jdbc.queryForObject("""
                    INSERT INTO bank_transactions (tenant_id, kind, txn_date, account_code, to_account_code, reference, total, created_by)
                    VALUES (?, 'TRANSFER', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, t, Date.valueOf(req.date()), bank, to, cut(req.reference(), 255),
                    money(amount), auditService.currentActorName());
            auditService.log("BANK_TRANSACTION", id, "CREATE", Map.of("kind", kind, "from", bank, "to", to, "amount", money(amount)));
            ledger.sync(true);
            return id;
        }
        List<CreateLine> lines = req.lines() == null ? List.of() : req.lines().stream().filter(l -> l.accountCode() != null && !l.accountCode().isBlank()
                && l.amount() != null && l.amount().signum() != 0).toList();
        if (lines.isEmpty()) throw new IllegalArgumentException("Add at least one line with an account and an amount");
        Map<String, ChartOfAccountsService.Account> accounts = chart.byCode();
        TaxRate.Usage usage = kind.equals("SPEND") ? TaxRate.Usage.PURCHASE : TaxRate.Usage.SALE;
        List<Object[]> rows = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (CreateLine l : lines) {
            ChartOfAccountsService.Account a = accounts.get(l.accountCode().trim().toLowerCase(Locale.ROOT));
            if (a == null || a.archived()) throw new IllegalArgumentException("Account " + l.accountCode() + " is not in the chart of accounts");
            if (a.type() == ChartOfAccountsService.Type.BANK) throw new IllegalArgumentException("Use a transfer to move money between bank accounts");
            BigDecimal rate = BigDecimal.ZERO;
            String taxName = blank(l.taxRule());
            if (taxName != null) {
                TaxRate r = taxService.rate(usage, taxName);
                rate = r.getRate();
                taxName = r.getName();
            }
            BigDecimal amount = money(l.amount());
            BigDecimal net, tax;
            if (req.taxInclusive()) {
                net = amount.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
                tax = amount.subtract(net);
            } else {
                net = amount;
                tax = amount.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
            sum = sum.add(net).add(tax);
            rows.add(new Object[]{a.code(), cut(l.description(), 500), net, taxName, rate, tax});
        }
        if (sum.signum() <= 0) throw new IllegalArgumentException("The total must be more than nothing");
        if (total != null && sum.compareTo(money(total)) != 0) {
            throw new IllegalArgumentException("The lines total " + money(sum).toPlainString() + ", the statement line " + money(total).toPlainString());
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO bank_transactions (tenant_id, kind, txn_date, account_code, contact, reference, total, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, t, kind, Date.valueOf(req.date()), bank, cut(blank(req.contact()), 255),
                cut(blank(req.reference()), 255), money(sum), auditService.currentActorName());
        jdbc.batchUpdate("INSERT INTO bank_transaction_lines (tenant_id, bank_transaction_id, account_code, description, net, tax_name, tax_rate, tax_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                rows.stream().map(r -> new Object[]{t, id, r[0], r[1], r[2], r[3], r[4], r[5]}).toList());
        auditService.log("BANK_TRANSACTION", id, "CREATE", Map.of("kind", kind, "account", bank, "total", money(sum)));
        ledger.sync(true);
        return id;
    }

    // ---- shared ----

    /** Every source's net amount on the bank account in the ledger (reversed ones drop out). */
    private List<Item> items(String code) {
        Long t = TenantContext.get();
        return jdbc.query("""
                SELECT j.source_key, MAX(j.source_type), SUM(l.amount),
                       (ARRAY_AGG(j.journal_date ORDER BY j.number DESC) FILTER (WHERE NOT j.reversal))[1],
                       (ARRAY_AGG(j.reference ORDER BY j.number DESC) FILTER (WHERE NOT j.reversal))[1],
                       (ARRAY_AGG(j.narration ORDER BY j.number DESC) FILTER (WHERE NOT j.reversal))[1],
                       (ARRAY_AGG(l.contact ORDER BY j.number DESC) FILTER (WHERE NOT j.reversal))[1]
                  FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                 WHERE j.tenant_id = ? AND lower(l.account_code) = lower(?)
                 GROUP BY j.source_key HAVING SUM(l.amount) <> 0""", (rs, n) -> new Item(rs.getString(1), rs.getString(2),
                rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), money(rs.getBigDecimal(3)), rs.getString(5), rs.getString(6), rs.getString(7)), t, code);
    }

    private List<Item> unmatchedItems(String code) {
        Set<String> done = new HashSet<>(jdbc.queryForList("SELECT source_key FROM bank_matches WHERE tenant_id = ? AND lower(account_code) = lower(?)",
                String.class, TenantContext.get(), code));
        return items(code).stream().filter(i -> !done.contains(i.sourceKey()) && i.date() != null && !"OPENING".equals(i.sourceType()))
                .sorted(Comparator.comparing(Item::date).thenComparing(Item::sourceKey)).toList();
    }

    private StatementLine reconciled(String code, Long lineId) {
        return lines(code, "RECONCILED").stream().filter(l -> l.id().equals(lineId)).findFirst().orElseThrow();
    }

    // [account code, amount, date, status, payee, reference]
    private Object[] line(Long lineId) {
        Object[] l = jdbc.query("SELECT account_code, amount, line_date, status, payee, reference FROM bank_statement_lines WHERE tenant_id = ? AND id = ?",
                rs -> rs.next() ? new Object[]{rs.getString(1), rs.getBigDecimal(2), rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5), rs.getString(6)} : null,
                TenantContext.get(), lineId);
        if (l == null) throw new IllegalArgumentException("Statement line not found");
        return l;
    }

    private String bankCode(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Choose the bank account");
        chart.loadStandardChart();
        ChartOfAccountsService.Account a = chart.byCode().get(code.trim().toLowerCase(Locale.ROOT));
        if (a == null || a.type() != ChartOfAccountsService.Type.BANK) throw new IllegalArgumentException(code + " is not a bank account in the chart of accounts");
        return a.code();
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String blank(String s) {
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
