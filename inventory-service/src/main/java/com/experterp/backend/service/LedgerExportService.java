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
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phase K5: the accountant's exports, as Xero's reports export — CSV files (they open in Excel) of the trial balance,
 * profit and loss, balance sheet, account transactions and the general ledger, and a <b>year-end pack</b>: one zip
 * with everything an accountant asks for at the year end (statements with the year before, trial balance, every
 * journal line of the year, aged debtors and creditors, the VAT returns filed, the chart of accounts).
 */
@Service
@RequiredArgsConstructor
public class LedgerExportService {

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final FinancialReportService reports;
    private final ChartOfAccountsService chart;
    private final LedgerPeriodService periods;
    private final SalesLedgerService salesLedger;
    private final PurchaseLedgerService purchaseLedger;
    private final MtdVatService vat;
    private final AuditService auditService;

    @Transactional
    public byte[] trialBalanceCsv(LocalDate asAt) {
        LedgerService.TrialBalance tb = ledger.trialBalance(asAt);
        return csv(List.of("Code", "Account", "Type", "Debit", "Credit"), out -> {
            for (LedgerService.TrialBalanceRow r : tb.rows()) out.add(row(r.code(), r.name(), r.type(), r.debit(), r.credit()));
            out.add(row("", "Total", "", tb.debits(), tb.credits()));
        });
    }

    @Transactional
    public byte[] profitAndLossCsv(LocalDate from, LocalDate to, int compare, FinancialReportService.Step step) {
        FinancialReportService.ProfitAndLoss pl = reports.profitAndLoss(from, to, compare, step);
        return csv(header(pl.columns()), out -> {
            section(out, pl.tradingIncome());
            section(out, pl.costOfSales());
            total(out, pl.grossProfit());
            section(out, pl.otherIncome());
            section(out, pl.operatingExpenses());
            total(out, pl.netProfit());
        });
    }

    @Transactional
    public byte[] balanceSheetCsv(LocalDate asAt, int compare, FinancialReportService.Step step) {
        FinancialReportService.BalanceSheet bs = reports.balanceSheet(asAt, compare, step);
        return csv(header(bs.columns()), out -> {
            bs.assets().forEach(s -> section(out, s));
            total(out, bs.totalAssets());
            bs.liabilities().forEach(s -> section(out, s));
            total(out, bs.totalLiabilities());
            total(out, bs.netAssets());
            section(out, bs.equity());
        });
    }

    @Transactional
    public byte[] accountTransactionsCsv(String account, LocalDate from, LocalDate to) {
        LedgerService.AccountTransactions a = ledger.accountTransactions(account, from, to);
        return csv(List.of("Date", "Journal", "Source", "Reference", "Description", "Contact", "Debit", "Credit", "Balance"), out -> {
            out.add(row(from, "", "Opening balance", "", "", "", null, null, a.opening()));
            for (LedgerService.TransactionRow r : a.rows()) {
                out.add(row(r.date(), r.journalNumber(), r.sourceType(), r.reference(), r.description() != null ? r.description() : r.narration(),
                        r.contact(), r.debit(), r.credit(), r.balance()));
            }
            out.add(row(to, "", "Closing balance", "", "", "", a.debits(), a.credits(), a.closing()));
        });
    }

