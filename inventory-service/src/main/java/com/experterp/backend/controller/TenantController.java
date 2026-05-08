package com.experterp.backend.controller;

import com.experterp.backend.entity.Tenant;
import com.experterp.backend.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<Tenant> list() {
        return tenantRepository.findAll();
    }

    @PutMapping("/{id}/toggle")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> toggleActive(@PathVariable Long id) {
        return tenantRepository.findById(id).map(t -> {
            t.setActive(!t.isActive());
            return ResponseEntity.ok(tenantRepository.save(t));
        }).orElse(ResponseEntity.notFound().build());
    }
}
