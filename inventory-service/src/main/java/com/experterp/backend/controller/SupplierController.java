package com.experterp.backend.controller;

import com.experterp.backend.entity.Supplier;
import com.experterp.backend.repository.SupplierRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierRepository repo;

    public SupplierController(SupplierRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Page<Supplier> list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("name"));
        return search.isBlank() ? repo.findAll(pageable) : repo.search(search, pageable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Supplier> get(@PathVariable Long id) {
        return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Supplier supplier) {
        if (repo.existsByName(supplier.getName())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Supplier name already exists"));
        }
        return ResponseEntity.ok(repo.save(supplier));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Supplier body) {
        return repo.findById(id).map(s -> {
            s.setName(body.getName());
            s.setStatus(body.getStatus());
            s.setCurrency(body.getCurrency());
            s.setPaymentTerm(body.getPaymentTerm());
            s.setTaxNumber(body.getTaxNumber());
            s.setDiscount(body.getDiscount());
            s.setWebsite(body.getWebsite());
            s.setContactName(body.getContactName());
            s.setPhone(body.getPhone());
            s.setEmail(body.getEmail());
            s.setComments(body.getComments());
            return ResponseEntity.ok(repo.save(s));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
