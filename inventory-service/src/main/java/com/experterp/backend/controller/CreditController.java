package com.experterp.backend.controller;

import com.experterp.backend.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** A customer's credit standing and the manual credit hold (phase G2). */
@RestController
@RequestMapping("/api/customers/{id}/credit")
@RequiredArgsConstructor
public class CreditController {

    private final CreditService creditService;

    public record HoldRequest(boolean hold, String reason) {}

    @GetMapping
    public CreditService.CreditStatus status(@PathVariable Long id) {
        return creditService.status(id);
    }

    @PutMapping("/hold")
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_CREDIT')")
    public CreditService.CreditStatus hold(@PathVariable Long id, @RequestBody HoldRequest req) {
        return creditService.setHold(id, req.hold(), req.reason());
    }
}
