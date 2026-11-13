package com.experterp.backend.controller;

import com.experterp.backend.service.PlatformService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/** Platform → Organisations (SUPER_ADMIN only): every organisation, its plan and whether it is switched on. */
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantController {

    private final PlatformService platformService;

    public record SubscriptionRequest(String planTier, String status, LocalDateTime endDate) {}

    public record ActiveRequest(boolean active) {}

    /** One page, newest first; q narrows by part of the name or slug. */
    @GetMapping
    public PlatformService.OrganisationPage list(@RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "25") int size) {
        return platformService.organisations(q, page, size);
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
