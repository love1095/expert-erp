package com.experterp.backend.controller;

import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.service.SalesLedgerService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The sales ledger's money (phase H1): payment accounts, customer credit, allocations, voids, aged debtors. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SalesLedgerController {

    private final SalesLedgerService ledger;

    public record UseCreditRequest(BigDecimal amount) {}

    public record AllocateRequest(Long creditNoteId, BigDecimal amount) {}

    public record VoidRequest(String reason) {}

    // ---- Settings → Payment accounts ----

    @GetMapping("/settings/payment-accounts")
    public List<SalesLedgerService.AccountView> accounts() {
        return ledger.accounts();
    }

    @PutMapping("/settings/payment-accounts")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public List<SalesLedgerService.AccountView> saveAccounts(@RequestBody List<SalesLedgerService.AccountRequest> rows) {
        return ledger.saveAccounts(rows);
    }

    // ---- customer credit ----

    @GetMapping("/customers/{id}/credits")
    public List<SalesLedgerService.CreditView> credits(@PathVariable Long id) {
        return ledger.credits(id);
    }

    // Money received before there is an invoice
    @PostMapping("/customers/{id}/credits")
    public SalesLedgerService.CreditView receiveOnAccount(@PathVariable Long id, @RequestBody SalesLedgerService.CreditRequest req) {
        return ledger.receiveOnAccount(id, req);
    }

    @PostMapping("/customers/{id}/credits/{creditId}/void")
    public void voidCredit(@PathVariable Long id, @PathVariable Long creditId, @RequestBody VoidRequest req) {
        ledger.voidCredit(id, creditId, req.reason());
    }

    @GetMapping("/customers/{id}/open-credit-notes")
    public List<SalesLedgerService.OpenCreditNote> openCreditNotes(@PathVariable Long id) {
        return ledger.openCreditNotes(id);
    }

    // ---- on an invoice ----

    @PostMapping("/invoices/{id}/use-credit")
    public InvoiceResponse useCredit(@PathVariable Long id, @RequestBody(required = false) UseCreditRequest req) {
        return ledger.useCustomerCredit(id, req == null ? null : req.amount());
    }

    @PostMapping("/invoices/{id}/allocate-credit-note")
    public InvoiceResponse allocate(@PathVariable Long id, @RequestBody AllocateRequest req) {
        return ledger.allocateCreditNote(id, req.creditNoteId(), req.amount());
    }

    @PostMapping("/invoices/{id}/payments/{paymentId}/void")
    public InvoiceResponse voidPayment(@PathVariable Long id, @PathVariable Long paymentId, @RequestBody VoidRequest req) {
        return ledger.voidPayment(id, paymentId, req.reason());
    }

    // ---- aged debtors ----

    @GetMapping("/reports/aged-debtors")
    public SalesLedgerService.AgedDebtors agedDebtors(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ledger.agedDebtors(asAt);
    }

    @GetMapping(value = "/reports/aged-debtors/csv", produces = "text/csv")
    public void agedDebtorsCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
                               HttpServletResponse response) throws IOException {
        SalesLedgerService.AgedDebtors r = ledger.agedDebtors(asAt);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"AgedDebtors_" + r.asAt() + ".csv\"");
        var out = response.getWriter();
        out.write('﻿');
        CSVPrinter p = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader("Customer", "Current", "1-30 days", "31-60 days",
                "61-90 days", "Over 90 days", "Credit", "Total", "Credit limit", "On hold").build());
        for (SalesLedgerService.AgedDebtor d : r.customers()) row(p, d);
        row(p, r.totals());
        p.flush();
    }

    private static void row(CSVPrinter p, SalesLedgerService.AgedDebtor d) throws IOException {
        p.printRecord(d.customerName(), d.current(), d.days1to30(), d.days31to60(), d.days61to90(), d.over90(), d.credit(), d.total(),
                d.creditLimit(), d.customerId() == null ? "" : d.onHold() ? "Yes" : "No");
    }
}
