package com.experterp.backend.controller;

import com.experterp.backend.integration.xero.XeroAppSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Platform → Integration apps (SUPER_ADMIN only): the platform's own app at each outside system. Today the Expert ERP
 * app at Xero; every organisation connects its own Xero with it and never sees the client ID or secret.
 */
@RestController
@RequestMapping("/api/platform/integration-apps")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformIntegrationController {

    private final XeroAppSettings xero;

    @GetMapping("/xero")
    public XeroAppSettings.View xero() {
        return xero.view();
    }

    @PutMapping("/xero")
    public XeroAppSettings.View saveXero(@RequestBody XeroAppSettings.Change change) {
        return xero.save(change);
    }

    @DeleteMapping("/xero")
    public XeroAppSettings.View removeXero() {
        return xero.remove();
    }
}
