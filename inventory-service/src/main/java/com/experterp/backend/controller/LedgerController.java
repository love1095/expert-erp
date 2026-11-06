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

    // ---- chart of accounts ----

    @GetMapping("/accounts")
    public List<ChartOfAccountsService.Account> accounts(@RequestParam(defaultValue = "false") boolean archived) {
        chart.loadStandardChart();
        return chart.list(archived);
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ChartOfAccountsService.Account addAccount(@RequestBody ChartOfAccountsService.AccountRequest req) {
        return chart.save(null, req);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ChartOfAccountsService.Account updateAccount(@PathVariable Long id, @RequestBody ChartOfAccountsService.AccountRequest req) {
        return chart.save(id, req);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public void deleteAccount(@PathVariable Long id) {
        chart.delete(id);
    }

    @PostMapping(value = "/accounts/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public LedgerService.Settings updateSettings(@RequestBody LedgerService.Settings s) {
        return ledger.updateSettings(s);
    }

    @GetMapping("/opening-balances")
    public LedgerService.Opening opening() {
        return ledger.opening();
    }

    @PutMapping("/opening-balances")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
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

    // ---- reports ----

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
}
