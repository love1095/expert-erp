package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/** A tenant's Shopify store and the Dev Dashboard app credentials used to reach it. */
@Entity
@Table(name = "shopify_connections")
@Data
@NoArgsConstructor
public class ShopifyConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    // e.g. expertsafety-dev.myshopify.com
    @Column(nullable = false)
    private String shopDomain;

    @Column(nullable = false)
    private String clientId;

    // Encrypted with SecretCipher; never returned by the API
    @Column(name = "client_secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String clientSecretEncrypted;

    // Client-credentials tokens last 24 h; cached here (encrypted) and renewed shortly before expiry
    @Column(name = "access_token_encrypted", columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    private LocalDateTime tokenExpiresAt;

    @Column(columnDefinition = "TEXT")
    private String scopes;

    private String shopName;

    @Column(length = 3)
    private String shopCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NOT_TESTED;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    private LocalDateTime lastCheckedAt;

    // ---- order import (7.2) ----

    // Warehouse that ships imported orders
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_warehouse_id")
    private Warehouse defaultWarehouse;

    // Public HTTPS address of this backend for webhooks
    @Column(length = 500)
    private String publicUrl;

    private LocalDateTime webhooksRegisteredAt;

    // Comma-separated topics registered at webhooksRegisteredAt
    @Column(columnDefinition = "TEXT")
    private String webhookTopics;

    private LocalDateTime lastImportAt;

    // ---- product sync ----

    private LocalDateTime lastProductSyncAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum Status { NOT_TESTED, CONNECTED, ERROR }
}
