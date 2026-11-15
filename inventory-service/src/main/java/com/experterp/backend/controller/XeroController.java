package com.experterp.backend.controller;

import com.experterp.backend.integration.xero.XeroConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase L: the Xero API link (Settings → Integrations → Xero). Setting it up (connect, choose the organisation, CSV or
 * API, disconnect): admins and the Settings permission. What was sent, what failed and the payments pulled: the
 * financial reports permission, with Retry / Send now / Pull now for advisers and admins.
 */
@RestController
@RequestMapping("/api/xero")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_REPORTS_FINANCIAL')")
public class XeroController {

    static final String SETUP = "hasAnyRole('ADMIN', 'SUPER_ADMIN') or hasAuthority('PERM_SETTINGS')";
    static final String SEE = SETUP + " or hasAuthority('PERM_REPORTS_FINANCIAL')";

    private final XeroConnectionService connection;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.experterp.backend.integration.xero.XeroClient xero;
    private final com.experterp.backend.integration.xero.XeroSyncService sync;
    private final com.experterp.backend.service.AccountingExportService export;
    private final com.experterp.backend.integration.xero.XeroPaymentPuller puller;

    // ---- payments from Xero (L4) ----

    @GetMapping("/payments")
    public java.util.List<com.experterp.backend.integration.xero.XeroPaymentPuller.Pulled> pulled(@RequestParam(defaultValue = "100") int limit) {
        return puller.pulled(limit);
    }

    @PostMapping("/payments/pull")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public com.experterp.backend.integration.xero.XeroPaymentPuller.PullResult pull() {
        return puller.pull();
    }

    /** CSV (files) or API (this link): one way to Xero at a time (L3). */
    @GetMapping("/link")
    @PreAuthorize(SEE)
    public Map<String, String> link() {
        return Map.of("link", export.xeroLink());
    }

    @PutMapping("/link")
    @PreAuthorize(SETUP)
    public Map<String, String> setLink(@RequestBody Map<String, String> body) {
        return Map.of("link", export.setXeroLink(body.get("link")));
    }

    @GetMapping("/connection")
    @PreAuthorize(SEE)
    public XeroConnectionService.Status status() {
        return connection.status();
    }

    @PostMapping("/connection/connect")
    @PreAuthorize(SETUP)
    public Map<String, String> connect() {
        return Map.of("url", connection.connect());
    }

    @PostMapping("/connection/callback")
    @PreAuthorize(SETUP)
    public XeroConnectionService.Status callback(@RequestBody Map<String, String> body) {
        return connection.callback(body.get("code"), body.get("state"));
    }

    @PutMapping("/connection/organisation")
    @PreAuthorize(SETUP)
    public XeroConnectionService.Status choose(@RequestBody Map<String, String> body) {
        return connection.choose(body.get("xeroTenantId"));
    }

    @DeleteMapping("/connection")
    @PreAuthorize(SETUP)
    public XeroConnectionService.Status disconnect() {
        return connection.disconnect();
    }

    /** The connected Xero organisation's chart of accounts, by code: to choose and check the account mapping. */
    @GetMapping("/accounts")
    public java.util.List<com.experterp.backend.integration.xero.XeroApi.Account> accounts() {
        return xero.accounts(connection.session(false)).stream()
                .filter(a -> a.code() != null && !"ARCHIVED".equals(a.status()))
                .sorted(java.util.Comparator.comparing(com.experterp.backend.integration.xero.XeroApi.Account::code)).toList();
    }

    public record AccountProblem(String what, String code, String problem) {}

    /**
     * The account mapping checked against the connected Xero's chart, before anything is refused there: a code Xero
     * does not have, Xero's own tracked-inventory account (Xero refuses a bill on it: Cin7 uses a Current
     * Asset account, 631), and payment accounts that are not bank accounts in Xero.
     */
    @GetMapping("/accounts/check")
    public java.util.List<AccountProblem> checkAccounts() {
        java.util.Map<String, com.experterp.backend.integration.xero.XeroApi.Account> chart = new java.util.HashMap<>();
        accounts().forEach(a -> chart.put(a.code().toLowerCase(), a));
        var s = export.settings();
        java.util.List<AccountProblem> out = new java.util.ArrayList<>();
        java.util.Map<String, String> mapping = new java.util.LinkedHashMap<>();
        mapping.put("Inventory control account", s.inventoryAccount());
        mapping.put("Cost of goods sold account", s.cogsAccount());
        mapping.put("Inventory discrepancy account", s.discrepancyAccount());
        mapping.put("Default revenue account", s.revenueAccount());
        mapping.put("Purchase charges account", s.purchaseChargesAccount());
        mapping.put("Customer credit account", s.customerCreditAccount());
        mapping.put("Supplier deposits account", s.supplierDepositsAccount());
        mapping.forEach((what, code) -> {
            if (code == null || code.isBlank()) return;
            var a = chart.get(code.toLowerCase());
            if (a == null) out.add(new AccountProblem(what, code, "Xero has no account " + code + ": documents using it will be refused"));
            else if ("INVENTORY".equals(a.type())) out.add(new AccountProblem(what, code, code + " " + a.name()
                    + " is Xero's own tracked-inventory account, which Xero refuses on bills: use a Current Asset account instead (Cin7 uses 631)"));
        });
        for (String code : jdbc.queryForList("SELECT code FROM payment_accounts WHERE tenant_id = ? AND active ORDER BY code", String.class,
                com.experterp.backend.multitenancy.TenantContext.get())) {
            var a = chart.get(code.toLowerCase());
            if (a == null || !"BANK".equals(a.type())) out.add(new AccountProblem("Payment account", code,
                    a == null ? "Xero has no bank account " + code + ": payments recorded into it here cannot be sent"
                            : code + " " + a.name() + " is not a bank account in Xero: payments recorded into it here cannot be sent"));
        }
        return out;
    }

    // ---- the sync queue (L2) ----

    @GetMapping("/queue")
    public Map<String, Object> queue(@RequestParam(required = false) String status, @RequestParam(defaultValue = "200") int limit) {
        return Map.of("counts", sync.counts(), "items", sync.items(status, limit));
    }

    @PostMapping("/queue/{id}/retry")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public void retry(@PathVariable Long id) {
        sync.retry(id);
    }

    /** Sends what is due now instead of waiting for the background run. */
    @PostMapping("/queue/run")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public com.experterp.backend.integration.xero.XeroSyncService.RunResult run() {
        return sync.process();
    }
}
