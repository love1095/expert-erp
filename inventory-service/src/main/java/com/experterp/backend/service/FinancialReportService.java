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
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase K2: Xero's Profit and Loss and Balance Sheet from the general ledger, with comparison columns (earlier
 * months, quarters or years). As Xero, there is no closing journal at the year end: the balance sheet's
 * <b>Current Year Earnings</b> is the profit since the start of the financial year, and <b>Retained Earnings</b> is
 * the profit of the years before plus anything posted to the retained earnings account.
 */
@Service
@RequiredArgsConstructor
public class FinancialReportService {

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Set<String> PL_TYPES = Set.of("REVENUE", "SALES", "OTHERINCOME", "DIRECTCOSTS", "EXPENSE", "OVERHEADS", "DEPRECIATN");

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final ChartOfAccountsService chart;
    private final LedgerPeriodService periods;

    public enum Step { MONTH, QUARTER, YEAR }

    public record Column(String label, LocalDate from, LocalDate to) {}

    public record Row(String code, String name, List<BigDecimal> values) {}

    public record Section(String title, List<Row> rows, List<BigDecimal> totals) {}

    /** A total line between sections (Gross Profit, Net Profit, Net Assets…). */
    public record Total(String title, List<BigDecimal> values) {}

    public record ProfitAndLoss(List<Column> columns, Section tradingIncome, Section costOfSales, Total grossProfit,
                                Section otherIncome, Section operatingExpenses, Total netProfit) {}

    public record BalanceSheet(List<Column> columns, List<Section> assets, Total totalAssets, List<Section> liabilities,
                               Total totalLiabilities, Total netAssets, Section equity, List<String> warnings) {}

    // ---- Profit and Loss ----

