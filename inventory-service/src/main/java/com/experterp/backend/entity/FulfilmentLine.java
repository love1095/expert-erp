package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "fulfilment_lines")
@Data
@NoArgsConstructor
public class FulfilmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfilment_id", nullable = false)
    private Fulfilment fulfilment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_order_line_id", nullable = false)
    private SaleOrderLine saleOrderLine;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;
}
