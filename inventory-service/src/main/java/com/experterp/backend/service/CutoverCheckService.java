package com.experterp.backend.service;

import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase J: the go-live check. The ERP's figures that must equal the old system's (Cin7) on the cutover day, side by
 * side on one page: stock per warehouse (units and FIFO value), what customers owe, what is owed to suppliers, the
 * orders still open, and the settings that must be right before the first day (number series, Xero start date).
 */
@Service
@RequiredArgsConstructor
public class CutoverCheckService {

    private final JdbcTemplate jdbc;
    private final SalesLedgerService salesLedgerService;
    private final PurchaseLedgerService purchaseLedgerService;
    private final AccountingExportService accountingExportService;
    private final TenantRepository tenantRepository;

    /** A figure to compare with Cin7's, and where to find Cin7's. */
    public record Figure(String key, String group, String label, BigDecimal value, boolean money, String cin7) {}

    public record Check(LocalDate asAt, List<Figure> figures, List<String> warnings) {}

    @Transactional(readOnly = true)
    public Check check() {
        Long t = TenantContext.get();
        List<Figure> f = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Stock per warehouse: units on hand and what is left in the FIFO cost layers
        jdbc.query("""
                SELECT w.id, w.name,
                       COALESCE((SELECT SUM(CASE WHEN m.type IN (%s) THEN m.quantity ELSE -m.quantity END)
                                   FROM stock_movements m WHERE m.tenant_id = w.tenant_id AND m.warehouse_id = w.id), 0) AS units,
                       COALESCE((SELECT SUM(l.remaining * l.unit_cost) FROM cost_layers l
                                  WHERE l.tenant_id = w.tenant_id AND l.warehouse_id = w.id AND l.remaining > 0), 0) AS value
                  FROM warehouses w WHERE w.tenant_id = ? ORDER BY w.name
                """.formatted(StockMovementRepository.IN_TYPES), rs -> {
            String name = rs.getString("name");
            f.add(new Figure("units-" + rs.getLong("id"), "Stock", name + ": units on hand", rs.getBigDecimal("units"), false,
                    "Inventory → Availability (location " + name + "), total On hand"));
            f.add(new Figure("value-" + rs.getLong("id"), "Stock", name + ": stock value", money(rs.getBigDecimal("value")), true,
                    "Inventory → Availability (location " + name + "), total Stock value"));
        }, t);

        // Money owed, both ways
        SalesLedgerService.AgedDebtors debtors = salesLedgerService.agedDebtors(LocalDate.now());
        f.add(new Figure("receivables", "Money", "Customers owe (less their credit)", debtors.totals().total(), true,
                "Reports → Aged Receivables (or Xero's Aged Receivables), total"));
        f.add(new Figure("debtors", "Money", "Customers owing", BigDecimal.valueOf(debtors.customers().stream()
                .filter(d -> d.total().signum() != 0).count()), false, "Aged Receivables, number of customers"));
        PurchaseLedgerService.AgedCreditors creditors = purchaseLedgerService.agedCreditors(LocalDate.now());
        f.add(new Figure("payables", "Money", "Owed to suppliers (less deposits)", creditors.totals().total(), true,
                "Reports → Aged Payables (or Xero's Aged Payables), total"));

        // Orders still open
        jdbc.query("""
                SELECT COUNT(DISTINCT o.id) AS n,
                       COALESCE(SUM((l.quantity - l.fulfilled_quantity - l.cancelled_quantity) * l.unit_price
                                    * (1 - COALESCE(l.discount_percent, 0) / 100)), 0) AS value
                  FROM sale_orders o JOIN sale_order_lines l ON l.sale_order_id = o.id
                 WHERE o.tenant_id = ? AND o.status = 'CONFIRMED'
                """, rs -> {
            f.add(new Figure("open-sales", "Orders", "Sale orders open (confirmed, not all shipped)", rs.getBigDecimal("n"), false,
                    "Sale → Sales list, filter Not fulfilled (authorised)"));
            f.add(new Figure("open-sales-value", "Orders", "Left to ship, net", money(rs.getBigDecimal("value")), true,
                    "Reports → Sales Backorder / Sale order details, not shipped"));
        }, t);
        jdbc.query("""
                SELECT COUNT(DISTINCT o.id) AS n,
                       COALESCE(SUM(GREATEST(l.quantity - l.received_quantity - l.closed_short_quantity, 0) * l.unit_cost
                                    * (1 - COALESCE(l.discount_percent, 0) / 100)), 0) AS value
                  FROM purchase_orders o JOIN purchase_order_lines l ON l.purchase_order_id = o.id
                 WHERE o.tenant_id = ? AND o.status = 'ORDERED'
                """, rs -> {
            f.add(new Figure("open-purchases", "Orders", "Purchase orders open (ordered, not all received)", rs.getBigDecimal("n"), false,
                    "Purchase → Purchases list, not received"));
            f.add(new Figure("open-purchases-value", "Orders", "Left to receive, net", money(rs.getBigDecimal("value")), true,
                    "Reports → Pending Purchase Orders, total"));
        }, t);

        // Settings for the first day
        Tenant tenant = tenantRepository.findById(t).orElseThrow();
        f.add(new Figure("next-so", "Numbers", "Next sale order: " + tenant.getSaleOrderPrefix() + tenant.getNextSaleOrderNumber(),
                BigDecimal.valueOf(tenant.getNextSaleOrderNumber()), false, "Cin7's last SO number + 1"));
        f.add(new Figure("next-inv", "Numbers", "Next invoice: " + tenant.getInvoicePrefix() + tenant.getNextInvoiceNumber(),
                BigDecimal.valueOf(tenant.getNextInvoiceNumber()), false, "Cin7's last invoice number + 1"));
        f.add(new Figure("next-po", "Numbers", "Next purchase order: " + tenant.getPurchaseOrderPrefix() + tenant.getNextPurchaseOrderNumber(),
                BigDecimal.valueOf(tenant.getNextPurchaseOrderNumber()), false, "Cin7's last PO number + 1"));

        LocalDate start = accountingExportService.settings().exportStartDate();
        if (start == null) warnings.add("No Xero export start date: set it to the cutover day (Accounting export), or the first export sends everything again");
        else if (start.isAfter(LocalDate.now().plusDays(7))) warnings.add("The Xero export start date " + start + " is more than a week away");
        Integer users2fa = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE tenant_id = ? AND active AND NOT totp_enabled", Integer.class, t);
        if (users2fa != null && users2fa > 0 && tenant.isTwoFactorRequired()) {
            warnings.add(users2fa + " users have not set up two-step sign-in yet: they will be asked at their first sign-in");
        }
        return new Check(LocalDate.now(), f, warnings);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
