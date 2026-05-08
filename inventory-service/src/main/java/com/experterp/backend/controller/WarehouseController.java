package com.experterp.backend.controller;

import com.experterp.backend.dto.WarehouseRequest;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.service.WarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.Page;

@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping
    public Page<Warehouse> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return warehouseService.findAll(page, size);
    }

    @GetMapping("/{id}")
    public Warehouse findById(@PathVariable Long id) {
        return warehouseService.findById(id);
    }

    @PostMapping
    public ResponseEntity<Warehouse> create(@Valid @RequestBody WarehouseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(warehouseService.create(req));
    }

    @PutMapping("/{id}")
    public Warehouse update(@PathVariable Long id, @Valid @RequestBody WarehouseRequest req) {
        return warehouseService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        warehouseService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
