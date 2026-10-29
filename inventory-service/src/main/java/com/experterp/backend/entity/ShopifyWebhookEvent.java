package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/** A webhook as Shopify sent it, stored before processing. */
@Entity
@Table(name = "shopify_webhook_events")
@Data
@NoArgsConstructor
public class ShopifyWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    // X-Shopify-Event-Id (same for every delivery of one event), else X-Shopify-Webhook-Id
    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false)
    private String shopDomain;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RECEIVED;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    private LocalDateTime processedAt;

    public enum Status { RECEIVED, PROCESSED, IGNORED, FAILED }
}
