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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer's sales ledger (phase G3, spec §5), the two statements Cin7 prints, and overdue reminders.
 * <p>
 * The ledger: issued invoices (debit), issued credit notes (credit), money received on invoices (credit) and refunds
 * paid on credit notes (debit). A credit note applied to an invoice is the credit note itself; the "payment" it makes
 * on the invoice is not counted again. Cancelled and draft documents are left out.
 * <ul>
 *   <li><b>Activity</b> statement: opening balance, every entry in the period with a running balance, closing balance.</li>
 *   <li><b>Outstanding</b> statement: what is still open on the statement date (unpaid invoices, unused credit).</li>
 * </ul>
 * Both end with the ageing of what is open on the statement date, which adds up to the closing balance.
 */
@Service
@RequiredArgsConstructor
public class StatementService {

    private static final BigDecimal PENNY = new BigDecimal("0.01");
    /** After the second reminder every further one is the final notice. */
    public static final int FINAL_LEVEL = 3;

    private final CustomerRepository customerRepository;
    private final JdbcTemplate jdbc;

    public enum Type { ACTIVITY, OUTSTANDING }

    /** One ledger line. {@code kind}: INVOICE, CREDIT_NOTE, PAYMENT, REFUND. */
    public record Entry(LocalDate date, String kind, Long invoiceId, String number, String reference, LocalDate dueDate,
                        BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    /** An invoice not fully paid, or a credit note not fully used, on a date. Credit is negative in {@code open}. */
    public record OpenItem(Long invoiceId, String kind, String number, LocalDate date, LocalDate dueDate, BigDecimal total,
                           BigDecimal settled, BigDecimal open, int daysOverdue, int reminderCount, LocalDateTime lastReminderAt) {
        public boolean overdue() {
            return "INVOICE".equals(kind) && daysOverdue > 0;
        }
    }

    /** What is open, by days past its due date (credit counts as current). */
    public record Ageing(BigDecimal current, BigDecimal days1to30, BigDecimal days31to60, BigDecimal days61to90,
                         BigDecimal over90, BigDecimal total) {}

    public record Statement(Type type, Long customerId, String customerName, LocalDate from, LocalDate to,
                            BigDecimal opening, List<Entry> entries, BigDecimal closing, List<OpenItem> openItems, Ageing ageing) {}

    public record OverdueCustomer(Long customerId, String customerName, BigDecimal overdue, int invoices, LocalDate oldestDue,
                                  int daysOverdue, BigDecimal balance, int nextLevel, LocalDateTime lastReminderAt, boolean onHold) {}

    /** A reminder: the overdue invoices and how firm it is (1 = first reminder, 2 = second, 3 = final notice). */
    public record Reminder(Long customerId, String customerName, int level, List<OpenItem> items, BigDecimal overdue,
                           BigDecimal balance, LocalDateTime previousReminderAt) {}

    // ---- statements ----

    @Transactional(readOnly = true)
    public Statement statement(Long customerId, Type type, LocalDate from, LocalDate to) {
        Customer c = customer(customerId);
        LocalDate today = LocalDate.now();
        LocalDate end = to == null || to.isAfter(today) ? today : to;
        Type t = type == null ? Type.ACTIVITY : type;
        if (t == Type.OUTSTANDING) {
            List<OpenItem> open = openItems(c.getId(), end);
            BigDecimal total = sum(open);
            return new Statement(t, c.getId(), c.getName(), null, end, null, List.of(), total, open, ageing(open));
        }
        LocalDate start = from != null ? from : defaultFrom(end);
        if (start.isAfter(end)) throw new IllegalArgumentException("The statement starts after it ends");

        BigDecimal opening = BigDecimal.ZERO.setScale(2);
        BigDecimal running = null;
        List<Entry> entries = new ArrayList<>();
        for (Entry e : ledger(c.getId(), end)) {
            BigDecimal change = e.debit().subtract(e.credit());
            if (e.date().isBefore(start)) {
                opening = opening.add(change);
                continue;
            }
            running = (running == null ? opening : running).add(change);
            entries.add(new Entry(e.date(), e.kind(), e.invoiceId(), e.number(), e.reference(), e.dueDate(), e.debit(), e.credit(), running));
        }
        if (running == null) running = opening;
        List<OpenItem> open = openItems(c.getId(), end);
        return new Statement(t, c.getId(), c.getName(), start, end, opening, entries, running, open, ageing(open));
    }

    /** First day of the month two months before the end: the last three months. */
    public static LocalDate defaultFrom(LocalDate end) {
        return end.minusMonths(2).withDayOfMonth(1);
    }

    /** What the customer owes today: unpaid invoices less unused credit (shared with credit control, G2). */
    @Transactional(readOnly = true)
    public BigDecimal balance(Long customerId) {
        return sum(openItems(customerId, LocalDate.now()));
    }

    // ---- overdue reminders ----

    /** Every customer with an invoice past its due date, most overdue first. */
    @Transactional(readOnly = true)
    public List<OverdueCustomer> overdueCustomers() {
        Long t = TenantContext.get();
        return jdbc.query("""
                SELECT c.id, c.name, c.on_hold, SUM(x.open) AS overdue, COUNT(*) AS invoices, MIN(x.due_date) AS oldest,
                       MAX(x.reminder_count) AS reminders, MAX(x.last_reminder_at) AS last_reminder
                FROM (
                  SELECT i.customer_id, i.due_date, i.reminder_count, i.last_reminder_at,
                         i.total_amount - COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.invoice_id = i.id), 0) AS open
                  FROM invoices i
                  WHERE i.tenant_id = ? AND i.customer_id IS NOT NULL AND i.type = 'SALES' AND i.kind = 'INVOICE'
                    AND i.status = 'ISSUED' AND i.due_date < CURRENT_DATE
                ) x JOIN customers c ON c.id = x.customer_id
                WHERE x.open >= 0.01
                GROUP BY c.id, c.name, c.on_hold
                ORDER BY MIN(x.due_date), c.name""", (rs, n) -> {
            LocalDate oldest = rs.getDate("oldest").toLocalDate();
            Timestamp last = rs.getTimestamp("last_reminder");
            long id = rs.getLong("id");
            return new OverdueCustomer(id, rs.getString("name"), money(rs.getBigDecimal("overdue")), rs.getInt("invoices"),
                    oldest, (int) ChronoUnit.DAYS.between(oldest, LocalDate.now()), balance(id),
                    Math.min(FINAL_LEVEL, rs.getInt("reminders") + 1), last == null ? null : last.toLocalDateTime(),
                    rs.getBoolean("on_hold"));
        }, t);
    }

    /** The reminder the customer would get now; refused when nothing is overdue. */
    @Transactional(readOnly = true)
    public Reminder reminder(Long customerId) {
        Customer c = customer(customerId);
        List<OpenItem> overdue = openItems(c.getId(), LocalDate.now()).stream().filter(OpenItem::overdue).toList();
        if (overdue.isEmpty()) throw new IllegalStateException(c.getName() + " has no overdue invoices");
        int level = Math.min(FINAL_LEVEL, overdue.stream().mapToInt(OpenItem::reminderCount).max().orElse(0) + 1);
        LocalDateTime previous = overdue.stream().map(OpenItem::lastReminderAt).filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
        return new Reminder(c.getId(), c.getName(), level, overdue, sum(overdue), balance(c.getId()), previous);
    }

    /** Records that the reminder was sent: each invoice on it counts one more reminder. */
    @Transactional
    public void recordReminder(Reminder r) {
        for (OpenItem i : r.items()) {
            jdbc.update("UPDATE invoices SET reminder_count = reminder_count + 1, last_reminder_at = ? WHERE id = ? AND tenant_id = ?",
                    Timestamp.valueOf(LocalDateTime.now()), i.invoiceId(), TenantContext.get());
        }
    }

    // ---- the ledger ----

    /** Every entry up to and including {@code to}, oldest first (documents before the money on the same day). */
    List<Entry> ledger(Long customerId, LocalDate to) {
        Long t = TenantContext.get();
        return jdbc.query("""
                SELECT * FROM (
                  SELECT COALESCE(i.issue_date, CAST(i.created_at AS date)) AS d, 1 AS o, i.kind AS k, i.id AS invoice_id,
                         i.invoice_number AS num, NULL AS ref, i.due_date AS due, i.id AS sort_id,
                         CASE WHEN i.kind = 'INVOICE' THEN i.total_amount ELSE 0 END AS debit,
                         CASE WHEN i.kind = 'CREDIT_NOTE' THEN i.total_amount ELSE 0 END AS credit
                  FROM invoices i
                  WHERE i.tenant_id = ? AND i.customer_id = ? AND i.type = 'SALES' AND i.status IN ('ISSUED', 'PAID')
                  UNION ALL
                  SELECT CAST(p.paid_at AS date), 2, CASE WHEN i.kind = 'INVOICE' THEN 'PAYMENT' ELSE 'REFUND' END, i.id,
                         i.invoice_number, COALESCE(p.reference, p.method), NULL, p.id,
                         CASE WHEN i.kind = 'CREDIT_NOTE' THEN p.amount ELSE 0 END,
                         CASE WHEN i.kind = 'INVOICE' THEN p.amount ELSE 0 END
                  FROM payments p JOIN invoices i ON i.id = p.invoice_id
                  WHERE i.tenant_id = ? AND i.customer_id = ? AND i.type = 'SALES' AND i.status IN ('ISSUED', 'PAID')
                    AND p.method NOT IN ('CREDIT_NOTE', 'CUSTOMER_CREDIT')
                  UNION ALL
                  -- H1: money received on account (overpayments, prepayments); using it on an invoice is not counted again
                  SELECT cc.received_on, 3, 'CREDIT', NULL, 'On account', COALESCE(cc.reference, cc.note), NULL, cc.id, 0, cc.amount
                  FROM customer_credits cc WHERE cc.tenant_id = ? AND cc.customer_id = ?
                ) x WHERE x.d <= ?
                ORDER BY x.d, x.o, x.sort_id""", (rs, n) -> new Entry(rs.getDate("d").toLocalDate(), rs.getString("k"),
                (Long) rs.getObject("invoice_id", Long.class), rs.getString("num"), rs.getString("ref"), date(rs.getDate("due")),
                money(rs.getBigDecimal("debit")), money(rs.getBigDecimal("credit")), null),
                t, customerId, t, customerId, t, customerId, Date.valueOf(to));
    }

    /**
     * What is open on {@code on}: invoices less what was paid on them by then (money or credit notes), credit notes
     * less what was refunded or applied by then.
     */
    List<OpenItem> openItems(Long customerId, LocalDate on) {
        Long t = TenantContext.get();
        Date d = Date.valueOf(on);
        List<OpenItem> items = jdbc.query("""
                SELECT i.id, i.kind, i.invoice_number, COALESCE(i.issue_date, CAST(i.created_at AS date)) AS d, i.due_date,
                       i.total_amount, i.reminder_count, i.last_reminder_at,
                       COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.invoice_id = i.id AND CAST(p.paid_at AS date) <= ?), 0)
                       + CASE WHEN i.kind = 'CREDIT_NOTE' THEN COALESCE((SELECT SUM(p.amount) FROM payments p
                              WHERE p.credit_note_id = i.id AND p.method = 'CREDIT_NOTE' AND CAST(p.paid_at AS date) <= ?), 0)
                         ELSE 0 END AS settled
                FROM invoices i
                WHERE i.tenant_id = ? AND i.customer_id = ? AND i.type = 'SALES' AND i.status IN ('ISSUED', 'PAID')
                  AND COALESCE(i.issue_date, CAST(i.created_at AS date)) <= ?
                ORDER BY COALESCE(i.due_date, i.issue_date, CAST(i.created_at AS date)), i.id""", (rs, n) -> {
            boolean credit = "CREDIT_NOTE".equals(rs.getString("kind"));
            BigDecimal total = money(rs.getBigDecimal("total_amount"));
            BigDecimal settled = money(rs.getBigDecimal("settled"));
            BigDecimal open = total.subtract(settled);
            LocalDate issued = rs.getDate("d").toLocalDate();
            LocalDate due = date(rs.getDate("due_date"));
            int days = credit ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(due != null ? due : issued, on));
            Timestamp last = rs.getTimestamp("last_reminder_at");
            return new OpenItem(rs.getLong("id"), rs.getString("kind"), rs.getString("invoice_number"), issued, due,
                    credit ? total.negate() : total, credit ? settled.negate() : settled, credit ? open.negate() : open,
                    days, rs.getInt("reminder_count"), last == null ? null : last.toLocalDateTime());
        }, d, d, t, customerId, d);
        // H1: customer credit not used by then (an overpayment or a payment before the invoice)
        items = new ArrayList<>(items);
        items.addAll(jdbc.query("""
                SELECT cc.id, cc.received_on, cc.reference, cc.note, cc.amount,
                       COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.customer_credit_id = cc.id AND CAST(p.paid_at AS date) <= ?), 0) AS used
                FROM customer_credits cc WHERE cc.tenant_id = ? AND cc.customer_id = ? AND cc.received_on <= ?
                ORDER BY cc.received_on, cc.id""", (rs, n) -> {
            BigDecimal amount = money(rs.getBigDecimal("amount"));
            BigDecimal used = money(rs.getBigDecimal("used"));
            String ref = rs.getString("reference");
            return new OpenItem(null, "CREDIT", ref != null ? "On account " + ref : "On account", rs.getDate("received_on").toLocalDate(),
                    null, amount.negate(), used.negate(), used.subtract(amount), 0, 0, null);
        }, d, t, customerId, d));
        return items.stream().filter(i -> i.open().abs().compareTo(PENNY) >= 0).toList();
    }

    static Ageing ageing(List<OpenItem> open) {
        BigDecimal[] b = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (OpenItem i : open) {
            int days = i.daysOverdue();
            int k = days <= 0 ? 0 : days <= 30 ? 1 : days <= 60 ? 2 : days <= 90 ? 3 : 4;
            b[k] = b[k].add(i.open());
        }
        return new Ageing(money(b[0]), money(b[1]), money(b[2]), money(b[3]), money(b[4]), sum(open));
    }

    private static BigDecimal sum(List<OpenItem> items) {
        return money(items.stream().map(OpenItem::open).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private Customer customer(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }
}
