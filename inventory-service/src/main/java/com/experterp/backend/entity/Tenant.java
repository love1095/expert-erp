package com.experterp.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
@Data
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String name;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ---- organisation settings ----

    @Column(nullable = false, length = 10)
    private String saleOrderPrefix = "SO-";

    // The number the next sale order gets (continues Cin7's series after cutover)
    @Column(nullable = false)
    private long nextSaleOrderNumber = 1;

    // Sales rep given to Shopify orders
    @Column(nullable = false, length = 100)
    private String webSalesRep = "Web Sales";

    // Sale tax rule used when neither the product nor the customer has one
    @Column(nullable = false, length = 100)
    private String defaultSaleTaxRule = "20% (VAT on Income)";

    // Default validity of a new quote, in days
    @Column(nullable = false)
    private int quoteValidDays = 30;

    @Column(nullable = false, length = 10)
    private String invoicePrefix = "INV-";

    // The number the next invoice gets (continues Cin7's series after cutover)
    @Column(nullable = false)
    private long nextInvoiceNumber = 1;

    // Every shipment creates and issues an invoice for what it shipped
    @Column(nullable = false)
    private boolean autoInvoiceOnShipment;

    @Column(nullable = false, length = 10)
    private String creditNotePrefix = "CR-";

    @Column(nullable = false)
    private long nextCreditNoteNumber = 1;

    // Purchase order numbers, e.g. PO-19074 (continues Cin7's series at cutover)
    @Column(nullable = false, length = 10)
    private String purchaseOrderPrefix = "PO-";

    @Column(nullable = false)
    private long nextPurchaseOrderNumber = 1;

    // Purchase tax rule used when neither the product nor the supplier has one
    @Column(nullable = false, length = 100)
    private String defaultPurchaseTaxRule = "20% (VAT on Expenses)";
}
