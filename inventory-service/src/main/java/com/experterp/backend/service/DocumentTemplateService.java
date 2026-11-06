package com.experterp.backend.service;

import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Named templates per document type (phase I4), like Cin7 Core's Document and email templates: an organisation can
 * have several (Expert Safety: "Invoice - Account Client", "Invoice - Cash Sale"…), one default per type, and pick
 * one when printing. Cin7 takes Word mail-merge files; here a template is a set of layout settings applied to the
 * ERP's own PDF layout: never code from the organisation, so one organisation cannot run anything on the server.
 */
@Service
@RequiredArgsConstructor
public class DocumentTemplateService {

    private static final Pattern COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public enum DocType { QUOTE, SALES_ORDER, INVOICE, CREDIT_NOTE, PURCHASE_ORDER }

    public record Template(Long id, DocType docType, String name, boolean isDefault, String title, String accentColor,
                           boolean showLogo, boolean showPrices, boolean showSku, boolean showDiscount, boolean showVatSummary,
                           String introText, String paymentText, String footerText, String emailSubject) {}

    @Transactional(readOnly = true)
    public List<Template> list() {
        return jdbc.query("SELECT * FROM document_templates WHERE tenant_id = ? ORDER BY doc_type, NOT is_default, lower(name)",
                (rs, n) -> map(rs), TenantContext.get());
    }

    @Transactional(readOnly = true)
    public Template get(Long id) {
        return jdbc.query("SELECT * FROM document_templates WHERE tenant_id = ? AND id = ?", (rs, n) -> map(rs), TenantContext.get(), id)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Template not found: " + id));
    }

    /** The template to print a document of this type with: the one asked for (it must be of that type), else the default. */
    @Transactional(readOnly = true)
    public Optional<Template> forPrinting(DocType type, Long templateId) {
        if (templateId != null) {
            Template t = get(templateId);
            if (t.docType() != type) {
                throw new IllegalArgumentException(t.name() + " is a template for " + label(t.docType()) + ", not for " + label(type));
            }
            return Optional.of(t);
        }
        return jdbc.query("SELECT * FROM document_templates WHERE tenant_id = ? AND doc_type = ? AND is_default",
                (rs, n) -> map(rs), TenantContext.get(), type.name()).stream().findFirst();
    }

