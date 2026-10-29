package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "sale_return_lines")
@Data
@NoArgsConstructor
public class SaleReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_return_id", nullable = false)
    private SaleReturn saleReturn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_order_line_id", nullable = false)
    private SaleOrderLine saleOrderLine;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    // true = back into sellable stock (SALE_RETURN_IN movement); false = recorded only
    @Column(nullable = false)
    private boolean restock;
}