    /** Revenue shown positive, costs positive; comparison columns go back from the period by the step. */
    @Transactional
    public ProfitAndLoss profitAndLoss(LocalDate from, LocalDate to, int compare, Step step) {
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Give the period (from – to)");
        ledger.sync(false);
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i <= Math.max(0, Math.min(compare, 11)); i++) {
            LocalDate f = back(from, step, i), t = i == 0 ? to : back(to, step, i);
            // A whole month stays a whole month (31 Jan → 28 Feb)
            if (i > 0 && to.equals(to.withDayOfMonth(to.lengthOfMonth()))) t = t.withDayOfMonth(t.lengthOfMonth());
            cols.add(new Column(f.format(LABEL) + " – " + t.format(LABEL), f, t));
        }
        List<Map<String, BigDecimal>> sums = cols.stream().map(c -> movement(c.from(), c.to())).toList();
        Map<String, ChartOfAccountsService.Account> accounts = chart.byCode();
        Section income = section("Trading Income", Set.of("REVENUE", "SALES"), sums, accounts, -1);
        Section cost = section("Cost of Sales", Set.of("DIRECTCOSTS"), sums, accounts, 1);
        Section other = section("Other Income", Set.of("OTHERINCOME"), sums, accounts, -1);
        Section expenses = section("Operating Expenses", Set.of("EXPENSE", "OVERHEADS", "DEPRECIATN"), sums, accounts, 1);
        Total gross = new Total("Gross Profit", combine(income.totals(), cost.totals(), -1));
        Total net = new Total("Net Profit", combine(combine(gross.values(), other.totals(), 1), expenses.totals(), -1));
        return new ProfitAndLoss(cols, income, cost, gross, other, expenses, net);
    }

    // ---- Balance Sheet ----

    /** Assets shown positive, liabilities and equity positive; comparison columns at earlier dates. */
    @Transactional
    public BalanceSheet balanceSheet(LocalDate asAt, int compare, Step step) {
        LocalDate day = asAt == null ? LocalDate.now() : asAt;
        ledger.sync(false);
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i <= Math.max(0, Math.min(compare, 11)); i++) {
            LocalDate d = back(day, step, i);
            if (i > 0 && day.equals(day.withDayOfMonth(day.lengthOfMonth()))) d = d.withDayOfMonth(d.lengthOfMonth());
            cols.add(new Column(d.format(LABEL), null, d));
        }
        Map<String, ChartOfAccountsService.Account> accounts = chart.byCode();
        LedgerService.Settings s = ledger.settings();
        List<Map<String, BigDecimal>> balances = new ArrayList<>();
        List<BigDecimal> currentYear = new ArrayList<>(), priorYears = new ArrayList<>();
        for (Column c : cols) {
            LocalDate yearStart = periods.yearStart(c.to());
            Map<String, BigDecimal> b = movement(null, c.to());
            // Profit and loss accounts leave the balance sheet: this year's profit and the years before's, in equity
            BigDecimal cy = BigDecimal.ZERO, py = BigDecimal.ZERO;
            Map<String, BigDecimal> beforeYear = movement(null, yearStart.minusDays(1));
            for (Map.Entry<String, BigDecimal> e : b.entrySet()) {
                if (!isPl(e.getKey(), accounts)) continue;
                BigDecimal before = beforeYear.getOrDefault(e.getKey(), BigDecimal.ZERO);
                py = py.add(before);
                cy = cy.add(e.getValue().subtract(before));
            }
            b.keySet().removeIf(k -> isPl(k, accounts));
            balances.add(b);
            currentYear.add(money(cy.negate()));
            priorYears.add(money(py.negate()));
        }
        List<Section> assets = List.of(
                section("Bank", Set.of("BANK"), balances, accounts, 1),
                section("Current Assets", Set.of("CURRENT", "INVENTORY", "PREPAYMENT"), balances, accounts, 1),
                section("Fixed Assets", Set.of("FIXED"), balances, accounts, 1),
                section("Non-current Assets", Set.of("NONCURRENT"), balances, accounts, 1));
        List<Section> liabilities = List.of(
                section("Current Liabilities", Set.of("CURRLIAB"), balances, accounts, -1),
                section("Non-current Liabilities", Set.of("TERMLIAB", "LIABILITY"), balances, accounts, -1));
        Total totalAssets = new Total("Total Assets", sumTotals(assets, cols.size()));
        Total totalLiabilities = new Total("Total Liabilities", sumTotals(liabilities, cols.size()));
        Total netAssets = new Total("Net Assets", combine(totalAssets.values(), totalLiabilities.values(), -1));

        // Equity: Current Year Earnings, Retained Earnings (the account plus the years before), the other equity accounts
        Section equityAccounts = section("Equity", Set.of("EQUITY"), balances, accounts, -1);
        String re = s.retainedEarnings().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(null, "Current Year Earnings", currentYear));
        Row reAccount = equityAccounts.rows().stream().filter(r -> r.code().equalsIgnoreCase(re)).findFirst().orElse(null);
        rows.add(new Row(s.retainedEarnings(), "Retained Earnings", reAccount == null ? priorYears : combine(priorYears, reAccount.values(), 1)));
        equityAccounts.rows().stream().filter(r -> !r.code().equalsIgnoreCase(re)).forEach(rows::add);
        List<BigDecimal> equityTotals = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            int k = i;
            equityTotals.add(rows.stream().map(r -> r.values().get(k)).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP));
        }
        Section equity = new Section("Equity", rows, equityTotals);
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            if (equityTotals.get(i).compareTo(netAssets.values().get(i)) != 0) {
                warnings.add(cols.get(i).label() + ": equity differs from net assets by " + netAssets.values().get(i).subtract(equityTotals.get(i)).toPlainString());
            }
        }
        return new BalanceSheet(cols, assets, totalAssets, liabilities, totalLiabilities, netAssets, equity, warnings);
    }

    // ---- shared ----

    /** Each account's net movement (debit positive) between two days; from = null means since the beginning. */
    private Map<String, BigDecimal> movement(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> m = new HashMap<>();
        jdbc.query("""
                SELECT lower(l.account_code), SUM(l.amount) FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                 WHERE j.tenant_id = ? AND (?::date IS NULL OR j.journal_date >= ?::date) AND j.journal_date <= ?
                 GROUP BY lower(l.account_code)""", rs -> { m.put(rs.getString(1), rs.getBigDecimal(2)); },
                TenantContext.get(), from == null ? null : Date.valueOf(from), from == null ? null : Date.valueOf(from), Date.valueOf(to));
        return m;
    }

    private static boolean isPl(String code, Map<String, ChartOfAccountsService.Account> accounts) {
        ChartOfAccountsService.Account a = accounts.get(code);
        return a != null && PL_TYPES.contains(a.type().name());
    }

    /** The accounts of these types, one value per column, signed so that the section's usual side is positive. */
    private static Section section(String title, Set<String> types, List<Map<String, BigDecimal>> columns,
                                   Map<String, ChartOfAccountsService.Account> accounts, int sign) {
        Set<String> codes = new TreeSet<>();
        columns.forEach(c -> c.forEach((code, v) -> {
            ChartOfAccountsService.Account a = accounts.get(code);
            // An account the chart does not know is shown with the current assets, so the balance sheet still balances
            String type = a == null ? "CURRENT" : a.type().name();
            if (types.contains(type) && v.signum() != 0) codes.add(code);
        }));
        List<Row> rows = new ArrayList<>();
        for (String code : codes) {
            ChartOfAccountsService.Account a = accounts.get(code);
            List<BigDecimal> values = columns.stream().map(c -> money(c.getOrDefault(code, BigDecimal.ZERO).multiply(BigDecimal.valueOf(sign)))).toList();
            rows.add(new Row(a == null ? code : a.code(), a == null ? "Unknown account " + code : a.name(), values));
        }
        rows.sort(Comparator.comparing(Row::code));
        List<BigDecimal> totals = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            int k = i;
            totals.add(rows.stream().map(r -> r.values().get(k)).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP));
        }
        return new Section(title, rows, totals);
    }

    private static List<BigDecimal> combine(List<BigDecimal> a, List<BigDecimal> b, int sign) {
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) out.add(a.get(i).add(b.get(i).multiply(BigDecimal.valueOf(sign))).setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    private static List<BigDecimal> sumTotals(List<Section> sections, int n) {
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            out.add(sections.stream().map(s -> s.totals().get(k)).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP));
        }
        return out;
    }

    private static LocalDate back(LocalDate d, Step step, int n) {
        if (n == 0) return d;
        return switch (step == null ? Step.MONTH : step) {
            case MONTH -> d.minusMonths(n);
            case QUARTER -> d.minusMonths(3L * n);
            case YEAR -> d.minusYears(n);
        };
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
