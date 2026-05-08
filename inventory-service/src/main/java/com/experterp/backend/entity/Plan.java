package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Table(name = "plans")
@Data
@NoArgsConstructor
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(unique = true, nullable = false)
    private PlanTier tier;

    @Column(nullable = false)
    private String displayName;

    /** -1 means unlimited */
    @Column(nullable = false)
    private int maxProducts;

    /** -1 means unlimited */
    @Column(nullable = false)
    private int maxUsers;

    /** -1 means unlimited */
    @Column(nullable = false)
    private int maxWarehouses;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    public enum PlanTier { FREE, PRO, ENTERPRISE }
}
