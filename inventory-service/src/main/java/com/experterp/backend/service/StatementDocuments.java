package com.experterp.backend.service;

import com.experterp.backend.service.DocumentModel.Company;
import com.experterp.backend.service.DocumentModel.Field;
import com.experterp.backend.service.DocumentModel.Party;
import com.experterp.backend.service.DocumentModel.Total;
import com.experterp.backend.service.StatementService.Ageing;
import com.experterp.backend.service.StatementService.Entry;
import com.experterp.backend.service.StatementService.OpenItem;
import com.experterp.backend.service.StatementService.Reminder;
import com.experterp.backend.service.StatementService.Statement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Statements and overdue reminders as PDF (phase G3), printed through {@code templates/documents/statement.html} with
 * the same letterhead as the other documents. Cin7's two statements: Activity and Outstanding.
 */
@Service
@RequiredArgsConstructor
public class StatementDocuments {

    private final StatementService statementService;
    private final DocumentService documentService;
    private final AuditService auditService;
    private final PdfRenderer renderer;

    /** What the statement template prints, already formatted. */
    public record Print(String title, String date, String stamp, Company company, Party customer, List<Field> fields,
                        List<String> letter, boolean activity, List<Row> rows, String emptyText, List<Total> totals,
                        List<Total> ageing, String payment, String fileName) {}

    /**
     * A table row. Activity: a = debit, b = credit, c = balance. Outstanding / reminder: a = total, b = paid, c = open,
     * with the days overdue.
     */
    public record Row(String date, String type, String number, String reference, String due, String days,
                      String a, String b, String c, boolean overdue) {}

    // ---- statements ----

    @Transactional(readOnly = true)
    public DocumentService.Pdf statement(Long customerId, StatementService.Type type, LocalDate from, LocalDate to) {
        Print p = statementPrint(statementService.statement(customerId, type, from, to), documentService.company());
        return new DocumentService.Pdf(p.fileName(), renderer.render("statement", p, p.fileName()));
    }

    /** Ticked customers' statements in one PDF, each starting on a new page. */
    @Transactional(readOnly = true)
    public DocumentService.Pdf statements(List<Long> customerIds, StatementService.Type type, LocalDate from, LocalDate to) {
        if (customerIds == null || customerIds.isEmpty()) throw new IllegalArgumentException("Choose the customers");
        if (customerIds.size() > 100) throw new IllegalArgumentException("Print at most 100 statements at a time");
        DocumentService.Letterhead company = documentService.company();
        List<byte[]> pdfs = new ArrayList<>();
        String name = null;
        for (Long id : customerIds) {
            Print p = statementPrint(statementService.statement(id, type, from, to), company);
            pdfs.add(renderer.render("statement", p, p.fileName()));
            name = p.fileName();
        }
        if (pdfs.size() > 1) name = "statements-" + LocalDate.now() + ".pdf";
        return new DocumentService.Pdf(name, renderer.merge(pdfs));
    }

