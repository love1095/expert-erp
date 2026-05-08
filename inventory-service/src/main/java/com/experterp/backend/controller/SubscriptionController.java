package com.experterp.backend.controller;

import com.experterp.backend.dto.SubscriptionResponse;
import com.experterp.backend.entity.Plan;
import com.experterp.backend.security.UserDetailsImpl;
import com.experterp.backend.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/plans")
    public List<Plan> listPlans() {
        return subscriptionService.getAllPlans();
    }

    @GetMapping("/subscription")
    public SubscriptionResponse getSubscription(@AuthenticationPrincipal UserDetailsImpl user) {
        return subscriptionService.getSubscriptionInfo(user.getTenantId());
    }

    @PostMapping("/subscription/upgrade")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<SubscriptionResponse> upgrade(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody Map<String, String> body) {
        String tier = body.get("planTier");
        if (tier == null || tier.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(subscriptionService.upgradePlan(user.getTenantId(), tier));
    }
}
