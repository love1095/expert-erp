package com.experterp.backend.controller;

import com.experterp.backend.entity.Supplier;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.service.SupplierFileService;
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
    private final SupplierFileService fileService;

    public SupplierController(SupplierRepository repo, SupplierFileService fileService) {
        this.repo = repo;
        this.fileService = fileService;
    }

    // Cin7's Suppliers file, so it can be imported back (here or into Cin7)
    @GetMapping(value = "/export", produces = "text/csv")
    public void export(@RequestParam(defaultValue = "SUPPLIERS") SupplierFileService.Kind type,
                       jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileService.fileName(type) + "\"");
        fileService.export(type, response.getWriter());
    }

    // Creates or updates suppliers by name; a file of another type is refused
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('PERM_DATA_IMPORT')")
    public SupplierFileService.ImportResult importCsv(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                      @RequestParam(defaultValue = "SUPPLIERS") SupplierFileService.Kind type) throws java.io.IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        return fileService.importCsv(type, file.getBytes());
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
        supplier.setAddress(com.experterp.backend.entity.Address.clean(supplier.getAddress()));
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
            s.setAddress(com.experterp.backend.entity.Address.clean(body.getAddress()));
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
