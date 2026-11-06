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
}
