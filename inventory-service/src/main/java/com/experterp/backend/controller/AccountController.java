package com.experterp.backend.controller;

import com.experterp.backend.entity.User;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.security.UserDetailsImpl;
import com.experterp.backend.service.TwoFactorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The signed-in user's own two-step sign-in (phase I5; Cin7: user profile), and the admin's organisation-wide
 * requirement and reset for others.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountController {

    private final TwoFactorService twoFactorService;
    private final UserRepository userRepository;

    public record Code(String code) {}

    @GetMapping("/account/two-factor")
    public TwoFactorService.Status status(@AuthenticationPrincipal UserDetailsImpl me) {
        return twoFactorService.status(user(me));
    }

    @PostMapping("/account/two-factor/start")
    public TwoFactorService.Setup start(@AuthenticationPrincipal UserDetailsImpl me) {
        return twoFactorService.start(user(me));
    }

    @PostMapping("/account/two-factor/confirm")
    public Map<String, List<String>> confirm(@AuthenticationPrincipal UserDetailsImpl me, @RequestBody Code body) {
        return Map.of("recoveryCodes", twoFactorService.confirm(user(me), body.code()));
    }

    @PostMapping("/account/two-factor/recovery-codes")
    public Map<String, List<String>> recoveryCodes(@AuthenticationPrincipal UserDetailsImpl me, @RequestBody Code body) {
        return Map.of("recoveryCodes", twoFactorService.newRecoveryCodes(user(me), body.code()));
    }

    @PostMapping("/account/two-factor/disable")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal UserDetailsImpl me, @RequestBody Code body) {
        twoFactorService.disable(user(me), body.code());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings/security")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public Map<String, Boolean> security() {
        return Map.of("twoFactorRequired", twoFactorService.isRequired());
    }

    @PutMapping("/settings/security")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public Map<String, Boolean> setSecurity(@RequestBody Map<String, Boolean> body) {
        return Map.of("twoFactorRequired", twoFactorService.setRequired(Boolean.TRUE.equals(body.get("twoFactorRequired"))));
    }

    @PostMapping("/settings/users/{userId}/two-factor/reset")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> reset(@PathVariable Long userId) {
        twoFactorService.reset(userId);
        return ResponseEntity.noContent().build();
    }

    private User user(UserDetailsImpl me) {
        if (me == null) throw new org.springframework.security.access.AccessDeniedException("Sign in first");
        return userRepository.findById(me.getId()).orElseThrow();
    }
}
