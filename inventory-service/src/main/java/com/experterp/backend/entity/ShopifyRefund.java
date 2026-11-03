package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A refund made in Shopify, waiting on its ERP order to be reviewed (phase 7.4; business-rules.md §5). */
@Entity
@Table(name = "shopify_refunds")
@Getter
@Setter
@NoArgsConstructor
public class ShopifyRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "sale_order_id", nullable = false)
    private Long saleOrderId;

    @Column(name = "refund_id", nullable = false, length = 100)
    private String refundId;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "other_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal otherAmount = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String note;

    // JSON: the refunded items (see V23)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String lines;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.TO_REVIEW;

    @Column(columnDefinition = "TEXT")
    private String outcome;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status { TO_REVIEW, DONE, DISMISSED }
}