    /** Every journal line in a period with its account: what an accountant loads into their own software. */
    @Transactional
    public byte[] generalLedgerCsv(LocalDate from, LocalDate to) {
        ledger.sync(false);
        Map<String, ChartOfAccountsService.Account> accounts = chart.byCode();
        return csv(List.of("Date", "Journal", "Source", "Source key", "Reference", "Narration", "Account code", "Account", "Account type",
                "Description", "Contact", "Debit", "Credit", "VAT rate", "Reversal"), out -> jdbc.query("""
                SELECT j.journal_date, j.number, j.source_type, j.source_key, j.reference, j.narration, l.account_code, l.description, l.contact,
                       l.amount, l.tax_name, j.reversal
                  FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                 WHERE j.tenant_id = ? AND j.journal_date BETWEEN ? AND ? ORDER BY j.journal_date, j.number, l.id""", rs -> {
            BigDecimal v = rs.getBigDecimal(10);
            ChartOfAccountsService.Account a = accounts.get(rs.getString(7).toLowerCase(Locale.ROOT));
            out.add(row(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), a == null ? "" : a.name(), a == null ? "" : a.typeLabel(), rs.getString(8), rs.getString(9),
                    v.signum() > 0 ? v : null, v.signum() < 0 ? v.negate() : null, rs.getString(11), rs.getBoolean(12) ? "Yes" : ""));
        }, TenantContext.get(), Date.valueOf(from), Date.valueOf(to)));
    }

    public record Pack(byte[] zip, String filename) {}

    /** The year-end pack for the financial year ending on this day (default: the last year end passed). */
    @Transactional
    public Pack yearEndPack(LocalDate yearEnd) {
        LocalDate end = yearEnd != null ? yearEnd : periods.yearStart(LocalDate.now()).minusDays(1);
        LocalDate start = periods.yearStart(end);
        if (!start.plusYears(1).minusDays(1).equals(end)) {
            throw new IllegalArgumentException("Give the last day of a financial year (the year runs " + start + " to " + start.plusYears(1).minusDays(1) + ")");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("1 Profit and loss " + end.getYear() + ".csv", profitAndLossCsv(start, end, 1, FinancialReportService.Step.YEAR));
        files.put("2 Balance sheet " + end + ".csv", balanceSheetCsv(end, 1, FinancialReportService.Step.YEAR));
        files.put("3 Trial balance " + end + ".csv", trialBalanceCsv(end));
        files.put("4 General ledger " + start + " to " + end + ".csv", generalLedgerCsv(start, end));
        files.put("5 Aged debtors " + end + ".csv", agedDebtorsCsv(end));
        files.put("6 Aged creditors " + end + ".csv", agedCreditorsCsv(end));
        files.put("7 VAT returns filed.csv", vatReturnsCsv(start, end));
        files.put("8 Chart of accounts.csv", chartCsv());
        LedgerPeriodService.Periods p = periods.periods();
        files.put("README.txt", ("Year-end pack for the financial year " + start + " to " + end + ", made " + LocalDate.now() + ".\r\n"
                + "Amounts in GBP. Lock dates: all users except advisers " + (p.lockDate() == null ? "none" : p.lockDate())
                + ", all users " + (p.hardLockDate() == null ? "none" : p.hardLockDate()) + ".\r\n"
                + "Lock the year (Ledger settings) once the accounts are agreed, so nothing in it changes afterwards.\r\n").getBytes(StandardCharsets.UTF_8));
        auditService.log("LEDGER", null, "YEAR_END_PACK", Map.of("yearEnd", end.toString()));
        return new Pack(zip(files), "Year-end pack " + end + ".zip");
    }

    private byte[] agedDebtorsCsv(LocalDate asAt) {
        SalesLedgerService.AgedDebtors a = salesLedger.agedDebtors(asAt);
        return csv(List.of("Customer", "Current", "1-30 days", "31-60 days", "61-90 days", "Over 90 days", "Credit", "Total"), out -> {
            for (SalesLedgerService.AgedDebtor d : a.customers()) out.add(row(d.customerName(), d.current(), d.days1to30(), d.days31to60(), d.days61to90(), d.over90(), d.credit(), d.total()));
            SalesLedgerService.AgedDebtor t = a.totals();
            out.add(row("Total", t.current(), t.days1to30(), t.days31to60(), t.days61to90(), t.over90(), t.credit(), t.total()));
        });
    }

    private byte[] agedCreditorsCsv(LocalDate asAt) {
        PurchaseLedgerService.AgedCreditors a = purchaseLedger.agedCreditors(asAt);
        return csv(List.of("Supplier", "Current", "1-30 days", "31-60 days", "61-90 days", "Over 90 days", "Credit", "Total"), out -> {
            for (PurchaseLedgerService.AgedCreditor d : a.suppliers()) out.add(row(d.supplierName(), d.current(), d.days1to30(), d.days31to60(), d.days61to90(), d.over90(), d.credit(), d.total()));
            PurchaseLedgerService.AgedCreditor t = a.totals();
            out.add(row("Total", t.current(), t.days1to30(), t.days31to60(), t.days61to90(), t.over90(), t.credit(), t.total()));
        });
    }

    private byte[] vatReturnsCsv(LocalDate from, LocalDate to) {
        return csv(List.of("From", "To", "How", "Filed", "Form bundle", "Box 1", "Box 2", "Box 3", "Box 4", "Box 5", "Box 6", "Box 7", "Box 8", "Box 9"), out -> {
            for (MtdVatService.Filed f : vat.filed()) {
                if (f.to().isBefore(from) || f.from().isAfter(to)) continue;
                VatReturnService.Boxes b = f.boxes();
                out.add(row(f.from(), f.to(), f.how(), f.filedAt().toLocalDate(), f.formBundleNumber(), b.box1(), b.box2(), b.box3(), b.box4(), b.box5(),
                        b.box6(), b.box7(), b.box8(), b.box9()));
            }
        });
    }

    private byte[] chartCsv() {
        // Xero's chart import headings, so the chart can go back into Xero or another package
        return csv(List.of("*Code", "*Name", "*Type", "*Tax Code", "Description"), out -> {
            for (ChartOfAccountsService.Account a : chart.list(true)) out.add(row(a.code(), a.name(), a.typeLabel(), a.taxType(), a.description()));
        });
    }

    // ---- shared ----

    private static List<String> header(List<FinancialReportService.Column> cols) {
        List<String> h = new ArrayList<>(List.of("Account code", "Account"));
        cols.forEach(c -> h.add(c.label()));
        return h;
    }

    private static void section(List<List<Object>> out, FinancialReportService.Section s) {
        if (s.rows().isEmpty()) return;
        out.add(row("", s.title()));
        for (FinancialReportService.Row r : s.rows()) {
            List<Object> l = new ArrayList<>(List.of(r.code() == null ? "" : r.code(), r.name()));
            l.addAll(r.values());
            out.add(l);
        }
        List<Object> t = new ArrayList<>(List.of("", "Total " + s.title()));
        t.addAll(s.totals());
        out.add(t);
    }

    private static void total(List<List<Object>> out, FinancialReportService.Total t) {
        List<Object> l = new ArrayList<>(List.of("", t.title()));
        l.addAll(t.values());
        out.add(l);
    }

    private static List<Object> row(Object... values) {
        List<Object> l = new ArrayList<>();
        for (Object v : values) l.add(v == null ? "" : v instanceof BigDecimal b ? b.toPlainString() : v);
        return l;
    }

    private static byte[] csv(List<String> header, java.util.function.Consumer<List<List<Object>>> fill) {
        List<List<Object>> rows = new ArrayList<>();
        fill.accept(rows);
        StringWriter w = new StringWriter();
        try (CSVPrinter p = new CSVPrinter(w, CSVFormat.DEFAULT.builder().setHeader(header.toArray(String[]::new)).setRecordSeparator("\r\n").build())) {
            for (List<Object> r : rows) p.printRecord(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // A byte order mark so Excel reads £ and accents right
        return ("﻿" + w).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
