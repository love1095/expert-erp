package com.experterp.backend.service;

import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.security.SecretCipher;
import com.experterp.backend.security.Totp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Two-step sign-in with an authenticator app (phase I5), like Cin7 Core's two-factor authentication: a user turns it
 * on from their account (secret confirmed with a first code; 8 one-time recovery codes given once), or must set it up
 * at sign-in when their organisation requires it. Codes are accepted once; 5 wrong codes lock the second step for
 * 15 minutes. An admin resets it for someone who lost their phone (Cin7: "Reset two-factor authentication").
 */
@Service
@RequiredArgsConstructor
public class TwoFactorService {

    public static final String ISSUER = "Expert ERP";
    static final int MAX_FAILED = 5;
    static final int LOCK_MINUTES = 15;
    static final int RECOVERY_CODES = 8;

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SecretCipher cipher;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    /** What the user must do after the password: nothing, give a code, or set the app up first. */
    public enum Step { NONE, CODE, SETUP }

    public record Setup(String secret, String otpauthUri) {}

    public record Status(boolean enabled, boolean required, int recoveryCodesLeft) {}

    public Step stepAfterPassword(User u) {
        if (u.isTotpEnabled()) return Step.CODE;
        return required(u) ? Step.SETUP : Step.NONE;
    }

    private boolean required(User u) {
        if (u.getTenantId() == null) return false;
        return tenantRepository.findById(u.getTenantId()).map(Tenant::isTwoFactorRequired).orElse(false);
    }

    public Status status(User u) {
        return new Status(u.isTotpEnabled(), required(u), u.isTotpEnabled() ? hashes(u).size() : 0);
    }

    /** A new secret to add to the app (not on until confirmed with a code). */
    @Transactional
    public Setup start(User u) {
        if (u.isTotpEnabled()) throw new IllegalStateException("Two-step sign-in is on already");
        String secret = Totp.newSecret();
        u.setTotpSecret(cipher.encrypt(secret));
        u.setTotpLastStep(null);
        userRepository.save(u);
        String account = u.getEmail() != null && !u.getEmail().isBlank() ? u.getEmail() : u.getUsername();
        return new Setup(secret, Totp.uri(ISSUER, account, secret));
    }

    /** The first code from the app turns it on; returns the recovery codes (shown once). */
    @Transactional
    public List<String> confirm(User u, String code) {
        if (u.isTotpEnabled()) throw new IllegalStateException("Two-step sign-in is on already");
        if (u.getTotpSecret() == null) throw new IllegalStateException("Start setting up two-step sign-in first");
        long step = Totp.matchingStep(cipher.decrypt(u.getTotpSecret()), code, Instant.now().getEpochSecond());
        if (step < 0) throw new IllegalArgumentException("That code is not right: check the app shows Expert ERP and try the current code");
        u.setTotpEnabled(true);
        u.setTotpLastStep(step);
        u.setTotpFailed(0);
        u.setTotpLockedUntil(null);
        List<String> codes = newRecoveryCodes(u);
        userRepository.save(u);
        auditIn(u, "TWO_FACTOR_ON", Map.of("user", u.getUsername()));
        return codes;
    }

    /**
     * Checks a code from the app, or a recovery code (used up), at sign-in. Wrong codes count; at 5 the step is locked
     * for 15 minutes. Throws with the reason when refused.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void verify(User u, String code) {
        LocalDateTime now = LocalDateTime.now();
        if (u.getTotpLockedUntil() != null && u.getTotpLockedUntil().isAfter(now)) {
            throw new IllegalStateException("Too many wrong codes: try again after " + u.getTotpLockedUntil().toLocalTime().withNano(0));
        }
        String clean = code == null ? "" : code.trim();
        long step = Totp.matchingStep(cipher.decrypt(u.getTotpSecret()), clean, Instant.now().getEpochSecond());
        boolean ok = false;
        if (step >= 0 && (u.getTotpLastStep() == null || step > u.getTotpLastStep())) {
            u.setTotpLastStep(step);
            ok = true;
        } else if (step < 0 && useRecoveryCode(u, clean)) {
            ok = true;
            auditIn(u, "TWO_FACTOR_RECOVERY_CODE", Map.of("user", u.getUsername(), "left", hashes(u).size()));
        }
        if (ok) {
            u.setTotpFailed(0);
            u.setTotpLockedUntil(null);
            userRepository.save(u);
            return;
        }
        u.setTotpFailed(u.getTotpFailed() + 1);
        if (u.getTotpFailed() >= MAX_FAILED) {
            u.setTotpFailed(0);
            u.setTotpLockedUntil(now.plusMinutes(LOCK_MINUTES));
            auditIn(u, "TWO_FACTOR_LOCKED", Map.of("user", u.getUsername()));
        }
        userRepository.save(u);
        throw new IllegalArgumentException(step >= 0 ? "That code was used already: wait for the next one" : "That code is not right");
    }

    /** Turns it off with a current code; not while the organisation requires it. */
    @Transactional
    public void disable(User u, String code) {
        if (!u.isTotpEnabled()) throw new IllegalStateException("Two-step sign-in is not on");
        if (required(u)) throw new IllegalStateException("Your organisation requires two-step sign-in: it cannot be turned off");
        verify(u, code);
        clear(u);
        userRepository.save(u);
        auditIn(u, "TWO_FACTOR_OFF", Map.of("user", u.getUsername()));
    }

