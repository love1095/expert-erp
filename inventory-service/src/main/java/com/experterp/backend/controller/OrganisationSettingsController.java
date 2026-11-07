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
    private final com.experterp.backend.service.CompanyDetailsService companyDetailsService;

    @GetMapping("/settings/organisation")
    public OrganisationSettingsService.Settings get() {
        return settingsService.get();
    }

    @PutMapping("/settings/organisation")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN') or hasAuthority('PERM_SETTINGS')")
    public OrganisationSettingsService.Settings update(@Valid @RequestBody OrganisationSettingsService.Settings settings) {
        return settingsService.update(settings);
    }

    // The company details printed on documents (phase D5)
    @GetMapping("/settings/company")
    public com.experterp.backend.service.CompanyDetailsService.Details company() {
        return companyDetailsService.get();
    }

    @PutMapping("/settings/company")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN') or hasAuthority('PERM_SETTINGS')")
    public com.experterp.backend.service.CompanyDetailsService.Details updateCompany(
            @Valid @RequestBody com.experterp.backend.service.CompanyDetailsService.Details details) {
        return companyDetailsService.update(details);
    }

    @PostMapping(value = "/settings/company/logo", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN') or hasAuthority('PERM_SETTINGS')")
    public com.experterp.backend.service.CompanyDetailsService.Details uploadLogo(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return companyDetailsService.setLogo(file.getBytes(), file.getContentType());
    }

    @DeleteMapping("/settings/company/logo")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN') or hasAuthority('PERM_SETTINGS')")
    public com.experterp.backend.service.CompanyDetailsService.Details removeLogo() {
        return companyDetailsService.removeLogo();
    }

    @GetMapping("/settings/company/logo")
    public org.springframework.http.ResponseEntity<byte[]> logo() {
        var p = companyDetailsService.printed();
        if (p.logo() == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(p.logoContentType()))
                .cacheControl(org.springframework.http.CacheControl.noCache())
                .body(p.logo());
    }

    // The organisation's VAT rates (UK rates named as in Cin7)
    @GetMapping("/tax-rates")
    public List<TaxRate> taxRates(@RequestParam(defaultValue = "SALE") TaxRate.Usage usage) {
        return taxService.rates(usage);
    }
}