    Print statementPrint(Statement s, DocumentService.Letterhead company) {
        boolean activity = s.type() == StatementService.Type.ACTIVITY;
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Statement", activity ? "Activity" : "Outstanding items"));
        fields.add(new Field("Date", DocumentService.date(s.to())));
        if (activity) fields.add(new Field("Period", DocumentService.date(s.from()) + " to " + DocumentService.date(s.to())));
        fields.add(new Field("Balance due", money(s.closing())));
        BigDecimal overdue = overdue(s.ageing());
        if (overdue.signum() > 0) fields.add(new Field("Overdue", money(overdue)));

        List<Row> rows = new ArrayList<>();
        List<Total> totals = new ArrayList<>();
        if (activity) {
            rows.add(new Row(DocumentService.date(s.from()), "Opening balance", null, null, null, null, null, null, money(s.opening()), false));
            for (Entry e : s.entries()) {
                rows.add(new Row(DocumentService.date(e.date()), kind(e.kind()), e.number(), reference(e), DocumentService.date(e.dueDate()),
                        null, blankZero(e.debit()), blankZero(e.credit()), money(e.balance()), false));
            }
            BigDecimal debits = s.entries().stream().map(Entry::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credits = s.entries().stream().map(Entry::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
            totals.add(new Total("Opening balance", money(s.opening()), false));
            totals.add(new Total("Invoiced and refunded", money(debits), false));
            totals.add(new Total("Paid and credited", money(credits), false));
            totals.add(new Total("Closing balance", money(s.closing()), true));
        } else {
            for (OpenItem i : s.openItems()) rows.add(openRow(i));
            totals.add(new Total("Balance due", money(s.closing()), true));
        }
        String payment = s.closing().signum() > 0 ? howToPay(company, null) : null;
        String file = "Statement-" + safe(s.customerName()) + "-" + s.to() + ".pdf";
        return new Print("Statement", DocumentService.date(s.to()), null,
                company.forPrint(), documentService.customerParty("Statement for", s.customerId(), s.customerName()), fields,
                List.of(), activity, rows, activity ? "No invoices, payments or credit notes in this period"
                : "Nothing outstanding: thank you", totals, ageing(s.ageing()), payment, file);
    }

    // ---- overdue reminders ----

    /** The reminder as it would be sent now; {@code record} counts it on the invoices (and is audited). */
    @Transactional
    public DocumentService.Pdf reminder(Long customerId, boolean record) {
        Reminder r = statementService.reminder(customerId);
        Print p = reminderPrint(r, documentService.company());
        byte[] pdf = renderer.render("statement", p, p.fileName());
        if (record) {
            statementService.recordReminder(r);
            auditService.log("CUSTOMER", customerId, "OVERDUE_REMINDER", Map.of(
                    "level", r.level(), "invoices", r.items().stream().map(OpenItem::number).toList(), "overdue", r.overdue()));
        }
        return new DocumentService.Pdf(p.fileName(), pdf);
    }

    Print reminderPrint(Reminder r, DocumentService.Letterhead company) {
        String title = switch (r.level()) {
            case 1 -> "Payment reminder";
            case 2 -> "Second payment reminder";
            default -> "Final notice";
        };
        String amount = money(r.overdue());
        List<String> letter = new ArrayList<>();
        letter.add("Dear " + r.customerName() + ",");
        switch (r.level()) {
            case 1 -> {
                letter.add("Our records show that the invoices below are past their due date, " + amount + " in all.");
                letter.add("If you have already paid, thank you, and please ignore this letter. Otherwise please pay the overdue amount now.");
            }
            case 2 -> {
                letter.add("We wrote to you" + (r.previousReminderAt() != null ? " on " + DocumentService.date(r.previousReminderAt().toLocalDate()) : "")
                        + " about the overdue invoices below, and " + amount + " is still unpaid.");
                letter.add("Please pay within 7 days, or contact us if there is a problem with any of these invoices.");
            }
            default -> {
                letter.add("Despite our earlier reminders, the invoices below are still unpaid: " + amount + " overdue.");
                letter.add("Please pay in full within 7 days. Until then your account stays on hold and no further orders can be supplied on credit.");
            }
        }
        letter.add("Yours sincerely,");
        letter.add(company.name() == null ? "" : company.name());

        List<Row> rows = r.items().stream().map(this::openRow).toList();
        List<Total> totals = new ArrayList<>();
        totals.add(new Total("Overdue", amount, true));
        if (r.balance().compareTo(r.overdue()) != 0) totals.add(new Total("Total balance on your account", money(r.balance()), false));
        List<Field> fields = List.of(new Field("Date", DocumentService.date(LocalDate.now())), new Field("Overdue", amount));
        String file = title.replace(' ', '-') + "-" + safe(r.customerName()) + "-" + LocalDate.now() + ".pdf";
        return new Print(title, DocumentService.date(LocalDate.now()), null,
                company.forPrint(), documentService.customerParty("To", r.customerId(), r.customerName()), fields, letter, false,
                rows, "", totals, List.of(), howToPay(company, amount), file);
    }

    // ---- shared ----

    private Row openRow(OpenItem i) {
        boolean credit = "CREDIT_NOTE".equals(i.kind());
        return new Row(DocumentService.date(i.date()), kind(i.kind()), i.number(), null, DocumentService.date(i.dueDate()),
                credit || i.daysOverdue() <= 0 ? null : String.valueOf(i.daysOverdue()),
                money(i.total()), blankZero(i.settled().abs()), money(i.open()), i.overdue());
    }

    private static List<Total> ageing(Ageing a) {
        return List.of(new Total("Current", money(a.current()), false), new Total("1-30 days", money(a.days1to30()), false),
                new Total("31-60 days", money(a.days31to60()), false), new Total("61-90 days", money(a.days61to90()), false),
                new Total("Over 90 days", money(a.over90()), false), new Total("Total", money(a.total()), true));
    }

    private static BigDecimal overdue(Ageing a) {
        return a.days1to30().add(a.days31to60()).add(a.days61to90()).add(a.over90());
    }

    private static String howToPay(DocumentService.Letterhead company, String amount) {
        StringBuilder sb = new StringBuilder(amount != null ? "Please pay " + amount + ", " : "Please pay ")
                .append("quoting the invoice numbers.");
        if (company.bankDetails() != null && !company.bankDetails().isBlank()) sb.append('\n').append(company.bankDetails());
        return sb.toString();
    }

    private static String kind(String k) {
        return switch (k) {
            case "INVOICE" -> "Invoice";
            case "CREDIT_NOTE" -> "Credit note";
            case "PAYMENT" -> "Payment";
            case "REFUND" -> "Refund";
            case "CREDIT" -> "Payment on account";
            default -> k;
        };
    }

    private static String reference(Entry e) {
        if (e.reference() == null) return null;
        return switch (e.reference()) {
            case "BANK" -> "Bank";
            case "CASH" -> "Cash";
            case "STRIPE" -> "Card";
            case "SHOPIFY" -> "Shopify";
            default -> e.reference();
        };
    }

    private static String money(BigDecimal v) {
        return DocumentService.money(v);
    }

    private static String blankZero(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : money(v);
    }

    private static String safe(String name) {
        String s = name == null ? "customer" : name.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "customer" : s;
    }
}
