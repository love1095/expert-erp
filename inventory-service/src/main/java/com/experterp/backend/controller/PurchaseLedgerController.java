package com.experterp.backend.controller;

import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.service.PurchaseLedgerService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The purchase ledger (phase H2): supplier credit notes, deposits, allocations, aged creditors. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PurchaseLedgerController {

    private final PurchaseLedgerService ledger;

    public record AmountRequest(BigDecimal amount) {}

    public record AllocateRequest(Long creditNoteId, BigDecimal amount) {}

    public record VoidRequest(String reason) {}

    // A supplier credit note against a bill, optionally sending goods back
    @PostMapping("/invoices/{id}/supplier-credit-notes")
    public InvoiceResponse creditNote(@PathVariable Long id, @RequestBody PurchaseLedgerService.CreditNoteRequest req) {
        return ledger.createCreditNote(id, req);
    }

    @PostMapping("/invoices/{id}/use-supplier-credit")
    public InvoiceResponse useCredit(@PathVariable Long id, @RequestBody(required = false) AmountRequest req) {
        return ledger.useSupplierCredit(id, req == null ? null : req.amount());
    }

    @PostMapping("/invoices/{id}/allocate-supplier-credit-note")
    public InvoiceResponse allocate(@PathVariable Long id, @RequestBody AllocateRequest req) {
        return ledger.allocateCreditNote(id, req.creditNoteId(), req.amount());
    }

    @GetMapping("/suppliers/{id}/credits")
    public List<PurchaseLedgerService.CreditView> credits(@PathVariable Long id) {
        return ledger.credits(id);
    }

    // A deposit: paid before the bill
    @PostMapping("/suppliers/{id}/credits")
    public PurchaseLedgerService.CreditView deposit(@PathVariable Long id, @RequestBody PurchaseLedgerService.DepositRequest req) {
        return ledger.payDeposit(id, req);
    }

    @PostMapping("/suppliers/{id}/credits/{creditId}/void")
    public void voidCredit(@PathVariable Long id, @PathVariable Long creditId, @RequestBody VoidRequest req) {
        ledger.voidCredit(id, creditId, req.reason());
    }

    @GetMapping("/suppliers/{id}/open-credit-notes")
    public List<PurchaseLedgerService.OpenCreditNote> openCreditNotes(@PathVariable Long id) {
        return ledger.openCreditNotes(id);
    }

    @GetMapping("/reports/aged-creditors")
    public PurchaseLedgerService.AgedCreditors agedCreditors(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ledger.agedCreditors(asAt);
    }

    @GetMapping(value = "/reports/aged-creditors/csv", produces = "text/csv")
    public void agedCreditorsCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
                                 HttpServletResponse response) throws IOException {
        PurchaseLedgerService.AgedCreditors r = ledger.agedCreditors(asAt);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"AgedCreditors_" + r.asAt() + ".csv\"");
        var out = response.getWriter();
        out.write('﻿');
        CSVPrinter p = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader("Supplier", "Current", "1-30 days", "31-60 days",
                "61-90 days", "Over 90 days", "Credit", "Total").build());
        for (PurchaseLedgerService.AgedCreditor c : r.suppliers()) row(p, c);
        row(p, r.totals());
        p.flush();
    }

    private static void row(CSVPrinter p, PurchaseLedgerService.AgedCreditor c) throws IOException {
        p.printRecord(c.supplierName(), c.current(), c.days1to30(), c.days31to60(), c.days61to90(), c.over90(), c.credit(), c.total());
    }
}
