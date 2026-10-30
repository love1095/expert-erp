package com.experterp.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money on sale lines and charges, the same everywhere (orders, dashboard, invoices):
 * net = quantity × price × (1 − discount %), VAT = net × rate %, each rounded half-up to the penny per line.
 */
public final class SaleAmounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SaleAmounts() {}

    public static BigDecimal net(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent) {
        if (unitPrice == null) return BigDecimal.ZERO.setScale(2);
        BigDecimal gross = quantity.multiply(unitPrice);
        BigDecimal discount = discountPercent == null ? BigDecimal.ZERO : discountPercent;
        return gross.multiply(HUNDRED.subtract(discount)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal tax(BigDecimal net, BigDecimal ratePercent) {
        if (ratePercent == null) return BigDecimal.ZERO.setScale(2);
        return net.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** Margin % of the net amount over the cost, or null when there is no cost or no sale value. */
    public static BigDecimal marginPercent(BigDecimal net, BigDecimal quantity, BigDecimal unitCost) {
        if (unitCost == null || net.signum() == 0) return null;
        BigDecimal cost = quantity.multiply(unitCost);
        return net.subtract(cost).multiply(HUNDRED).divide(net, 2, RoundingMode.HALF_UP);
    }
}
