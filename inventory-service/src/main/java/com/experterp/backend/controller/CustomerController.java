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

    @GetMapping
    public Page<CustomerResponse> list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return customerService.findAll(search, page, size);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable Long id) {
        return customerService.findById(id);
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.create(req));
    }

    // Cin7 Core customer export; creates or updates customers by name
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SUPER_ADMIN')")
    public CustomerImportService.ImportResult importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        return importService.importCsv(file.getInputStream());
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
