package com.experterp.backend.integration.shopify;

import com.experterp.backend.service.SaleAmounts;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shopify prices include VAT (business-rules.md §5); ERP lines hold a net price and add VAT per line, rounded to
 * the penny. This finds the net unit price whose net + VAT is what Shopify charged for the line. About one gross
 * amount in six cannot be reached that way (VAT rounding skips it): then the line comes out 1p under, and that
 * penny is returned as {@code shortBy} so the order can make it up.
 */
final class VatIncluded {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PENNY = new BigDecimal("0.01");

    record Split(BigDecimal unitNet, BigDecimal net, BigDecimal shortBy) {}

    private VatIncluded() {}

    static Split split(BigDecimal gross, BigDecimal quantity, BigDecimal ratePercent) {
        BigDecimal rate = ratePercent == null ? BigDecimal.ZERO : ratePercent;
        BigDecimal target = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal ideal = target.multiply(HUNDRED).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP);

        // Nearest net (in pennies around the ideal) with net + VAT = gross; else the nearest 1p under
        BigDecimal exact = null, under = null;
        for (int step = 0; step <= 3 && exact == null; step++) {
            for (int sign : step == 0 ? new int[]{0} : new int[]{-1, 1}) {
                BigDecimal n = ideal.add(PENNY.multiply(BigDecimal.valueOf((long) sign * step)));
                if (n.signum() < 0) continue;
                BigDecimal sum = n.add(SaleAmounts.tax(n, rate));
                if (sum.compareTo(target) == 0 && exact == null) exact = n;
                if (sum.compareTo(target.subtract(PENNY)) == 0 && under == null) under = n;
            }
        }
        BigDecimal net = exact != null ? exact : under != null ? under : ideal;
        BigDecimal unit = quantity.signum() == 0 ? BigDecimal.ZERO : net.divide(quantity, 4, RoundingMode.HALF_UP);
        // With many units the 4-decimal unit price may not give back the net exactly: use what it gives
        BigDecimal actualNet = SaleAmounts.net(quantity, unit, BigDecimal.ZERO);
        BigDecimal shortBy = target.subtract(actualNet.add(SaleAmounts.tax(actualNet, rate)));
        return new Split(unit, actualNet, shortBy);
    }
}
