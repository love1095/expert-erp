package com.experterp.backend.controller;

import com.experterp.backend.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Printed documents as PDF (phase D5): quote / sale order, invoice / credit note, purchase order. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping("/sale-orders/{id}/pdf")
    public ResponseEntity<byte[]> saleOrder(@PathVariable Long id) {
        return pdf(documentService.saleOrder(id));
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        return pdf(documentService.invoice(id));
    }

    @GetMapping("/purchase-orders/{id}/pdf")
    public ResponseEntity<byte[]> purchaseOrder(@PathVariable Long id) {
        return pdf(documentService.purchaseOrder(id));
    }

    // Ticked rows of a list in one PDF: kind = sale-orders, invoices or purchase-orders
    @GetMapping("/documents/{kind}/pdf")
    public ResponseEntity<byte[]> many(@PathVariable String kind, @RequestParam List<Long> ids) {
        return pdf(documentService.many(kind, ids));
    }

    private static ResponseEntity<byte[]> pdf(DocumentService.Pdf pdf) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.fileName()).build().toString())
                .body(pdf.bytes());
    }
}
