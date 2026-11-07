package com.experterp.backend.controller;

import com.experterp.backend.service.ProductUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Phase P3: a product's extra units of measure (pair, box of 100, carton). */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductUnitController {

    private final ProductUnitService units;

    @GetMapping("/{id}/units")
    public List<ProductUnitService.Unit> units(@PathVariable Long id) {
        return units.units(id);
    }

    @PutMapping("/{id}/units")
    public List<ProductUnitService.Unit> save(@PathVariable Long id, @RequestBody List<ProductUnitService.UnitRequest> list) {
        return units.save(id, list);
    }
}
