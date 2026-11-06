package com.experterp.backend.service;

import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Invoice;
import com.experterp.backend.entity.SaleOrder;
import com.experterp.backend.integration.mail.MailSender;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.security.Permission;
import com.experterp.backend.security.SecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase M (spec §7): e-mail from the ERP — quotes, sales orders, invoices, credit notes, statements, overdue reminders,
 * purchase orders (and, with the couriers of phase O, tracking). Each document type has an editable subject and body
 * with placeholders ({{customer}}, {{number}}, {{amount}}, {{due_date}}…); the document's PDF (I4 templates, G3
 * statements and reminders) is attached; the recipient is the customer's default contact (or first with an e-mail) or
 * the supplier's e-mail. Every e-mail is kept in the outbox — the fake mode stops there, live sends it by SMTP — which
 * is also each document's "sent" log.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private static final DateTimeFormatter UK = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$");

    private final JdbcTemplate jdbc;
    private final MailSender sender;
    private final SecretCipher cipher;
    private final DocumentService documents;
    private final StatementDocuments statementDocuments;
    private final StatementService statements;
    private final SaleOrderService saleOrderService;
    private final InvoiceService invoiceService;
    private final PurchaseOrderService purchaseOrderService;
    private final AuditService auditService;

    /** What can be e-mailed: subject / body per type. */
    public enum DocType { QUOTE, SALES_ORDER, INVOICE, CREDIT_NOTE, STATEMENT, REMINDER_1, REMINDER_2, REMINDER_3, PURCHASE_ORDER, TRACKING }

    // ---- settings (M1) ----

    public record Settings(String mode, String fromName, String fromAddress, String replyTo, String smtpHost, Integer smtpPort,
                           String smtpUsername, boolean smtpPasswordSet, boolean smtpStartTls) {}

    public record SettingsRequest(String fromName, String fromAddress, String replyTo, String smtpHost, Integer smtpPort, String smtpUsername,
                                  String smtpPassword, Boolean smtpStartTls) {}

    @Transactional(readOnly = true)
    public Settings settings() {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM mail_settings WHERE tenant_id = ?", TenantContext.get());
        if (r.isEmpty()) return new Settings(sender.mode(), null, null, null, null, null, null, false, true);
        Map<String, Object> m = r.get(0);
        return new Settings(sender.mode(), (String) m.get("from_name"), (String) m.get("from_address"), (String) m.get("reply_to"),
                (String) m.get("smtp_host"), (Integer) m.get("smtp_port"), (String) m.get("smtp_username"), m.get("smtp_password_encrypted") != null,
                Boolean.TRUE.equals(m.get("smtp_starttls")));
    }

    /** The sender address and SMTP server; the password is kept encrypted and only replaced when a new one is given. */
    @Transactional
    public Settings updateSettings(SettingsRequest s) {
        requireAdmin();
        if (s.fromAddress() != null && !s.fromAddress().isBlank() && !EMAIL.matcher(s.fromAddress().trim()).matches()) {
            throw new IllegalArgumentException("The sender address is not an e-mail address");
        }
        Long t = TenantContext.get();
        jdbc.update("INSERT INTO mail_settings (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING", t);
        jdbc.update("""
                UPDATE mail_settings SET from_name = ?, from_address = ?, reply_to = ?, smtp_host = ?, smtp_port = ?, smtp_username = ?,
                       smtp_starttls = ?, updated_at = now(), updated_by = ? WHERE tenant_id = ?""",
                blank(s.fromName()), blank(s.fromAddress()), blank(s.replyTo()), blank(s.smtpHost()), s.smtpPort(), blank(s.smtpUsername()),
                s.smtpStartTls() == null || s.smtpStartTls(), auditService.currentActorName(), t);
        if (s.smtpPassword() != null && !s.smtpPassword().isBlank()) {
            jdbc.update("UPDATE mail_settings SET smtp_password_encrypted = ? WHERE tenant_id = ?", cipher.encrypt(s.smtpPassword()), t);
        }
        auditService.log("MAIL", null, "SETTINGS", Map.of("from", String.valueOf(blank(s.fromAddress())), "smtp", String.valueOf(blank(s.smtpHost()))));
        return settings();
    }

    // ---- templates (M2) ----

    public record Template(DocType docType, String subject, String body, boolean custom) {}

    @Transactional(readOnly = true)
    public List<Template> templates() {
        Map<String, Map<String, Object>> custom = new HashMap<>();
        jdbc.queryForList("SELECT * FROM mail_templates WHERE tenant_id = ?", TenantContext.get()).forEach(r -> custom.put((String) r.get("doc_type"), r));
        List<Template> out = new ArrayList<>();
        for (DocType d : DocType.values()) {
            Map<String, Object> c = custom.get(d.name());
            String[] def = DEFAULTS.get(d);
            out.add(c == null ? new Template(d, def[0], def[1], false) : new Template(d, (String) c.get("subject"), (String) c.get("body"), true));
        }
        return out;
    }

    @Transactional
    public Template saveTemplate(DocType type, String subject, String body) {
        requireAdmin();
        if (subject == null || subject.isBlank() || body == null || body.isBlank()) throw new IllegalArgumentException("The subject and the text are required");
        jdbc.update("""
                INSERT INTO mail_templates (tenant_id, doc_type, subject, body, updated_by) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, doc_type) DO UPDATE SET subject = EXCLUDED.subject, body = EXCLUDED.body, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                TenantContext.get(), type.name(), subject.trim(), body, auditService.currentActorName());
        auditService.log("MAIL", null, "TEMPLATE", Map.of("type", type.name()));
        return template(type);
    }

    /** Back to the standard wording. */
    @Transactional
    public Template resetTemplate(DocType type) {
        requireAdmin();
        jdbc.update("DELETE FROM mail_templates WHERE tenant_id = ? AND doc_type = ?", TenantContext.get(), type.name());
        return template(type);
    }

    private Template template(DocType type) {
        return templates().stream().filter(t -> t.docType() == type).findFirst().orElseThrow();
    }

    /** {{name}} placeholders filled; unknown ones left as they are, so a typo shows. */
    static String fill(String text, Map<String, String> values) {
        Matcher m = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v != null ? v : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---- composing and sending (M3) ----

    /** What to e-mail: a document (id) or a customer (statement / reminder). */
    public record Request(String kind, Long id, String statementType, LocalDate from, LocalDate to, String recipient, String cc, String subject, String body) {
        public Request(String kind, Long id) { this(kind, id, null, null, null, null, null, null, null); }
    }

    /** The e-mail as it would go: recipient, subject and text filled in, the PDF it carries. */
    public record Draft(DocType docType, String docKey, String docLabel, String to, String cc, String subject, String body, String attachmentName,
                        String missingRecipient) {}

    public record Sent(Long id, DocType docType, String docKey, String docLabel, String to, String cc, String subject, String body,
                       String attachmentName, String mode, String status, String error, LocalDateTime createdAt, String createdBy) {}

    private record Composed(Draft draft, java.util.function.Supplier<DocumentService.Pdf> pdf, Runnable afterSending) {}

    @Transactional
    public Draft preview(Request r) {
        return compose(r).draft();
    }

    /** Sends one e-mail (the draft's text unless the request gives other words). A missing e-mail address is refused clearly. */
    @Transactional
    public Sent send(Request r) {
        Composed c = compose(r);
        Draft d = c.draft();
        String to = r.recipient() != null && !r.recipient().isBlank() ? r.recipient().trim() : d.to();
        if (to == null || to.isBlank()) throw new IllegalArgumentException(d.missingRecipient());
        for (String a : to.split("[,;]")) {
            if (!EMAIL.matcher(a.trim()).matches()) throw new IllegalArgumentException("\"" + a.trim() + "\" is not an e-mail address");
        }
        String cc = r.cc() != null && !r.cc().isBlank() ? r.cc().trim() : null;
        String subject = r.subject() != null && !r.subject().isBlank() ? r.subject() : d.subject();
        String body = r.body() != null && !r.body().isBlank() ? r.body() : d.body();
        DocumentService.Pdf pdf = c.pdf().get();
        Settings s = settings();
        if (s.fromAddress() == null) throw new IllegalStateException("Set the sender address first (Settings → E-mail)");
        String status = "KEPT", error = null;
        try {
            sender.send(new MailSender.Mail(s.fromName(), s.fromAddress(), s.replyTo(), to, cc, subject, body, pdf == null ? null : pdf.fileName(),
                    pdf == null ? null : pdf.bytes()), smtp());
            status = "fake".equals(sender.mode()) ? "KEPT" : "SENT";
        } catch (IllegalStateException e) {
            status = "FAILED";
            error = e.getMessage();
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO mail_outbox (tenant_id, doc_type, doc_key, doc_label, to_address, cc_address, subject, body, attachment_name, attachment,
                                         mode, status, error, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, TenantContext.get(), d.docType().name(), d.docKey(),
                cut(d.docLabel(), 255), cut(to, 500), cut(cc, 500), cut(subject, 500), body, pdf == null ? null : pdf.fileName(),
                pdf == null ? null : pdf.bytes(), sender.mode(), status, error, auditService.currentActorName());
        if (!"FAILED".equals(status) && c.afterSending() != null) c.afterSending().run();
        auditService.log("MAIL", id, "SEND", Map.of("type", d.docType().name(), "doc", d.docKey(), "to", to, "status", status));
        return outbox(null, 1, id).get(0);
    }

    public record BulkResult(Long customerId, String customer, String status, String message) {}

    /** Statements to many customers at once; those without an e-mail address are listed, not stopped on. */
    @Transactional
    public List<BulkResult> sendStatements(List<Long> customerIds, String type, LocalDate from, LocalDate to) {
        return bulk(customerIds, id -> new Request("STATEMENT", id, type, from, to, null, null, null, null));
    }

    /** Overdue reminders (G3: first, second, final notice by how many each invoice has had), recorded on the invoices. */
    @Transactional
    public List<BulkResult> sendReminders(List<Long> customerIds) {
        List<Long> ids = customerIds == null || customerIds.isEmpty()
                ? statements.overdueCustomers().stream().map(StatementService.OverdueCustomer::customerId).toList() : customerIds;
        return bulk(ids, id -> new Request("REMINDER", id));
    }

    private List<BulkResult> bulk(List<Long> ids, java.util.function.Function<Long, Request> request) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Choose the customers");
        if (ids.size() > 500) throw new IllegalArgumentException("At most 500 customers at a time");
        List<BulkResult> out = new ArrayList<>();
        for (Long id : ids) {
            String name = jdbc.queryForList("SELECT name FROM customers WHERE tenant_id = ? AND id = ?", String.class, TenantContext.get(), id)
                    .stream().findFirst().orElse("Customer " + id);
            try {
                Sent s = send(request.apply(id));
                out.add(new BulkResult(id, name, s.status(), s.error()));
            } catch (RuntimeException e) {
                out.add(new BulkResult(id, name, "SKIPPED", e.getMessage()));
            }
        }
        return out;
    }

    private Composed compose(Request r) {
        String kind = r.kind() == null ? "" : r.kind().toUpperCase(Locale.ROOT);
        Map<String, String> v = new HashMap<>();
        DocumentService.Letterhead company = documents.company();
        v.put("company", company.name());
        return switch (kind) {
            case "SALE_ORDER", "QUOTE", "SALES_ORDER" -> {
                require(Permission.SALE_EDIT);
                SaleOrderResponse so = saleOrderService.findById(r.id());
                boolean quote = so.getStatus() == SaleOrder.Status.QUOTE;
                v.put("customer", so.getCustomerName());
                v.put("number", so.getOrderNumber());
                v.put("amount", money(so.getTotal()));
                v.put("reference", nn(so.getCustomerReference()));
                v.put("order_number", so.getOrderNumber());
                DocType t = quote ? DocType.QUOTE : DocType.SALES_ORDER;
                yield composed(t, "SO:" + so.getId(), (quote ? "Quote " : "Sales order ") + so.getOrderNumber(), customerEmail(so.getCustomerId()),
                        so.getCustomerName(), v, () -> documents.saleOrder(r.id()), null);
            }
            case "INVOICE", "CREDIT_NOTE" -> {
                require(Permission.SALE_EDIT);
                InvoiceResponse inv = invoiceService.findById(r.id());
                if (inv.getType() != Invoice.InvoiceType.SALES) throw new IllegalArgumentException("Supplier bills are not e-mailed from here");
                if (inv.getStatus() == Invoice.InvoiceStatus.DRAFT) throw new IllegalStateException("Issue the invoice before e-mailing it");
                boolean credit = inv.getKind() == Invoice.Kind.CREDIT_NOTE;
                v.put("customer", inv.getPartyName());
                v.put("number", inv.getInvoiceNumber());
                v.put("amount", money(inv.getTotalAmount()));
                v.put("amount_due", money(inv.getTotalAmount().subtract(inv.getAmountPaid() == null ? BigDecimal.ZERO : inv.getAmountPaid())));
                v.put("due_date", inv.getDueDate() == null ? "" : inv.getDueDate().format(UK));
                v.put("date", inv.getIssueDate() == null ? "" : inv.getIssueDate().format(UK));
                DocType t = credit ? DocType.CREDIT_NOTE : DocType.INVOICE;
                yield composed(t, "DOC:" + inv.getId(), (credit ? "Credit note " : "Invoice ") + inv.getInvoiceNumber(), customerEmail(inv.getCustomerId()),
                        inv.getPartyName(), v, () -> documents.invoice(r.id()), null);
            }
            case "PURCHASE_ORDER" -> {
                require(Permission.PURCHASE_EDIT);
                PurchaseOrderResponse po = purchaseOrderService.findById(r.id());
                v.put("supplier", po.getSupplierName());
                v.put("number", po.getOrderNumber());
                v.put("amount", po.getTotal() == null ? "" : money(po.getTotal()));
                yield composed(DocType.PURCHASE_ORDER, "PO:" + po.getId(), "Purchase order " + po.getOrderNumber(), supplierEmail(po.getSupplierId()),
                        po.getSupplierName(), v, () -> documents.purchaseOrder(r.id()), null);
            }
            case "STATEMENT" -> {
                require(Permission.CUSTOMERS);
                StatementService.Type type = r.statementType() == null ? StatementService.Type.OUTSTANDING : StatementService.Type.valueOf(r.statementType().toUpperCase(Locale.ROOT));
                StatementService.Statement st = statements.statement(r.id(), type, r.from(), r.to());
                v.put("customer", st.customerName());
                v.put("amount", money(st.closing()));
                v.put("date", st.to().format(UK));
                yield composed(DocType.STATEMENT, "CUST:" + r.id(), "Statement " + st.customerName(), customerEmail(r.id()), st.customerName(), v,
                        () -> statementDocuments.statement(r.id(), type, r.from(), r.to()), null);
            }
            case "REMINDER" -> {
                require(Permission.CUSTOMER_CREDIT);
                StatementService.Reminder rem = statements.reminder(r.id());
                v.put("customer", rem.customerName());
                v.put("amount", money(rem.overdue()));
                v.put("balance", money(rem.balance()));
                v.put("invoices", String.join(", ", rem.items().stream().map(StatementService.OpenItem::number).toList()));
                DocType t = rem.level() <= 1 ? DocType.REMINDER_1 : rem.level() == 2 ? DocType.REMINDER_2 : DocType.REMINDER_3;
                // Counted on the invoices (G3) only once the e-mail has gone
                yield composed(t, "CUST:" + r.id(), (rem.level() >= 3 ? "Final notice " : "Payment reminder ") + rem.customerName(), customerEmail(r.id()),
                        rem.customerName(), v, () -> statementDocuments.reminder(r.id(), false), () -> {
                            statements.recordReminder(rem);
                            auditService.log("CUSTOMER", r.id(), "OVERDUE_REMINDER", Map.of("level", rem.level(), "by", "e-mail", "overdue", rem.overdue()));
                        });
            }
            default -> throw new IllegalArgumentException("Nothing to e-mail for \"" + r.kind() + "\"");
        };
    }

    private Composed composed(DocType type, String key, String label, String to, String party, Map<String, String> values,
                              java.util.function.Supplier<DocumentService.Pdf> pdf, Runnable after) {
        Template t = template(type);
        String missing = (party == null ? "The customer" : party) + " has no e-mail address: add one to "
                + (type == DocType.PURCHASE_ORDER ? "the supplier" : "a contact of the customer") + ", or type it here";
        return new Composed(new Draft(type, key, label, to, null, fill(t.subject(), values), fill(t.body(), values),
                label.replace(' ', '-') + ".pdf", to == null ? missing : null), pdf, after);
    }

    // ---- log and outbox (M4) ----

    /** Every e-mail made (newest first), or only those of one document (its "sent" log). */
    @Transactional(readOnly = true)
    public List<Sent> outbox(String docKey, int limit, Long id) {
        return jdbc.query("""
                SELECT id, doc_type, doc_key, doc_label, to_address, cc_address, subject, body, attachment_name, mode, status, error, created_at, created_by
                  FROM mail_outbox WHERE tenant_id = ? AND (?::varchar IS NULL OR doc_key = ?::varchar) AND (?::bigint IS NULL OR id = ?::bigint)
                 ORDER BY created_at DESC, id DESC LIMIT ?""", (rs, n) -> new Sent(rs.getLong(1), DocType.valueOf(rs.getString(2)), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getTimestamp(13).toLocalDateTime(), rs.getString(14)),
                TenantContext.get(), docKey, docKey, id, id, Math.max(1, Math.min(limit, 500)));
    }

    /** The PDF an e-mail carried, as it was sent. */
    @Transactional(readOnly = true)
    public DocumentService.Pdf attachment(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT attachment_name, attachment FROM mail_outbox WHERE tenant_id = ? AND id = ?", TenantContext.get(), id);
        if (r.isEmpty() || r.get(0).get("attachment") == null) throw new IllegalArgumentException("No attachment");
        return new DocumentService.Pdf((String) r.get(0).get("attachment_name"), (byte[]) r.get(0).get("attachment"));
    }

    // ---- shared ----

    /** The customer's default contact's e-mail, else the first contact with one. */
    private String customerEmail(Long customerId) {
        if (customerId == null) return null;
        return jdbc.queryForList("""
                SELECT email FROM customer_contacts WHERE tenant_id = ? AND customer_id = ? AND email IS NOT NULL AND email <> ''
                 ORDER BY is_default DESC, id LIMIT 1""", String.class, TenantContext.get(), customerId).stream().findFirst().orElse(null);
    }

    private String supplierEmail(Long supplierId) {
        if (supplierId == null) return null;
        return jdbc.queryForList("SELECT email FROM suppliers WHERE tenant_id = ? AND id = ? AND email IS NOT NULL AND email <> ''", String.class,
                TenantContext.get(), supplierId).stream().findFirst().orElse(null);
    }

    private MailSender.Smtp smtp() {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM mail_settings WHERE tenant_id = ?", TenantContext.get());
        if (r.isEmpty()) return null;
        Map<String, Object> m = r.get(0);
        String pw = (String) m.get("smtp_password_encrypted");
        return new MailSender.Smtp((String) m.get("smtp_host"), m.get("smtp_port") == null ? 587 : (Integer) m.get("smtp_port"),
                (String) m.get("smtp_username"), pw == null ? null : cipher.decrypt(pw), Boolean.TRUE.equals(m.get("smtp_starttls")));
    }

    private static void require(Permission p) {
        if (!Permission.current(p)) throw new AccessDeniedException("Your role does not allow this: " + p.label());
    }

    private static void requireAdmin() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean ok = auth != null && auth.getAuthorities().stream().anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN").contains(a.getAuthority()));
        if (!ok) throw new AccessDeniedException("Only an admin can change the e-mail settings and templates");
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : "£" + String.format(Locale.UK, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    /** The standard wording (Cin7-like, plain), until the organisation writes its own. */
    static final Map<DocType, String[]> DEFAULTS = Map.of(
            DocType.QUOTE, new String[]{"Quote {{number}} from {{company}}",
                    "Dear {{customer}},\n\nPlease find attached our quote {{number}} for {{amount}}.\n\nKind regards,\n{{company}}"},
            DocType.SALES_ORDER, new String[]{"Order confirmation {{number}} — {{company}}",
                    "Dear {{customer}},\n\nThank you for your order {{reference}}. Our order confirmation {{number}} for {{amount}} is attached.\n\nKind regards,\n{{company}}"},
            DocType.INVOICE, new String[]{"Invoice {{number}} from {{company}}",
                    "Dear {{customer}},\n\nPlease find attached invoice {{number}} for {{amount}}, due on {{due_date}}.\n\nKind regards,\n{{company}}"},
            DocType.CREDIT_NOTE, new String[]{"Credit note {{number}} from {{company}}",
                    "Dear {{customer}},\n\nPlease find attached credit note {{number}} for {{amount}}.\n\nKind regards,\n{{company}}"},
            DocType.STATEMENT, new String[]{"Statement of account — {{company}}",
                    "Dear {{customer}},\n\nPlease find attached your statement to {{date}}. The balance is {{amount}}.\n\nKind regards,\n{{company}}"},
            DocType.REMINDER_1, new String[]{"Payment reminder — {{company}}",
                    "Dear {{customer}},\n\nOur records show {{amount}} overdue ({{invoices}}). If you have already paid, thank you; otherwise please arrange payment.\n\nKind regards,\n{{company}}"},
            DocType.REMINDER_2, new String[]{"Second payment reminder — {{company}}",
                    "Dear {{customer}},\n\n{{amount}} is still overdue ({{invoices}}). Please arrange payment within 7 days.\n\nKind regards,\n{{company}}"},
            DocType.REMINDER_3, new String[]{"Final notice — {{company}}",
                    "Dear {{customer}},\n\nDespite our reminders, {{amount}} remains overdue ({{invoices}}). Unless we receive payment within 7 days your account may be put on hold.\n\n{{company}}"},
            DocType.PURCHASE_ORDER, new String[]{"Purchase order {{number}} from {{company}}",
                    "Dear {{supplier}},\n\nPlease find attached our purchase order {{number}}. Please confirm the delivery date.\n\nKind regards,\n{{company}}"},
            DocType.TRACKING, new String[]{"Your order {{order_number}} is on its way",
                    "Dear {{customer}},\n\nYour order {{order_number}} has been sent with {{carrier}}. Tracking number: {{tracking}}.\n\nKind regards,\n{{company}}"});
}
