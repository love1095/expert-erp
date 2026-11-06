package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Phase K1: the chart of accounts, like Xero's (Accounting → Chart of accounts): code, name, Xero's account types,
 * default VAT rate, archive. A new organisation starts with Xero's standard UK chart plus the accounts the ERP posts
 * to (inventory control, customer credits, supplier deposits, inventory discrepancy); an organisation coming from Xero
 * imports its own chart from Xero's export file (Chart of accounts → Export).
 */
@Service
@RequiredArgsConstructor
public class ChartOfAccountsService {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    /** Xero's account types, with the class each belongs to and the name Xero shows. */
    public enum Type {
        BANK("ASSET", "Bank"), CURRENT("ASSET", "Current Asset"), FIXED("ASSET", "Fixed Asset"), INVENTORY("ASSET", "Inventory"),
        NONCURRENT("ASSET", "Non-current Asset"), PREPAYMENT("ASSET", "Prepayment"),
        CURRLIAB("LIABILITY", "Current Liability"), LIABILITY("LIABILITY", "Liability"), TERMLIAB("LIABILITY", "Non-current Liability"),
        EQUITY("EQUITY", "Equity"),
        REVENUE("REVENUE", "Revenue"), SALES("REVENUE", "Sales"), OTHERINCOME("REVENUE", "Other Income"),
        DIRECTCOSTS("EXPENSE", "Direct Costs"), EXPENSE("EXPENSE", "Expense"), OVERHEADS("EXPENSE", "Overhead"), DEPRECIATN("EXPENSE", "Depreciation");

        public final String accountClass;
        public final String label;

        Type(String accountClass, String label) {
            this.accountClass = accountClass;
            this.label = label;
        }

        /** From Xero's export (e.g. "Current Asset", "Overhead") or our own names. */
        public static Type parse(String s) {
            if (s == null) return null;
            String t = s.trim().replace("-", " ").toLowerCase(Locale.ROOT);
            for (Type x : values()) {
                if (x.name().equalsIgnoreCase(t.replace(" ", "")) || x.label.replace("-", " ").equalsIgnoreCase(t)) return x;
            }
            return switch (t) {
                case "overheads" -> OVERHEADS;
                case "current liabilities", "current liab" -> CURRLIAB;
                case "term liability", "non current liabilities" -> TERMLIAB;
                case "fixed assets" -> FIXED;
                case "current assets" -> CURRENT;
                case "direct cost" -> DIRECTCOSTS;
                case "expenses" -> EXPENSE;
                default -> null;
            };
        }
    }

    public record Account(Long id, String code, String name, Type type, String accountClass, String typeLabel, String taxType,
                          String description, boolean archived, boolean used) {}

    public record AccountRequest(String code, String name, Type type, String taxType, String description, Boolean archived) {}

    public record ImportResult(int added, int updated, int unchanged, List<String> warnings) {}

    @Transactional(readOnly = true)
    public List<Account> list(boolean includeArchived) {
        Long t = TenantContext.get();
        Set<String> used = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT lower(account_code) FROM journal_lines WHERE tenant_id = ?", String.class, t));
        return jdbc.query("SELECT * FROM ledger_accounts WHERE tenant_id = ? ORDER BY code", (rs, n) -> {
            Type type = Type.valueOf(rs.getString("type"));
            return new Account(rs.getLong("id"), rs.getString("code"), rs.getString("name"), type, type.accountClass, type.label,
                    rs.getString("tax_type"), rs.getString("description"), rs.getBoolean("archived"),
                    used.contains(rs.getString("code").toLowerCase(Locale.ROOT)));
        }, t).stream().filter(a -> includeArchived || !a.archived()).sorted(ORDER).toList();
    }

    // Xero lists by class (assets, liabilities, equity, revenue, expenses), then code
    private static final List<String> CLASSES = List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");
    static final Comparator<Account> ORDER = Comparator.comparing((Account a) -> CLASSES.indexOf(a.accountClass()))
            .thenComparing(Account::code);

