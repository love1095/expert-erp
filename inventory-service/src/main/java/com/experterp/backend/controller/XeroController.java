package com.experterp.backend.controller;

import com.experterp.backend.integration.xero.XeroConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Phase L: the Xero API link — connection (L1). Seeing it: the financial reports permission; changing it: advisers / admins. */
@RestController
@RequestMapping("/api/xero")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_REPORTS_FINANCIAL')")
public class XeroController {

    private final XeroConnectionService connection;
    private final com.experterp.backend.integration.xero.XeroSyncService sync;
    private final com.experterp.backend.service.AccountingExportService export;

    /** CSV (files) or API (this link): one way to Xero at a time (L3). */
    @GetMapping("/link")
    public Map<String, String> link() {
        return Map.of("link", export.xeroLink());
    }

    @PutMapping("/link")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public Map<String, String> setLink(@RequestBody Map<String, String> body) {
        return Map.of("link", export.setXeroLink(body.get("link")));
    }

    @GetMapping("/connection")
    public XeroConnectionService.Status status() {
        return connection.status();
    }

    @PostMapping("/connection/connect")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public Map<String, String> connect() {
        return Map.of("url", connection.connect());
    }

    @PostMapping("/connection/callback")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public XeroConnectionService.Status callback(@RequestBody Map<String, String> body) {
        return connection.callback(body.get("code"), body.get("state"));
    }

    @PutMapping("/connection/organisation")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public XeroConnectionService.Status choose(@RequestBody Map<String, String> body) {
        return connection.choose(body.get("xeroTenantId"));
    }

    @DeleteMapping("/connection")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public XeroConnectionService.Status disconnect() {
        return connection.disconnect();
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
