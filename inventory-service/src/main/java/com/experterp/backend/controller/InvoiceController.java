package com.experterp.backend.controller;

import com.experterp.backend.dto.CreditNoteRequest;
import com.experterp.backend.dto.InvoiceRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.entity.Invoice;
import com.experterp.backend.service.CreditNoteService;
import com.experterp.backend.service.InvoiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final CreditNoteService creditNoteService;

    @GetMapping
    public Page<InvoiceResponse> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Invoice.InvoiceType type,
            @RequestParam(required = false) Invoice.InvoiceStatus status,
            @RequestParam(required = false) String q) {
        return invoiceService.findAll(page, size, type, status, q);
    }

    @GetMapping("/{id}")
    public InvoiceResponse findById(@PathVariable Long id) {
        return invoiceService.findById(id);
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> create(@Valid @RequestBody InvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceService.create(req));
    }

    @PostMapping("/{id}/issue")
    public InvoiceResponse issue(@PathVariable Long id) {
        return invoiceService.issue(id);
    }

    @PostMapping("/{id}/cancel")
    public InvoiceResponse cancel(@PathVariable Long id) {
        return invoiceService.cancel(id);
    }

    // Credit note & restock for a sales invoice; settled by a refund, paying off the invoice, or kept as credit
    @PostMapping("/{id}/credit-notes")
    public ResponseEntity<InvoiceResponse> creditNote(@PathVariable Long id, @Valid @RequestBody CreditNoteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditNoteService.create(id, req));
    }

    @PostMapping("/{id}/pay")
    public InvoiceResponse pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest req) {
        return invoiceService.addPayment(id, req);
    }
}
