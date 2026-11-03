package com.experterp.backend.service;

import com.experterp.backend.entity.Customer;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Credit control (phase G2, spec §5). A customer is on <b>credit hold</b> when:
 * <ul>
 *   <li>put on hold by hand (with a reason), or</li>
 *   <li>what they owe (issued invoices less payments and unused credit notes) plus their orders not invoiced yet
 *       (confirmed or shipped) is over their credit limit — no limit set means no limit, or</li>
 *   <li>an invoice is past its due date (their payment terms are breached).</li>
 * </ul>
 * Orders warn at entry; a held customer's order is only confirmed by a manager, with a reason (audited).
 */
@Service
@RequiredArgsConstructor
public class CreditService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PENNY = new BigDecimal("0.01");

    private final CustomerRepository customerRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public record CreditStatus(Long customerId, BigDecimal creditLimit, BigDecimal balance, BigDecimal notInvoiced,
                               BigDecimal thisOrder, BigDecimal exposure, BigDecimal available, BigDecimal overdue,
                               LocalDate oldestDueDate, Integer daysOverdue, boolean manualHold, String holdReason,
                               String heldBy, LocalDateTime heldAt, boolean held, List<String> reasons) {
        /** One line for an order screen, or null when the customer is in good standing. */
        public String warning() {
            return reasons.isEmpty() ? null : "Credit hold: " + String.join("; ", reasons);
        }
    }

    @Transactional(readOnly = true)
    public CreditStatus status(Long customerId) {
        return check(customer(customerId), BigDecimal.ZERO, null);
    }

    /**
     * The customer's standing with an order of {@code orderTotal} on top (the order itself, {@code orderId}, is
     * left out of what is not invoiced yet so it is not counted twice).
     */
    @Transactional(readOnly = true)
    public CreditStatus check(Customer c, BigDecimal orderTotal, Long orderId) {
        Long t = TenantContext.get();
        BigDecimal invoiced = money(jdbc.queryForObject("""
                SELECT COALESCE(SUM(i.total_amount - COALESCE(p.paid, 0)), 0) FROM invoices i
                LEFT JOIN (SELECT invoice_id, SUM(amount) AS paid FROM payments GROUP BY invoice_id) p ON p.invoice_id = i.id
                WHERE i.tenant_id = ? AND i.customer_id = ? AND i.type = 'SALES' AND i.kind = 'INVOICE' AND i.status IN ('ISSUED', 'PAID')""",
                BigDecimal.class, t, c.getId()));
        // Credit notes kept as credit (not applied, not paid back) lower what is owed
        BigDecimal credit = money(jdbc.queryForObject("""
                SELECT COALESCE(SUM(i.total_amount - COALESCE(p.paid, 0)), 0) FROM invoices i
                LEFT JOIN (SELECT invoice_id, SUM(amount) AS paid FROM payments GROUP BY invoice_id) p ON p.invoice_id = i.id
                WHERE i.tenant_id = ? AND i.customer_id = ? AND i.type = 'SALES' AND i.kind = 'CREDIT_NOTE' AND i.status = 'ISSUED'""",
                BigDecimal.class, t, c.getId()));
        // Applied credit notes show as payments on the invoice, so only unused credit is taken off here
        BigDecimal unusedCredit = credit.max(BigDecimal.ZERO);
        BigDecimal balance = invoiced.subtract(unusedCredit);

        BigDecimal notInvoiced = money(jdbc.queryForObject("""
                SELECT COALESCE(SUM(x.amount), 0) FROM (
                  SELECT (l.quantity - l.cancelled_quantity - l.invoiced_quantity) * COALESCE(l.unit_price, 0)
                         * (100 - l.discount_percent) / 100 * (100 + l.tax_rate) / 100 AS amount
                  FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id
                  WHERE so.tenant_id = ? AND so.customer_id = ? AND so.status IN ('CONFIRMED', 'COMPLETED')
                    AND so.id <> COALESCE(?, -1) AND l.quantity - l.cancelled_quantity - l.invoiced_quantity > 0
                  UNION ALL
                  SELECT c.quantity * c.unit_price * (100 - c.discount_percent) / 100 * (100 + c.tax_rate) / 100
                  FROM sale_order_charges c JOIN sale_orders so ON so.id = c.sale_order_id
                  WHERE so.tenant_id = ? AND so.customer_id = ? AND so.status IN ('CONFIRMED', 'COMPLETED')
                    AND so.id <> COALESCE(?, -1) AND NOT c.invoiced
                ) x""", BigDecimal.class, t, c.getId(), orderId, t, c.getId(), orderId));

        Map<String, Object> overdueRow = jdbc.queryForMap("""
                SELECT COALESCE(SUM(i.total_amount - COALESCE(p.paid, 0)), 0) AS amount, MIN(i.due_date) AS oldest FROM invoices i
                LEFT JOIN (SELECT invoice_id, SUM(amount) AS paid FROM payments GROUP BY invoice_id) p ON p.invoice_id = i.id
                WHERE i.tenant_id = ? AND i.customer_id = ? AND i.type = 'SALES' AND i.kind = 'INVOICE' AND i.status = 'ISSUED'
                  AND i.due_date < CURRENT_DATE AND i.total_amount - COALESCE(p.paid, 0) >= 0.01""", t, c.getId());
        BigDecimal overdue = money((BigDecimal) overdueRow.get("amount"));
        LocalDate oldest = overdueRow.get("oldest") == null ? null : ((Date) overdueRow.get("oldest")).toLocalDate();
        Integer days = oldest == null ? null : (int) ChronoUnit.DAYS.between(oldest, LocalDate.now());

        BigDecimal thisOrder = money(orderTotal == null ? BigDecimal.ZERO : orderTotal);
        BigDecimal exposure = balance.add(notInvoiced).add(thisOrder);
        BigDecimal limit = c.getCreditLimit() != null && c.getCreditLimit().signum() > 0 ? c.getCreditLimit() : null;
        BigDecimal available = limit == null ? null : limit.subtract(exposure);

        List<String> reasons = new ArrayList<>();
        if (c.isOnHold()) reasons.add("put on hold" + (c.getHoldReason() == null ? "" : ": " + c.getHoldReason()));
        if (limit != null && exposure.compareTo(limit) > 0) {
            reasons.add("£" + plain(exposure) + " owed and on order" + (thisOrder.signum() > 0 ? " with this order" : "")
                    + " is £" + plain(exposure.subtract(limit)) + " over the £" + plain(limit) + " credit limit");
        }
        if (overdue.compareTo(PENNY) >= 0) {
            reasons.add("£" + plain(overdue) + " overdue, the oldest since " + oldest + " (" + days + " days)");
        }
        return new CreditStatus(c.getId(), limit, balance, notInvoiced, thisOrder, exposure, available, overdue, oldest, days,
                c.isOnHold(), c.getHoldReason(), c.getHeldBy(), c.getHeldAt(), !reasons.isEmpty(), reasons);
    }

    /** Puts a customer on hold by hand, or releases them (the limit and overdue invoices still hold by themselves). */
    @Transactional
    public CreditStatus setHold(Long customerId, boolean hold, String reason) {
        Customer c = customer(customerId);
        if (hold && (reason == null || reason.isBlank())) throw new IllegalArgumentException("Say why the customer is put on hold");
        c.setOnHold(hold);
        c.setHoldReason(hold ? reason.trim() : null);
        c.setHeldBy(hold ? auditService.currentActorName() : null);
        c.setHeldAt(hold ? LocalDateTime.now() : null);
        auditService.log("CUSTOMER", customerId, hold ? "CREDIT_HOLD" : "CREDIT_RELEASE", hold ? Map.of("reason", reason.trim()) : Map.of());
        return check(c, BigDecimal.ZERO, null);
    }

    private Customer customer(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
