package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Phase P1: safety compliance per SKU — the EN / ISO standards, CE and UKCA marking, the protection class and the
 * certificate (number, issuing body, expiry, the PDF). Each SKU holds its own: nothing is taken from another product of
 * the same family, because a certificate covers the sizes and models it names. A report lists certificates expiring
 * within N days (and those already expired).
 */
@Service
@RequiredArgsConstructor
public class ProductComplianceService {

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final ProductFileService files;
    private final AuditService auditService;

    public record Compliance(Long productId, String standards, boolean ceMarked, boolean ukcaMarked, String protectionClass, String certificateNumber,
                             String certificateIssuer, LocalDate certificateExpiry, String notes, ProductFileService.FileInfo certificateFile) {
        public static Compliance empty(Long productId) {
            return new Compliance(productId, null, false, false, null, null, null, null, null, null);
        }
    }

    public record Request(String standards, Boolean ceMarked, Boolean ukcaMarked, String protectionClass, String certificateNumber,
                          String certificateIssuer, LocalDate certificateExpiry, String notes) {}

    public record Expiring(Long productId, String sku, String name, String standards, String certificateNumber, String certificateIssuer,
                           LocalDate certificateExpiry, long daysLeft, boolean hasFile) {}

    @Transactional(readOnly = true)
    public Compliance get(Long productId) {
        products.findById(productId).orElseThrow(() -> new IllegalArgumentException("Product not found"));
        ProductFileService.FileInfo cert = files.certificate(productId).orElse(null);
        return jdbc.query("SELECT * FROM product_compliance WHERE tenant_id = ? AND product_id = ?", rs -> {
            if (!rs.next()) return new Compliance(productId, null, false, false, null, null, null, null, null, cert);
            Date exp = rs.getDate("certificate_expiry");
            return new Compliance(productId, rs.getString("standards"), rs.getBoolean("ce_marked"), rs.getBoolean("ukca_marked"),
                    rs.getString("protection_class"), rs.getString("certificate_number"), rs.getString("certificate_issuer"),
                    exp == null ? null : exp.toLocalDate(), rs.getString("notes"), cert);
        }, TenantContext.get(), productId);
    }

    @Transactional
    public Compliance save(Long productId, Request r) {
        products.findById(productId).orElseThrow(() -> new IllegalArgumentException("Product not found"));
        upsert(productId, r);
        auditService.log("PRODUCT", productId, "COMPLIANCE", Map.of("standards", String.valueOf(r.standards()),
                "certificateExpiry", String.valueOf(r.certificateExpiry())));
        return get(productId);
    }

    /** Saves without the product check or audit line: the CSV import does it in bulk for products it already holds. */
    void upsert(Long productId, Request r) {
        jdbc.update("""
                INSERT INTO product_compliance (product_id, tenant_id, standards, ce_marked, ukca_marked, protection_class, certificate_number,
                                                certificate_issuer, certificate_expiry, notes, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
                ON CONFLICT (product_id) DO UPDATE SET standards = EXCLUDED.standards, ce_marked = EXCLUDED.ce_marked, ukca_marked = EXCLUDED.ukca_marked,
                       protection_class = EXCLUDED.protection_class, certificate_number = EXCLUDED.certificate_number,
                       certificate_issuer = EXCLUDED.certificate_issuer, certificate_expiry = EXCLUDED.certificate_expiry, notes = EXCLUDED.notes,
                       updated_at = now(), updated_by = EXCLUDED.updated_by""",
                productId, TenantContext.get(), cut(r.standards(), 500), Boolean.TRUE.equals(r.ceMarked()), Boolean.TRUE.equals(r.ukcaMarked()),
                cut(r.protectionClass(), 100), cut(r.certificateNumber(), 100), cut(r.certificateIssuer(), 150),
                r.certificateExpiry() == null ? null : Date.valueOf(r.certificateExpiry()), cut(r.notes(), 1000), auditService.currentActorName());
    }

    /** Certificates expiring within {@code days} days, and those already expired, soonest first. */
    @Transactional(readOnly = true)
    public List<Expiring> expiring(int days) {
        if (days < 0 || days > 3650) throw new IllegalArgumentException("Choose between 0 and 3650 days");
        LocalDate today = LocalDate.now();
        return jdbc.query("""
                SELECT c.product_id, p.sku, p.name, c.standards, c.certificate_number, c.certificate_issuer, c.certificate_expiry,
                       EXISTS (SELECT 1 FROM product_files f WHERE f.product_id = c.product_id AND f.kind = 'CERTIFICATE') AS has_file
                  FROM product_compliance c JOIN products p ON p.id = c.product_id
                 WHERE c.tenant_id = ? AND c.certificate_expiry IS NOT NULL AND c.certificate_expiry <= ? AND p.status <> 'DEPRECATED'
                 ORDER BY c.certificate_expiry, p.sku""", (rs, n) -> {
            LocalDate exp = rs.getDate(7).toLocalDate();
            return new Expiring(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), exp,
                    ChronoUnit.DAYS.between(today, exp), rs.getBoolean(8));
        }, TenantContext.get(), Date.valueOf(today.plusDays(days)));
    }

    /** Compliance of many products at once (the CSV export). */
    @Transactional(readOnly = true)
    public Map<Long, Compliance> byProduct(Collection<Long> productIds) {
        Map<Long, Compliance> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        jdbc.query("SELECT * FROM product_compliance WHERE tenant_id = ? AND product_id = ANY(?::bigint[])", rs -> {
            Date exp = rs.getDate("certificate_expiry");
            out.put(rs.getLong("product_id"), new Compliance(rs.getLong("product_id"), rs.getString("standards"), rs.getBoolean("ce_marked"),
                    rs.getBoolean("ukca_marked"), rs.getString("protection_class"), rs.getString("certificate_number"), rs.getString("certificate_issuer"),
                    exp == null ? null : exp.toLocalDate(), rs.getString("notes"), null));
        }, TenantContext.get(), (Object) productIds.toArray(Long[]::new));
        return out;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