    /** New recovery codes (the old ones stop working), with a current code. */
    @Transactional
    public List<String> newRecoveryCodes(User u, String code) {
        if (!u.isTotpEnabled()) throw new IllegalStateException("Two-step sign-in is not on");
        verify(u, code);
        List<String> codes = newRecoveryCodes(u);
        userRepository.save(u);
        auditIn(u, "TWO_FACTOR_RECOVERY_RENEWED", Map.of("user", u.getUsername()));
        return codes;
    }

    /** An admin of the organisation clears someone's two-step sign-in (lost phone). */
    @Transactional
    public void reset(Long userId) {
        User u = userRepository.findById(userId).filter(x -> Objects.equals(x.getTenantId(), TenantContext.get()))
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        clear(u);
        userRepository.save(u);
        auditService.log("USER", userId, "TWO_FACTOR_RESET", Map.of("user", u.getUsername()));
    }

    @Transactional
    public boolean setRequired(boolean required) {
        Tenant t = tenantRepository.findById(TenantContext.get()).orElseThrow();
        t.setTwoFactorRequired(required);
        tenantRepository.save(t);
        auditService.log("ORGANISATION", t.getId(), "TWO_FACTOR_REQUIRED", Map.of("required", required));
        return required;
    }

    public boolean isRequired() {
        return tenantRepository.findById(TenantContext.get()).map(Tenant::isTwoFactorRequired).orElse(false);
    }

    // ---- helpers ----

    private static void clear(User u) {
        u.setTotpEnabled(false);
        u.setTotpSecret(null);
        u.setTotpLastStep(null);
        u.setTotpFailed(0);
        u.setTotpLockedUntil(null);
        u.setTotpRecovery(null);
    }

    // xxxx-xxxx from an alphabet without look-alikes; only their SHA-256 is kept
    private List<String> newRecoveryCodes(User u) {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        List<String> codes = new ArrayList<>(), hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 8; k++) {
                if (k == 4) sb.append('-');
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            codes.add(sb.toString());
            hashes.add(sha256(sb.toString()));
        }
        u.setTotpRecovery(String.join(",", hashes));
        return codes;
    }

    private boolean useRecoveryCode(User u, String code) {
        String normal = code.toLowerCase(Locale.ROOT).replace(" ", "");
        if (!normal.matches("[a-z0-9]{4}-?[a-z0-9]{4}")) return false;
        if (normal.length() == 8) normal = normal.substring(0, 4) + "-" + normal.substring(4);
        String h = sha256(normal);
        List<String> left = new ArrayList<>(hashes(u));
        boolean found = false;
        for (Iterator<String> it = left.iterator(); it.hasNext(); ) {
            if (MessageDigest.isEqual(it.next().getBytes(StandardCharsets.US_ASCII), h.getBytes(StandardCharsets.US_ASCII))) {
                it.remove();
                found = true;
                break;
            }
        }
        if (found) u.setTotpRecovery(String.join(",", left));
        return found;
    }

    private static List<String> hashes(User u) {
        if (u.getTotpRecovery() == null || u.getTotpRecovery().isBlank()) return List.of();
        return Arrays.asList(u.getTotpRecovery().split(","));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Sign-in runs before the organisation is known: log in the user's organisation
    private void auditIn(User u, String action, Map<String, Object> details) {
        Long before = TenantContext.get();
        try {
            if (u.getTenantId() != null) TenantContext.set(u.getTenantId());
            auditService.log("USER", u.getId(), action, details);
        } finally {
            if (before != null) TenantContext.set(before); else TenantContext.clear();
        }
    }
}
