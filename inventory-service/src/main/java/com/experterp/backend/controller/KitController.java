package com.experterp.backend.controller;

import com.experterp.backend.service.KitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Phase P2: a kit's components and what the stock can make of it. */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class KitController {

    private final KitService kits;

    @GetMapping("/{id}/components")
    public List<KitService.Component> components(@PathVariable Long id) {
        return kits.components(id);
    }

    @PutMapping("/{id}/components")
    public List<KitService.Component> setComponents(@PathVariable Long id, @RequestBody List<KitService.ComponentRequest> components) {
        return kits.setComponents(id, components);
    }

    @GetMapping("/{id}/buildable")
    public List<KitService.Buildable> buildable(@PathVariable Long id) {
        kits.components(id);
        return kits.buildable(id);
    }
}
