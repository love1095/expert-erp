package com.experterp.backend.controller;

import com.experterp.backend.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Settings → Integrations → API: the organisation's Account ID and Application Keys (admins only). */
@RestController
@RequestMapping("/api/settings/api-keys")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public record CreateRequest(String name, String role) {}

    @GetMapping
    public ApiKeyService.Access access() {
        return apiKeyService.access();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyService.Created create(@RequestBody CreateRequest req) {
        return apiKeyService.create(req.name(), req.role());
    }

    @DeleteMapping("/{id}")
    public ApiKeyService.Key revoke(@PathVariable Long id) {
        return apiKeyService.revoke(id);
    }
}
