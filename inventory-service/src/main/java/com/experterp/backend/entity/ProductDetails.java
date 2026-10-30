package com.experterp.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Cin7 product fields the ERP keeps for reference but does not search or filter on.
 * Stored as JSONB in products.details; empty fields are left out of the JSON.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductDetails {
    // Comma-separated
    private String tags;
    // Cin7 price tiers beyond the shop (tier 1) and trade (tier 2) prices
    private BigDecimal priceTier3;
    private BigDecimal priceTier4;
    private String supplierProductName;
    private BigDecimal supplierPrice;
    private BigDecimal weight;
    // e.g. g, kg
    private String weightUnit;
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
    // e.g. cm
    private String dimensionUnit;
    private String purchaseTaxRule;
    private String saleTaxRule;
    private String hsCode;
    private String countryOfOrigin;
    private String internalNote;
}
