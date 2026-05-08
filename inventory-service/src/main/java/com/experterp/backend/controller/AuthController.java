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
    private final SubscriptionService subscriptionService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
            UserDetailsImpl userDetails = (UserDetailsImpl) auth.getPrincipal();
            String token = jwtUtil.generateToken(userDetails);
            User entity = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();

            String tenantName = null, tenantSlug = null;
            if (entity.getTenantId() != null) {
                Tenant t = tenantRepo.findById(entity.getTenantId()).orElse(null);
                if (t != null) { tenantName = t.getName(); tenantSlug = t.getSlug(); }
            }

            return ResponseEntity.ok(new LoginResponse(
                    token, userDetails.getId(), userDetails.getUsername(),
                    entity.getEmail(), userDetails.getRole().name(),
                    entity.getTenantId(), tenantName, tenantSlug));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal UserDetailsImpl user) {
        if (user == null) return ResponseEntity.status(401).build();
        User entity = userRepository.findByUsername(user.getUsername()).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", entity.getEmail() != null ? entity.getEmail() : "",
                "role", user.getRole().name()
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already taken"));
        }

        Long tenantId;
        if (req.getOrganizationName() != null && !req.getOrganizationName().isBlank()) {
            // New organization signup
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
            tenantId = currentUser.getTenantId();
            subscriptionService.checkUserLimit(tenantId);
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setTenantId(tenantId);
        try {
            user.setRole(req.getRole() != null && !req.getRole().isBlank()
                    ? User.Role.valueOf(req.getRole()) : User.Role.ADMIN);
        } catch (IllegalArgumentException e) {
            user.setRole(User.Role.ADMIN);
        }
        userRepository.save(user);

        Tenant tenant = tenantRepo.findById(tenantId).orElseThrow();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtUtil.generateToken(userDetails);
        return ResponseEntity.ok(new LoginResponse(
                token, user.getId(), user.getUsername(),
                user.getEmail(), user.getRole().name(),
                tenantId, tenant.getName(), tenant.getSlug()));
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public Page<Map<String, Object>> listUsers(
            @AuthenticationPrincipal UserDetailsImpl current,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("username"));
        return userRepository.findByTenantId(current.getTenantId(), pageable)
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "username", u.getUsername(),
                        "email", u.getEmail() != null ? u.getEmail() : "",
                        "role", u.getRole().name(),
                        "active", u.isActive(),
                        "createdAt", u.getCreatedAt()));
    }
}