    @Transactional
    public Template save(Long id, Template t) {
        Long tenant = TenantContext.get();
        validate(t);
        // The first template of a type is its default; a new default takes over from the old one
        boolean first = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_templates WHERE tenant_id = ? AND doc_type = ? AND (?::bigint IS NULL OR id <> ?)",
                Integer.class, tenant, t.docType().name(), id, id)).orElse(0) == 0;
        boolean isDefault = t.isDefault() || first;
        if (isDefault) {
            jdbc.update("UPDATE document_templates SET is_default = false WHERE tenant_id = ? AND doc_type = ? AND is_default"
                    + " AND (?::bigint IS NULL OR id <> ?)", tenant, t.docType().name(), id, id);
        }
        Object[] values = {t.docType().name(), t.name().trim(), isDefault, blank(t.title()), t.accentColor() == null ? "#d42020" : t.accentColor(),
                t.showLogo(), t.showPrices(), t.showSku(), t.showDiscount(), t.showVatSummary(), blank(t.introText()),
                blank(t.paymentText()), blank(t.footerText()), blank(t.emailSubject())};
        try {
            if (id == null) {
                Object[] args = Arrays.copyOf(new Object[]{tenant}, 1 + values.length);
                System.arraycopy(values, 0, args, 1, values.length);
                id = jdbc.queryForObject("""
                        INSERT INTO document_templates (tenant_id, doc_type, name, is_default, title, accent_color, show_logo, show_prices,
                            show_sku, show_discount, show_vat_summary, intro_text, payment_text, footer_text, email_subject)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, args);
                auditService.log("DOCUMENT_TEMPLATE", id, "CREATE", Map.of("name", t.name(), "type", t.docType().name()));
            } else {
                get(id);
                Object[] args = Arrays.copyOf(values, values.length + 2);
                args[values.length] = tenant;
                args[values.length + 1] = id;
                jdbc.update("""
                        UPDATE document_templates SET doc_type = ?, name = ?, is_default = ?, title = ?, accent_color = ?, show_logo = ?,
                            show_prices = ?, show_sku = ?, show_discount = ?, show_vat_summary = ?, intro_text = ?, payment_text = ?,
                            footer_text = ?, email_subject = ?, updated_at = now()
                        WHERE tenant_id = ? AND id = ?""", args);
                auditService.log("DOCUMENT_TEMPLATE", id, "UPDATE", Map.of("name", t.name(), "type", t.docType().name()));
            }
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("There is a " + label(t.docType()) + " template called " + t.name().trim() + " already");
        }
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        Template t = get(id);
        jdbc.update("DELETE FROM document_templates WHERE tenant_id = ? AND id = ?", TenantContext.get(), id);
        // Another template of the type becomes the default, so printing keeps one
        if (t.isDefault()) {
            jdbc.update("""
                    UPDATE document_templates SET is_default = true WHERE id = (
                      SELECT id FROM document_templates WHERE tenant_id = ? AND doc_type = ? ORDER BY lower(name) LIMIT 1)""",
                    TenantContext.get(), t.docType().name());
        }
        auditService.log("DOCUMENT_TEMPLATE", id, "DELETE", Map.of("name", t.name(), "type", t.docType().name()));
    }

    /** The document laid out as the template says. */
    public static DocumentModel apply(DocumentModel m, Template t) {
        if (t == null) return m;
        DocumentModel.Company c = m.company();
        DocumentModel.Company company = new DocumentModel.Company(c.name(), c.address(), c.phone(), c.email(), c.website(),
                c.vatNumber(), c.companyNumber(), t.showLogo() ? c.logoDataUri() : null,
                t.footerText() != null ? t.footerText() : c.footer());
        boolean prices = t.showPrices();
        String payment = !prices ? null : t.paymentText() != null ? t.paymentText() : m.payment();
        return new DocumentModel(t.title() != null ? t.title() : m.title(), m.number(), m.stamp(), company, m.parties(), m.fields(),
                m.showCode() && t.showSku(), m.showSupplierCode(), prices && m.showDiscount() && t.showDiscount(), m.lines(),
                prices ? m.totals() : List.of(), prices && t.showVatSummary() ? m.vatSummary() : List.of(), m.notes(), payment,
                m.fileName(), new DocumentModel.Style(t.accentColor(), prices, t.introText()));
    }

    private static void validate(Template t) {
        if (t.docType() == null) throw new IllegalArgumentException("Choose the document type");
        if (t.name() == null || t.name().isBlank()) throw new IllegalArgumentException("Give the template a name");
        if (t.name().trim().length() > 80) throw new IllegalArgumentException("A template name is at most 80 characters");
        if (t.title() != null && t.title().trim().length() > 60) throw new IllegalArgumentException("A title is at most 60 characters");
        if (t.accentColor() != null && !COLOUR.matcher(t.accentColor()).matches()) {
            throw new IllegalArgumentException("The colour must look like #d42020");
        }
        for (String s : new String[]{t.introText(), t.paymentText(), t.footerText()}) {
            if (s != null && s.length() > 2000) throw new IllegalArgumentException("A text is at most 2000 characters");
        }
        if (t.emailSubject() != null && t.emailSubject().length() > 200) throw new IllegalArgumentException("An e-mail subject is at most 200 characters");
    }

    static String label(DocType t) {
        return switch (t) {
            case QUOTE -> "quotes";
            case SALES_ORDER -> "sales orders";
            case INVOICE -> "invoices";
            case CREDIT_NOTE -> "credit notes";
            case PURCHASE_ORDER -> "purchase orders";
        };
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Template map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Template(rs.getLong("id"), DocType.valueOf(rs.getString("doc_type")), rs.getString("name"), rs.getBoolean("is_default"),
                rs.getString("title"), rs.getString("accent_color"), rs.getBoolean("show_logo"), rs.getBoolean("show_prices"),
                rs.getBoolean("show_sku"), rs.getBoolean("show_discount"), rs.getBoolean("show_vat_summary"), rs.getString("intro_text"),
                rs.getString("payment_text"), rs.getString("footer_text"), rs.getString("email_subject"));
    }
}