    /** Code → account, for the ledger. */
    @Transactional(readOnly = true)
    public Map<String, Account> byCode() {
        Map<String, Account> m = new HashMap<>();
        list(true).forEach(a -> m.put(a.code().toLowerCase(Locale.ROOT), a));
        return m;
    }

    @Transactional
    public Account save(Long id, AccountRequest req) {
        String code = req.code() == null ? "" : req.code().trim();
        String name = req.name() == null ? "" : req.name().trim();
        if (code.isEmpty() || code.length() > 10) throw new IllegalArgumentException("The code is required (at most 10 characters)");
        if (name.isEmpty() || name.length() > 150) throw new IllegalArgumentException("The name is required (at most 150 characters)");
        if (req.type() == null) throw new IllegalArgumentException("Choose the account type");
        Long t = TenantContext.get();
        Long other = jdbc.query("SELECT id FROM ledger_accounts WHERE tenant_id = ? AND lower(code) = lower(?)",
                rs -> rs.next() ? rs.getLong(1) : null, t, code);
        if (other != null && !other.equals(id)) throw new IllegalArgumentException("Account code " + code + " is used already");
        if (id == null) {
            Long newId = jdbc.queryForObject("""
                    INSERT INTO ledger_accounts (tenant_id, code, name, type, tax_type, description) VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    Long.class, t, code, name, req.type().name(), blank(req.taxType()), blank(req.description()));
            auditService.log("LEDGER_ACCOUNT", newId, "CREATE", Map.of("code", code, "name", name, "type", req.type()));
            id = newId;
        } else {
            Map<String, Object> before = jdbc.queryForMap("SELECT code, type FROM ledger_accounts WHERE tenant_id = ? AND id = ?", t, id);
            boolean used = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_lines WHERE tenant_id = ? AND lower(account_code) = lower(?))",
                    Boolean.class, t, before.get("code")));
            // As Xero: an account with transactions keeps its code (its journals name it); its type can change within reason
            if (used && !code.equalsIgnoreCase((String) before.get("code"))) {
                throw new IllegalArgumentException("Account " + before.get("code") + " has transactions: its code cannot change");
            }
            jdbc.update("UPDATE ledger_accounts SET code = ?, name = ?, type = ?, tax_type = ?, description = ?, archived = ? WHERE tenant_id = ? AND id = ?",
                    code, name, req.type().name(), blank(req.taxType()), blank(req.description()), Boolean.TRUE.equals(req.archived()), t, id);
            auditService.log("LEDGER_ACCOUNT", id, "UPDATE", Map.of("code", code, "name", name, "type", req.type(),
                    "archived", Boolean.TRUE.equals(req.archived())));
        }
        Long finalId = id;
        return list(true).stream().filter(a -> a.id().equals(finalId)).findFirst().orElseThrow();
    }

    /** Xero: an account with no transactions can be deleted; one with transactions is archived instead. */
    @Transactional
    public void delete(Long id) {
        Long t = TenantContext.get();
        String code = jdbc.query("SELECT code FROM ledger_accounts WHERE tenant_id = ? AND id = ?", rs -> rs.next() ? rs.getString(1) : null, t, id);
        if (code == null) throw new IllegalArgumentException("Account not found");
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_lines WHERE tenant_id = ? AND lower(account_code) = lower(?)) "
                + "OR EXISTS (SELECT 1 FROM manual_journal_lines WHERE tenant_id = ? AND lower(account_code) = lower(?))", Boolean.class, t, code, t, code);
        if (Boolean.TRUE.equals(used)) throw new IllegalStateException("Account " + code + " has transactions: archive it instead");
        jdbc.update("DELETE FROM ledger_accounts WHERE tenant_id = ? AND id = ?", t, id);
        auditService.log("LEDGER_ACCOUNT", id, "DELETE", Map.of("code", code));
    }

    /** Xero's standard UK chart, when the organisation has no accounts yet. Returns how many were added. */
    @Transactional
    public int loadStandardChart() {
        Long t = TenantContext.get();
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_accounts WHERE tenant_id = ?", Integer.class, t);
        if (n != null && n > 0) return 0;
        List<Object[]> rows = STANDARD.stream().map(a -> new Object[]{t, a[0], a[1], a[2], a.length > 3 ? a[3] : null}).toList();
        jdbc.batchUpdate("INSERT INTO ledger_accounts (tenant_id, code, name, type, tax_type) VALUES (?, ?, ?, ?, ?)", rows);
        // The organisation's own payment accounts (phase H1) are its bank accounts
        jdbc.update("""
                INSERT INTO ledger_accounts (tenant_id, code, name, type)
                SELECT pa.tenant_id, pa.code, pa.name, 'BANK' FROM payment_accounts pa
                 WHERE pa.tenant_id = ? AND NOT EXISTS (SELECT 1 FROM ledger_accounts a WHERE a.tenant_id = pa.tenant_id AND lower(a.code) = lower(pa.code))""", t);
        auditService.log("LEDGER_ACCOUNT", null, "LOAD_STANDARD_CHART", Map.of("accounts", rows.size()));
        return rows.size();
    }

    /** Adds an account the ledger needs and the chart does not have (a payment account as a bank, else a current asset). */
    @Transactional
    public void ensure(Collection<String> codes) {
        Long t = TenantContext.get();
        Map<String, Account> have = byCode();
        for (String code : codes) {
            if (code == null || have.containsKey(code.toLowerCase(Locale.ROOT))) continue;
            List<String> bank = jdbc.queryForList("SELECT name FROM payment_accounts WHERE tenant_id = ? AND lower(code) = lower(?)", String.class, t, code);
            jdbc.update("INSERT INTO ledger_accounts (tenant_id, code, name, type, description) VALUES (?, ?, ?, ?, ?)", t, code,
                    bank.isEmpty() ? "Account " + code : bank.get(0), bank.isEmpty() ? Type.CURRENT.name() : Type.BANK.name(),
                    bank.isEmpty() ? "Added by the ledger: a document posts to this code. Rename it and set its type." : null);
            have.put(code.toLowerCase(Locale.ROOT), null);
        }
    }

    /**
     * Xero's chart export (*Code, *Name, *Type, *Tax Code, Description, ...): accounts added, or updated by code.
     * A preview (apply = false) writes nothing.
     */
    @Transactional
    public ImportResult importXeroCsv(InputStream in, boolean apply) throws IOException {
        Long t = TenantContext.get();
        List<String> warnings = new ArrayList<>();
        Map<String, Account> have = byCode();
        int added = 0, updated = 0, unchanged = 0;
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setAllowMissingColumnNames(true).build();
        Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '﻿') reader.reset();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            CsvColumns c = new CsvColumns(parser.getHeaderNames().stream().map(h -> h.trim().replace("*", "")).toList());
            if (!c.has("Code") || !c.has("Name") || !c.has("Type")) {
                throw new IllegalArgumentException("This is not a Xero chart of accounts file: it needs the columns Code, Name and Type (Xero: Chart of accounts → Export)");
            }
            for (CSVRecord r : parser) {
                String code = c.get(r, "Code"), name = c.get(r, "Name");
                Type type = Type.parse(c.get(r, "Type"));
                if (code == null || name == null) continue;
                if (type == null) { warnings.add(code + " " + name + ": type '" + c.get(r, "Type") + "' not known, skipped"); continue; }
                String tax = c.get(r, "Tax Code"), description = c.get(r, "Description");
                Account a = have.get(code.toLowerCase(Locale.ROOT));
                if (a == null) {
                    added++;
                    if (apply) jdbc.update("INSERT INTO ledger_accounts (tenant_id, code, name, type, tax_type, description) VALUES (?, ?, ?, ?, ?, ?)",
                            t, code, cut(name, 150), type.name(), cut(tax, 100), cut(description, 500));
                    have.put(code.toLowerCase(Locale.ROOT), null);
                } else if (a.name().equals(name) && a.type() == type) {
                    unchanged++;
                } else {
                    updated++;
                    if (apply) jdbc.update("UPDATE ledger_accounts SET name = ?, type = ?, tax_type = COALESCE(?, tax_type), description = COALESCE(?, description) WHERE id = ?",
                            cut(name, 150), type.name(), cut(tax, 100), cut(description, 500), a.id());
                }
            }
        }
        if (apply) auditService.log("LEDGER_ACCOUNT", null, "IMPORT", Map.of("added", added, "updated", updated));
        return new ImportResult(added, updated, unchanged, warnings);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    /** Xero's standard UK chart (code, name, type, VAT), plus the accounts the ERP posts to (631, 615, 802, 495). */
    static final List<String[]> STANDARD = List.of(
            new String[]{"090", "Business Bank Account", "BANK"},
            new String[]{"200", "Sales", "REVENUE", "20% (VAT on Income)"},
            new String[]{"260", "Other Revenue", "REVENUE", "20% (VAT on Income)"},
            new String[]{"270", "Interest Income", "REVENUE", "No VAT"},
            new String[]{"310", "Cost of Goods Sold", "DIRECTCOSTS", "20% (VAT on Expenses)"},
            new String[]{"312", "Inventory Damages and Losses", "DIRECTCOSTS", "No VAT"},
            new String[]{"320", "Direct Wages", "DIRECTCOSTS", "No VAT"},
            new String[]{"325", "Direct Expenses", "DIRECTCOSTS", "20% (VAT on Expenses)"},
            new String[]{"400", "Advertising & Marketing", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"401", "Audit & Accountancy fees", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"404", "Bank Fees", "OVERHEADS", "No VAT"},
            new String[]{"408", "Cleaning", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"412", "Consulting", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"416", "Depreciation Expense", "DEPRECIATN", "No VAT"},
            new String[]{"418", "Charitable and Political Donations", "OVERHEADS", "No VAT"},
            new String[]{"420", "Entertainment-100% business", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"424", "Entertainment - 0%", "OVERHEADS", "No VAT"},
            new String[]{"425", "Postage, Freight & Courier", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"429", "General Expenses", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"433", "Insurance", "OVERHEADS", "Exempt Expenses"},
            new String[]{"437", "Interest Paid", "OVERHEADS", "No VAT"},
            new String[]{"441", "Legal Expenses", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"445", "Light, Power, Heating", "OVERHEADS", "5% (VAT on Expenses)"},
            new String[]{"449", "Motor Vehicle Expenses", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"457", "Operating Lease Payments", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"461", "Printing & Stationery", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"463", "IT Software and Consumables", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"465", "Rates", "OVERHEADS", "No VAT"},
            new String[]{"469", "Rent", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"473", "Repairs & Maintenance", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"477", "Salaries", "OVERHEADS", "No VAT"},
            new String[]{"478", "Directors' Remuneration", "OVERHEADS", "No VAT"},
            new String[]{"479", "Employers National Insurance", "OVERHEADS", "No VAT"},
            new String[]{"480", "Staff Training", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"482", "Pensions Costs", "OVERHEADS", "No VAT"},
            new String[]{"483", "Medical Insurance", "OVERHEADS", "Exempt Expenses"},
            new String[]{"485", "Subscriptions", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"489", "Telephone & Internet", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"493", "Travel - National", "OVERHEADS", "20% (VAT on Expenses)"},
            new String[]{"494", "Travel - International", "OVERHEADS", "No VAT"},
            new String[]{"495", "Inventory Discrepancy", "DIRECTCOSTS", "No VAT"},
            new String[]{"497", "Bank Revaluations", "OVERHEADS", "No VAT"},
            new String[]{"498", "Unrealised Currency Gains", "OVERHEADS", "No VAT"},
            new String[]{"499", "Realised Currency Gains", "OVERHEADS", "No VAT"},
            new String[]{"500", "Corporation Tax", "OVERHEADS", "No VAT"},
            new String[]{"610", "Accounts Receivable", "CURRENT"},
            new String[]{"611", "Less Provision for Doubtful Debts", "CURRENT"},
            new String[]{"615", "Supplier Deposits", "CURRENT"},
            new String[]{"620", "Prepayments", "CURRENT"},
            new String[]{"631", "Inventory Control", "INVENTORY"},
            new String[]{"710", "Office Equipment", "FIXED", "20% (VAT on Expenses)"},
            new String[]{"711", "Less Accumulated Depreciation on Office Equipment", "FIXED"},
            new String[]{"720", "Computer Equipment", "FIXED", "20% (VAT on Expenses)"},
            new String[]{"721", "Less Accumulated Depreciation on Computer Equipment", "FIXED"},
            new String[]{"740", "Buildings", "FIXED"},
            new String[]{"741", "Less Accumulated Depreciation on Buildings", "FIXED"},
            new String[]{"750", "Leasehold Improvements", "FIXED"},
            new String[]{"751", "Less Accumulated Depreciation on Leasehold Improvements", "FIXED"},
            new String[]{"760", "Motor Vehicles", "FIXED", "20% (VAT on Expenses)"},
            new String[]{"761", "Less Accumulated Depreciation on Motor Vehicles", "FIXED"},
            new String[]{"764", "Fixtures and Fittings", "FIXED", "20% (VAT on Expenses)"},
            new String[]{"765", "Less Accumulated Depreciation on Fixtures and Fittings", "FIXED"},
            new String[]{"770", "Intangibles", "FIXED"},
            new String[]{"771", "Less Accumulated Amortisation on Intangibles", "FIXED"},
            new String[]{"800", "Accounts Payable", "CURRLIAB"},
            new String[]{"801", "Unpaid Expense Claims", "CURRLIAB"},
            new String[]{"802", "Customer Credits", "CURRLIAB"},
            new String[]{"805", "Accruals", "CURRLIAB"},
            new String[]{"810", "Income in Advance", "CURRLIAB"},
            new String[]{"811", "Credit Card Control Account", "CURRLIAB"},
            new String[]{"814", "Wages Payable - Payroll", "CURRLIAB"},
            new String[]{"820", "VAT", "CURRLIAB"},
            new String[]{"825", "PAYE Payable", "CURRLIAB"},
            new String[]{"826", "NIC Payable", "CURRLIAB"},
            new String[]{"830", "Provision for Corporation Tax", "CURRLIAB"},
            new String[]{"835", "Directors' Loan Account", "CURRLIAB"},
            new String[]{"840", "Historical Adjustment", "CURRLIAB"},
            new String[]{"850", "Suspense", "CURRLIAB"},
            new String[]{"855", "Clearing Account", "CURRLIAB"},
            new String[]{"860", "Rounding", "CURRLIAB"},
            new String[]{"868", "Earnings Orders Payable", "CURRLIAB"},
            new String[]{"877", "Tracking Transfers", "CURRLIAB"},
            new String[]{"900", "Loan", "TERMLIAB"},
            new String[]{"910", "Hire Purchase Loan", "TERMLIAB"},
            new String[]{"920", "Deferred Tax", "TERMLIAB"},
            new String[]{"947", "Student Loan Deductions Payable", "CURRLIAB"},
            new String[]{"950", "Capital - x,000 Ordinary Shares", "EQUITY"},
            new String[]{"960", "Retained Earnings", "EQUITY"},
            new String[]{"970", "Owner A Funds Introduced", "EQUITY"},
            new String[]{"980", "Owner A Drawings", "EQUITY"});
}
