package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.STAFF;

    @Column(name = "tenant_id")
    private Long tenantId;

    // Phase I1: the organisation's role giving this user's permissions; none = the defaults of the role above
    @Column(name = "access_role_id")
    private Long accessRoleId;

    // Phase I5: two-step sign-in (authenticator app); the secret is encrypted
    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "totp_last_step")
    private Long totpLastStep;

    @Column(name = "totp_failed", nullable = false)
    private int totpFailed;

    @Column(name = "totp_locked_until")
    private LocalDateTime totpLockedUntil;

    @Column(name = "totp_recovery")
    private String totpRecovery;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Role { SUPER_ADMIN, ADMIN, MANAGER, STAFF }
}
