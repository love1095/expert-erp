package com.experterp.backend.controller;

import com.experterp.backend.entity.PurchaseOrder;
import com.experterp.backend.service.OrganisationSettingsService;
import com.experterp.backend.service.PurchasePresetService;
import com.experterp.backend.service.PurchasePresetService.Preset;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * What a new purchase starts from: the organisation's defaults (location, Stock first / Invoice first) and the presets
 * (Cin7's Save as preset / Select a preset). Reading needs the purchase view permission, saving the edit one (PermissionFilter).
 */
@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchasePresetController {

    private final PurchasePresetService presets;
    private final OrganisationSettingsService settings;

    public record NewDefaults(Long warehouseId, PurchaseOrder.Approach approach) {}

    public record SavePreset(String name, Map<String, Object> data) {}

    @GetMapping("/new-defaults")
    public NewDefaults newDefaults() {
        return new NewDefaults(settings.defaultWarehouseId(), settings.defaultPurchaseApproach());
    }

    @GetMapping("/presets")
    public List<Preset> list() {
        return presets.list();
    }

    @PostMapping("/presets")
    @ResponseStatus(HttpStatus.CREATED)
    public Preset save(@RequestBody SavePreset req) {
        return presets.save(req.name(), req.data());
    }

    @DeleteMapping("/presets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        presets.delete(id);
    }
}
