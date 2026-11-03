package com.experterp.backend.controller;

import com.experterp.backend.dto.SubscriptionResponse;
import com.experterp.backend.entity.Plan;
import com.experterp.backend.security.UserDetailsImpl;
import com.experterp.backend.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The organisation's own plan and usage (read only). Plans are changed by the platform's super admin
 * (PUT /api/tenants/{id}/subscription): there is no online payment yet.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/plans")
    public List<Plan> listPlans() {
        return subscriptionService.getAllPlans();
    }

    // The super admin belongs to no organisation and has no subscription: 204
    @GetMapping("/subscription")
    public ResponseEntity<SubscriptionResponse> getSubscription(@AuthenticationPrincipal UserDetailsImpl user) {
        if (user.getTenantId() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(subscriptionService.getSubscriptionInfo(user.getTenantId()));
    }
}
