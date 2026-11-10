package com.experterp.backend.controller;

import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.dto.CustomerResponse;
import com.experterp.backend.service.CustomerImportService;
import com.experterp.backend.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerImportService importService;
    private final com.experterp.backend.service.CustomerExportService exportService;

    @GetMapping
    public Page<CustomerResponse> list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return customerService.findAll(search, page, size);
    }

    // The sales rep list on the sale order and customer forms
    @GetMapping("/sales-reps")
    public java.util.List<String> salesReps() {
        return customerService.salesReps();
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable Long id) {
        return customerService.findById(id);
    }

    // "+" next to the Contact field of a sale: adds one contact, the others stay
    @PostMapping("/{id}/contacts")
    public ResponseEntity<CustomerResponse> addContact(@PathVariable Long id, @Valid @RequestBody CustomerRequest.Contact req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.addContact(id, req));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.create(req));
    }

    // Cin7 Core's customer files (type = CUSTOMERS, ADDRESSES, CONTACTS, CUSTOM_PRICES; a file of another type is refused)
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('PERM_DATA_IMPORT')")
    public CustomerImportService.ImportResult importCsv(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(required = false) CustomerImportService.Kind type) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        return importService.importCsv(file.getInputStream(), type);
    }

    // The same four files, in Cin7's columns, so they can be imported back (here or in Cin7)
    @GetMapping(value = "/export", produces = "text/csv")
    public void export(@RequestParam(defaultValue = "CUSTOMERS") CustomerImportService.Kind type,
                       jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + exportService.fileName(type) + "\"");
        exportService.export(type, response.getWriter());
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest req) {
        return customerService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        customerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
