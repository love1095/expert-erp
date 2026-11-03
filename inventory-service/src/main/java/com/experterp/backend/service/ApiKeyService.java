package com.experterp.backend.service;

import com.experterp.backend.entity.ApiKey;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ApiKeyRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.security.ApiKeyPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * API access per organisation, like Cin7 Core: the organisation's Account ID plus an Application Key made by one of
 * its admins. A key is 32 random bytes; only its SHA-256 is kept, so it is shown once, when it is made. A revoked
 * key, an inactive organisation or a subscription that is no longer active stops working at once.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    public static final String ACCOUNT_HEADER = "api-auth-accountid";
    public static final String KEY_HEADER = "api-auth-applicationkey";
    static final String KEY_START = "ek_";
    static final int MAX_ACTIVE_KEYS = 20;
    private static final Set<User.Role> ROLES = EnumSet.of(User.Role.ADMIN, User.Role.MANAGER, User.Role.STAFF);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public record Key(Long id, String name, String keyPrefix, String role, String createdBy, LocalDateTime createdAt,
                      LocalDateTime lastUsedAt, LocalDateTime revokedAt, String revokedBy) {}

    /** The organisation's Account ID and its keys (revoked ones too, so it is clear what stopped working). */
    public record Access(UUID accountId, List<Key> keys) {}

    /** A new key: the only time {@code applicationKey} is shown. */
    public record Created(UUID accountId, Key key, String applicationKey) {}

    @Transactional(readOnly = true)
    public Access access() {
        return new Access(tenant().getAccountId(), repository.findAllByOrderByCreatedAtDesc().stream().map(ApiKeyService::toKey).toList());
    }

    @Transactional
    public Created create(String name, String role) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) throw new IllegalArgumentException("Give the key a name, e.g. the system that will use it");
        if (cleanName.length() > 100) throw new IllegalArgumentException("The name can be at most 100 characters");
        User.Role r = role(role);
        if (repository.countByRevokedAtIsNull() >= MAX_ACTIVE_KEYS) {
            throw new IllegalStateException("An organisation can have at most " + MAX_ACTIVE_KEYS + " keys in use: revoke one first");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = KEY_START + Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        ApiKey k = new ApiKey();
        k.setName(cleanName);
        k.setRole(r);
        k.setKeyHash(sha256(secret));
        k.setKeyPrefix(secret.substring(0, 10));
        k.setCreatedBy(auditService.currentActorName());
        k = repository.save(k);
        auditService.log("ApiKey", k.getId(), "CREATE", Map.of("name", cleanName, "role", r.name(), "key", k.getKeyPrefix() + "…"));
        return new Created(tenant().getAccountId(), toKey(k), secret);
    }

    @Transactional
    public Key revoke(Long id) {
        ApiKey k = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("API key not found: " + id));
        if (k.getRevokedAt() == null) {
            k.setRevokedAt(LocalDateTime.now());
            k.setRevokedBy(auditService.currentActorName());
            auditService.log("ApiKey", k.getId(), "REVOKE", Map.of("name", k.getName(), "key", k.getKeyPrefix() + "…"));
        }
        return toKey(k);
    }

    /**
     * Checks an Account ID and Application Key from a request. Runs before the organisation is known, so it reads
     * with plain SQL (not tenant-filtered). Empty when the pair is wrong, the key is revoked, or the organisation
     * or its subscription is not active.
     */
    public Optional<ApiKeyPrincipal> authenticate(String accountId, String applicationKey) {
        if (accountId == null || applicationKey == null || !applicationKey.startsWith(KEY_START)) return Optional.empty();
        UUID account;
        try {
            account = UUID.fromString(accountId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT k.id, k.tenant_id, k.name, k.role, k.last_used_at
                FROM api_keys k
                JOIN tenants t ON t.id = k.tenant_id
                LEFT JOIN subscriptions s ON s.tenant_id = t.id
                WHERE k.key_hash = ? AND t.account_id = ? AND k.revoked_at IS NULL AND t.active
                  AND (s.id IS NULL OR s.status = 'ACTIVE')""", sha256(applicationKey.trim()), account);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        Long keyId = ((Number) row.get("id")).longValue();
        // When it was last used, to the minute (no write on every call)
        Timestamp last = (Timestamp) row.get("last_used_at");
        if (last == null || last.toLocalDateTime().isBefore(LocalDateTime.now().minusMinutes(1))) {
            jdbc.update("UPDATE api_keys SET last_used_at = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now()), keyId);
        }
        return Optional.of(new ApiKeyPrincipal(keyId, ((Number) row.get("tenant_id")).longValue(), (String) row.get("name"),
                User.Role.valueOf((String) row.get("role"))));
    }

    private static User.Role role(String role) {
        if (role == null || role.isBlank()) return User.Role.STAFF;
        try {
            User.Role r = User.Role.valueOf(role.trim().toUpperCase(Locale.ROOT));
            if (ROLES.contains(r)) return r;
        } catch (IllegalArgumentException ignored) {
            // falls through
        }
        throw new IllegalArgumentException("A key's role is ADMIN, MANAGER or STAFF");
    }

    private Tenant tenant() {
        return tenantRepository.findById(TenantContext.get())
                .orElseThrow(() -> new IllegalStateException("No organisation for this request"));
    }

    private static Key toKey(ApiKey k) {
        return new Key(k.getId(), k.getName(), k.getKeyPrefix(), k.getRole().name(), k.getCreatedBy(), k.getCreatedAt(),
                k.getLastUsedAt(), k.getRevokedAt(), k.getRevokedBy());
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
