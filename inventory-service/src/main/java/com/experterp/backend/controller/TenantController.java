package com.experterp.backend.controller;

import com.experterp.backend.service.PlatformService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** Platform → Organisations (SUPER_ADMIN only): every organisation, its plan and whether it is switched on. */
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantController {

    private final PlatformService platformService;

    public record SubscriptionRequest(String planTier, String status, LocalDateTime endDate) {}

    public record ActiveRequest(boolean active) {}

    @GetMapping
    public List<PlatformService.Organisation> list() {
        return platformService.organisations();
    }

    @PutMapping("/{id}/subscription")
    public PlatformService.Organisation subscription(@PathVariable Long id, @RequestBody SubscriptionRequest req) {
        return platformService.setSubscription(id, req.planTier(), req.status(), req.endDate());
    }

    @PutMapping("/{id}/active")
    public PlatformService.Organisation active(@PathVariable Long id, @RequestBody ActiveRequest req) {
        return platformService.setActive(id, req.active());
    }
}
