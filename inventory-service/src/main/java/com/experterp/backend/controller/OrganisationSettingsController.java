package com.experterp.backend.controller;

import com.experterp.backend.entity.TaxRate;
import com.experterp.backend.service.OrganisationSettingsService;
import com.experterp.backend.service.TaxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrganisationSettingsController {

    private final OrganisationSettingsService settingsService;
    private final TaxService taxService;

    @GetMapping("/settings/organisation")
    public OrganisationSettingsService.Settings get() {
        return settingsService.get();
    }

    @PutMapping("/settings/organisation")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public OrganisationSettingsService.Settings update(@Valid @RequestBody OrganisationSettingsService.Settings settings) {
        return settingsService.update(settings);
    }

    // The organisation's VAT rates (UK rates named as in Cin7)
    @GetMapping("/tax-rates")
    public List<TaxRate> taxRates(@RequestParam(defaultValue = "SALE") TaxRate.Usage usage) {
        return taxService.rates(usage);
    }
}
