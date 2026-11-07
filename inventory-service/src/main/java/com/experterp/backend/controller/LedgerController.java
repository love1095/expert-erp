package com.experterp.backend.controller;

import com.experterp.backend.service.ChartOfAccountsService;
import com.experterp.backend.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Phase K1: chart of accounts, general ledger, manual journals, conversion balances and ledger reports (like Xero). */
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_ACCOUNTING')")
public class LedgerController {

    private final ChartOfAccountsService chart;
    private final LedgerService ledger;
    private final com.experterp.backend.service.LedgerPeriodService periods;
    private final com.experterp.backend.service.FinancialReportService reports;
    private final com.experterp.backend.service.LedgerExportService exports;

    // ---- chart of accounts ----

    @GetMapping("/accounts")
    public List<ChartOfAccountsService.Account> accounts(@RequestParam(defaultValue = "false") boolean archived) {
        chart.loadStandardChart();
        return chart.list(archived);
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public ChartOfAccountsService.Account addAccount(@RequestBody ChartOfAccountsService.AccountRequest req) {
        return chart.save(null, req);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public ChartOfAccountsService.Account updateAccount(@PathVariable Long id, @RequestBody ChartOfAccountsService.AccountRequest req) {
        return chart.save(id, req);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public void deleteAccount(@PathVariable Long id) {
        chart.delete(id);
    }

    @PostMapping(value = "/accounts/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public ChartOfAccountsService.ImportResult importAccounts(@RequestParam("file") MultipartFile file,
                                                             @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        try (var in = file.getInputStream()) {
            return chart.importXeroCsv(in, apply);
        }
    }

    @GetMapping("/account-types")
    public List<Map<String, String>> types() {
        return java.util.Arrays.stream(ChartOfAccountsService.Type.values())
                .map(t -> Map.of("type", t.name(), "label", t.label, "accountClass", t.accountClass)).toList();
    }

    // ---- settings and conversion balances ----

    @GetMapping("/settings")
    public LedgerService.Settings settings() {
        return ledger.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public LedgerService.Settings updateSettings(@RequestBody LedgerService.Settings s) {
        return ledger.updateSettings(s);
    }

    @GetMapping("/opening-balances")
    public LedgerService.Opening opening() {
        return ledger.opening();
    }

    @PutMapping("/opening-balances")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public LedgerService.Opening saveOpening(@RequestBody List<LedgerService.OpeningBalance> balances) {
        return ledger.saveOpening(balances);
    }

    // ---- manual journals ----

    @GetMapping("/manual-journals")
    public List<LedgerService.ManualJournal> manualJournals(@RequestParam(required = false) String status) {
        return ledger.manualJournals(status);
    }

    @PostMapping("/manual-journals")
    public LedgerService.ManualJournal addManual(@RequestBody LedgerService.ManualJournalRequest req) {
        return ledger.saveManual(null, req);
    }

    @PutMapping("/manual-journals/{id}")
    public LedgerService.ManualJournal updateManual(@PathVariable Long id, @RequestBody LedgerService.ManualJournalRequest req) {
        return ledger.saveManual(id, req);
    }

    @PostMapping("/manual-journals/{id}/post")
    public void postManual(@PathVariable Long id) {
        ledger.post(id);
    }

    /** Void a posted journal (reversed), or delete a draft. */
    @PostMapping("/manual-journals/{id}/void")
    public void voidManual(@PathVariable Long id) {
        ledger.voidOrDelete(id);
    }

    // ---- financial year and lock dates (K2) ----

    @GetMapping("/periods")
    public com.experterp.backend.service.LedgerPeriodService.Periods periods() {
        return periods.periods();
    }

    @PutMapping("/periods")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public com.experterp.backend.service.LedgerPeriodService.Periods updatePeriods(@RequestBody com.experterp.backend.service.LedgerPeriodService.Periods p) {
        return periods.update(p);
    }

    // ---- reports ----

    @GetMapping("/profit-and-loss")
    public com.experterp.backend.service.FinancialReportService.ProfitAndLoss profitAndLoss(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int compare,
            @RequestParam(defaultValue = "MONTH") com.experterp.backend.service.FinancialReportService.Step step) {
        return reports.profitAndLoss(from, to, compare, step);
    }

    @GetMapping("/balance-sheet")
    public com.experterp.backend.service.FinancialReportService.BalanceSheet balanceSheet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int compare,
            @RequestParam(defaultValue = "MONTH") com.experterp.backend.service.FinancialReportService.Step step) {
        return reports.balanceSheet(date, compare, step);
    }

    @GetMapping("/trial-balance")
    public LedgerService.TrialBalance trialBalance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ledger.trialBalance(date);
    }

    @GetMapping("/account-transactions")
    public LedgerService.AccountTransactions accountTransactions(@RequestParam String account,
                                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ledger.accountTransactions(account, from, to);
    }

    @GetMapping("/journals")
    public List<LedgerService.JournalView> journals(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(required = false) String source) {
        return ledger.journalReport(from, to, source);
    }

    // ---- exports (K5): CSV that opens in Excel, and the accountant's year-end pack ----

    @GetMapping("/export/trial-balance.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportTrialBalance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return file(exports.trialBalanceCsv(date), "Trial balance " + (date == null ? LocalDate.now() : date) + ".csv", "text/csv");
    }

    @GetMapping("/export/profit-and-loss.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportProfitAndLoss(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int compare, @RequestParam(defaultValue = "MONTH") com.experterp.backend.service.FinancialReportService.Step step) {
        return file(exports.profitAndLossCsv(from, to, compare, step), "Profit and loss " + from + " to " + to + ".csv", "text/csv");
    }

    @GetMapping("/export/balance-sheet.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportBalanceSheet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int compare, @RequestParam(defaultValue = "MONTH") com.experterp.backend.service.FinancialReportService.Step step) {
        return file(exports.balanceSheetCsv(date, compare, step), "Balance sheet " + (date == null ? LocalDate.now() : date) + ".csv", "text/csv");
    }

    // Q6: the same reports as PDFs
    @GetMapping("/export/profit-and-loss.pdf")
    public org.springframework.http.ResponseEntity<byte[]> profitAndLossPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int compare, @RequestParam(defaultValue = "MONTH") com.experterp.backend.service.FinancialReportService.Step step) {
        return file(exports.profitAndLossPdf(from, to, compare, step), "Profit and loss " + from + " to " + to + ".pdf", "application/pdf");
    }

    @GetMapping("/export/balance-sheet.pdf")
    public org.springframework.http.ResponseEntity<byte[]> balanceSheetPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int compare, @RequestParam(defaultValue = "MONTH") com.experterp.backend.service.FinancialReportService.Step step) {
        return file(exports.balanceSheetPdf(date, compare, step), "Balance sheet " + (date == null ? LocalDate.now() : date) + ".pdf", "application/pdf");
    }

    @GetMapping("/export/account-transactions.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportAccountTransactions(@RequestParam String account,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return file(exports.accountTransactionsCsv(account, from, to), "Account " + account + " " + from + " to " + to + ".csv", "text/csv");
    }

    @GetMapping("/export/general-ledger.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportGeneralLedger(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return file(exports.generalLedgerCsv(from, to), "General ledger " + from + " to " + to + ".csv", "text/csv");
    }

    @GetMapping("/export/year-end")
    public org.springframework.http.ResponseEntity<byte[]> yearEndPack(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate yearEnd) {
        com.experterp.backend.service.LedgerExportService.Pack p = exports.yearEndPack(yearEnd);
        return file(p.zip(), p.filename(), "application/zip");
    }

    private static org.springframework.http.ResponseEntity<byte[]> file(byte[] body, String name, String type) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment().filename(name, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(org.springframework.http.MediaType.parseMediaType(type)).body(body);
    }
}
