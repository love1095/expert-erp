package com.experterp.backend.controller;

import com.experterp.backend.service.AccountingExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Export to the accounting package, Xero CSV files first (phase H5). Account mapping: admins; exporting: managers too. */
@RestController
@RequestMapping("/api/accounting")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_REPORTS_FINANCIAL')")
public class AccountingExportController {

    private final AccountingExportService service;

    @GetMapping("/settings")
    public AccountingExportService.Settings settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public AccountingExportService.Settings updateSettings(@RequestBody AccountingExportService.Settings settings) {
        return service.updateSettings(settings);
    }

    @GetMapping("/export/pending")
    public AccountingExportService.Pending pending() {
        return service.pending();
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export() {
        return zip(service.export());
    }

    @GetMapping("/exports")
    public List<AccountingExportService.Batch> batches() {
        return service.batches();
    }

    @GetMapping("/exports/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        return zip(service.file(id));
    }

    @PostMapping("/exports/{id}/undo")
    public AccountingExportService.Batch undo(@PathVariable Long id) {
        return service.undo(id);
    }

    @GetMapping("/export/changed")
    public List<AccountingExportService.Changed> changed() {
        return service.changedAfterExport();
    }

    private static ResponseEntity<byte[]> zip(AccountingExportService.Export e) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename() + "\"")
                .header("X-Export-Batch", String.valueOf(e.batchId()))
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(e.zip());
    }
}
