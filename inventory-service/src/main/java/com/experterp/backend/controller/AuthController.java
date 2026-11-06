package com.experterp.backend.controller;

import com.experterp.backend.dto.*;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.security.JwtUtil;
import com.experterp.backend.security.UserDetailsImpl;
import com.experterp.backend.security.UserDetailsServiceImpl;
import com.experterp.backend.service.SubscriptionService;
import com.experterp.backend.service.TwoFactorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepo;
    private final PasswordEncoder passwordEncoder;
    private final UserDetailsServiceImpl userDetailsService;
    private final TwoFactorService twoFactorService;
    private final SubscriptionService subscriptionService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
            UserDetailsImpl userDetails = (UserDetailsImpl) auth.getPrincipal();
            User entity = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
            ResponseEntity<?> off = switchedOff(entity);
            if (off != null) return off;

            // Phase I5: the password was right; a code from the app comes next (or setting the app up first)
            switch (twoFactorService.stepAfterPassword(entity)) {
                case CODE -> {
                    return ResponseEntity.ok(Map.of("twoFactor", "CODE",
                            "challenge", jwtUtil.generateChallengeToken(entity.getUsername(), "CODE")));
                }
                case SETUP -> {
                    TwoFactorService.Setup setup = twoFactorService.start(entity);
                    return ResponseEntity.ok(Map.of("twoFactor", "SETUP",
                            "challenge", jwtUtil.generateChallengeToken(entity.getUsername(), "SETUP"),
                            "secret", setup.secret(), "otpauthUri", setup.otpauthUri()));
                }
                default -> { }
            }
            return ResponseEntity.ok(session(userDetails, entity));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }
    }

    public record TwoFactorLogin(String challenge, String code) {}

    /** Second step: the code from the app (or a recovery code) for the challenge given after the password. */
    @PostMapping("/login/two-factor")
    public ResponseEntity<?> loginTwoFactor(@RequestBody TwoFactorLogin req) {
        User entity = challenged(req.challenge(), "CODE");
        if (entity == null) return ResponseEntity.status(401).body(Map.of("error", "Sign in again: the time for the code ran out"));
        ResponseEntity<?> off = switchedOff(entity);
        if (off != null) return off;
        try {
            twoFactorService.verify(entity, req.code());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(session((UserDetailsImpl) userDetailsService.loadUserByUsername(entity.getUsername()), entity));
    }

    /** Second step when the organisation requires the app and it is not set up yet: the first code turns it on. */
    @PostMapping("/login/two-factor/setup")
    public ResponseEntity<?> loginTwoFactorSetup(@RequestBody TwoFactorLogin req) {
        User entity = challenged(req.challenge(), "SETUP");
        if (entity == null) return ResponseEntity.status(401).body(Map.of("error", "Sign in again: the time for setting up ran out"));
        ResponseEntity<?> off = switchedOff(entity);
        if (off != null) return off;
        List<String> codes;
        try {
            codes = twoFactorService.confirm(entity, req.code());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
        LoginResponse session = session((UserDetailsImpl) userDetailsService.loadUserByUsername(entity.getUsername()), entity);
        return ResponseEntity.ok(Map.of("session", session, "recoveryCodes", codes));
    }

    private User challenged(String challenge, String purpose) {
        String username = challenge == null ? null : jwtUtil.challengeUsername(challenge, purpose);
        if (username == null) return null;
        return userRepository.findByUsername(username).filter(User::isActive).orElse(null);
    }

    // A switched-off organisation (Platform → Organisations): its people cannot log in
    private ResponseEntity<?> switchedOff(User entity) {
        if (entity.getTenantId() == null) return null;
        Tenant t = tenantRepo.findById(entity.getTenantId()).orElse(null);
        if (t != null && !t.isActive()) {
            return ResponseEntity.status(403).body(Map.of("error", "This organisation is switched off. Contact Expert ERP."));
        }
        return null;
    }

    private LoginResponse session(UserDetailsImpl userDetails, User entity) {
        String tenantName = null, tenantSlug = null;
        if (entity.getTenantId() != null) {
            Tenant t = tenantRepo.findById(entity.getTenantId()).orElse(null);
            if (t != null) { tenantName = t.getName(); tenantSlug = t.getSlug(); }
        }
        return new LoginResponse(jwtUtil.generateToken(userDetails), userDetails.getId(), userDetails.getUsername(),
                entity.getEmail(), userDetails.getRole().name(), entity.getTenantId(), tenantName, tenantSlug);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal UserDetailsImpl user) {
        if (user == null) return ResponseEntity.status(401).build();
        User entity = userRepository.findByUsername(user.getUsername()).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", entity.getEmail() != null ? entity.getEmail() : "",
                "role", user.getRole().name(),
                "twoFactorEnabled", entity.isTotpEnabled(),
                // Phase I1: what the user's role allows, for the pages to show only that
                "permissions", user.getPermissions().stream().map(Enum::name).sorted().toList()
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already taken"));
        }

        Long tenantId;
        User.Role role;
        if (req.getOrganizationName() != null && !req.getOrganizationName().isBlank()) {
            // New organization signup — the founder is always the tenant ADMIN; a requested role is ignored
            role = User.Role.ADMIN;
            String slug = req.getOrganizationName().trim().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (tenantRepo.existsBySlug(slug)) {
                slug = slug + "-" + System.currentTimeMillis() % 10000;
            }
            Tenant tenant = new Tenant();
            tenant.setName(req.getOrganizationName().trim());
            tenant.setSlug(slug);
            tenant = tenantRepo.save(tenant);
            tenantId = tenant.getId();
            subscriptionService.assignFreePlan(tenantId);
        } else {
            // Add user to current tenant (requires ADMIN auth)
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) {
                return ResponseEntity.status(403).body(Map.of("error", "Authentication required to add users"));
            }
            UserDetailsImpl currentUser = (UserDetailsImpl) auth.getPrincipal();
            if (currentUser.getRole() != User.Role.ADMIN && currentUser.getRole() != User.Role.SUPER_ADMIN) {
                return ResponseEntity.status(403).body(Map.of("error", "Only admins can add users"));
            }
            role = User.Role.ADMIN;
            if (req.getRole() != null && !req.getRole().isBlank()) {
                try {
                    role = User.Role.valueOf(req.getRole());
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Unknown role: " + req.getRole()));
                }
            }
            // Platform-wide role: only an existing SUPER_ADMIN may grant it
            if (role == User.Role.SUPER_ADMIN && currentUser.getRole() != User.Role.SUPER_ADMIN) {
                return ResponseEntity.status(403).body(Map.of("error", "Only a super admin can create super admins"));
            }
            tenantId = currentUser.getTenantId();
            // The super admin belongs to no organisation: it can only add other super admins here
            if (tenantId == null && role != User.Role.SUPER_ADMIN) {
                return ResponseEntity.badRequest().body(Map.of("error", "A super admin belongs to no organisation: people are added by the organisation's admin"));
            }
            if (tenantId != null) subscriptionService.checkUserLimit(tenantId);
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setTenantId(tenantId);
        user.setRole(role);
        userRepository.save(user);

        Tenant tenant = tenantId == null ? null : tenantRepo.findById(tenantId).orElseThrow();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtUtil.generateToken(userDetails);
        return ResponseEntity.ok(new LoginResponse(
                token, user.getId(), user.getUsername(),
                user.getEmail(), user.getRole().name(),
                tenantId, tenant == null ? null : tenant.getName(), tenant == null ? null : tenant.getSlug()));
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public Page<Map<String, Object>> listUsers(
            @AuthenticationPrincipal UserDetailsImpl current,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("username"));
        return userRepository.findByTenantId(current.getTenantId(), pageable)
                .map(u -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("email", u.getEmail() != null ? u.getEmail() : "");
                    m.put("role", u.getRole().name());
                    m.put("accessRoleId", u.getAccessRoleId());
                    m.put("active", u.isActive());
                    m.put("createdAt", u.getCreatedAt());
                    return m;
                });
    }
}
