package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "invoice_sequences")
@Data
@NoArgsConstructor
public class InvoiceSequence {

    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private Long currentNumber = 0L;
}
