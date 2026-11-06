package com.experterp.backend.controller;

import com.experterp.backend.service.DocumentService;
import com.experterp.backend.service.StatementDocuments;
import com.experterp.backend.service.StatementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Customer statements (Activity, Outstanding) and overdue reminders (phase G3). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatementController {

    private final StatementService statementService;
    private final StatementDocuments statementDocuments;

    @GetMapping("/customers/{id}/statement")
    public StatementService.Statement statement(@PathVariable Long id,
                                                @RequestParam(defaultValue = "ACTIVITY") StatementService.Type type,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statementService.statement(id, type, from, to);
    }

    @GetMapping("/customers/{id}/statement/pdf")
    public ResponseEntity<byte[]> statementPdf(@PathVariable Long id,
                                               @RequestParam(defaultValue = "ACTIVITY") StatementService.Type type,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pdf(statementDocuments.statement(id, type, from, to));
    }

    // Ticked customers on the list, one PDF
    @GetMapping("/statements/pdf")
    public ResponseEntity<byte[]> statementsPdf(@RequestParam List<Long> ids,
                                                @RequestParam(defaultValue = "ACTIVITY") StatementService.Type type,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pdf(statementDocuments.statements(ids, type, from, to));
    }

    // Customers with overdue invoices, for the Overdue list
    @GetMapping("/customers/overdue")
    public List<StatementService.OverdueCustomer> overdue() {
        return statementService.overdueCustomers();
    }

    // The reminder the customer would get now (nothing recorded)
    @GetMapping("/customers/{id}/reminder")
    public StatementService.Reminder reminder(@PathVariable Long id) {
        return statementService.reminder(id);
    }

    @GetMapping("/customers/{id}/reminder/pdf")
    public ResponseEntity<byte[]> reminderPreview(@PathVariable Long id) {
        return pdf(statementDocuments.reminder(id, false));
    }

    // Prints the reminder and records it on the overdue invoices: the next one is firmer
    @PostMapping("/customers/{id}/reminder")
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_CREDIT')")
    public ResponseEntity<byte[]> sendReminder(@PathVariable Long id) {
        return pdf(statementDocuments.reminder(id, true));
    }

    private static ResponseEntity<byte[]> pdf(DocumentService.Pdf pdf) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.fileName()).build().toString())
                .body(pdf.bytes());
    }
}
