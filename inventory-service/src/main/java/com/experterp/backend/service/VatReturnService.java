package com.experterp.backend.service;

import com.experterp.backend.entity.TaxRate.VatType;
import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * The VAT return for a period (phase H3, spec §6), in HMRC's nine boxes, on the standard (accrual) scheme: every
 * issued sales invoice and supplier bill dated in the period counts, paid or not; credit notes count against them.
 * Each line goes by its tax rate's VAT type (like Xero's tax types):
 * <ul>
 *   <li>sales: VAT in box 1, net in box 6 (zero-rated and exempt too; "No VAT" is outside the scope and left out);</li>
 *   <li>purchases: VAT in box 4, net in box 7; reverse charge: notional VAT in box 1 and box 4 (nothing was charged);
 *       EC acquisitions: notional VAT in box 2 and box 4, net in box 9; Full VAT (import VAT): all VAT, box 4.</li>
 * </ul>
 * Boxes 1–5 are in pounds and pence; boxes 6–9 in whole pounds, pence dropped, as HMRC asks. The return itself is
 * filed from the accounting package (Making Tax Digital); this is the figure to check it against.
 */
@Service
@RequiredArgsConstructor
public class VatReturnService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcTemplate jdbc;

    public record Boxes(BigDecimal box1, BigDecimal box2, BigDecimal box3, BigDecimal box4, BigDecimal box5,
                        BigDecimal box6, BigDecimal box7, BigDecimal box8, BigDecimal box9) {}

    /** Net and VAT per tax rate, sales or purchases. */
    public record RateRow(String side, String taxName, VatType vatType, BigDecimal rate, BigDecimal net, BigDecimal vat, int lines) {}

    /** A document in the period with what it adds to the return (credit notes negative). */
    public record Document(Long invoiceId, String side, String kind, String number, LocalDate date, String party, BigDecimal net, BigDecimal vat) {}

    public record VatReturn(LocalDate from, LocalDate to, Boxes boxes, List<RateRow> rates, List<Document> documents, boolean refund) {}

    private record Line(String side, String kind, Long invoiceId, String number, LocalDate date, String party,
                        BigDecimal net, BigDecimal vat, String taxName, BigDecimal rate, VatType type, BigDecimal reportRate) {}

    @Transactional(readOnly = true)
    public VatReturn vatReturn(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("Give the VAT period (from and to)");
        if (from.isAfter(to)) throw new IllegalArgumentException("The VAT period starts after it ends");
        Long t = TenantContext.get();
        List<Line> lines = jdbc.query("""
                SELECT i.type, i.kind, i.id, i.invoice_number, i.issue_date, i.party_name,
                       it.total_price AS net, it.tax_amount AS vat, it.tax_name, it.tax_rate, tr.vat_type, tr.report_rate
                FROM invoice_items it
                JOIN invoices i ON i.id = it.invoice_id
                LEFT JOIN tax_rates tr ON tr.tenant_id = i.tenant_id AND lower(tr.name) = lower(it.tax_name)
                     AND tr.usage = CASE WHEN i.type = 'SALES' THEN 'SALE' ELSE 'PURCHASE' END
                WHERE i.tenant_id = ? AND i.status IN ('ISSUED', 'PAID') AND i.issue_date BETWEEN ? AND ?
                ORDER BY i.issue_date, i.id, it.id""", (rs, n) -> {
            boolean credit = "CREDIT_NOTE".equals(rs.getString("kind"));
            BigDecimal sign = credit ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            BigDecimal rate = nz(rs.getBigDecimal("tax_rate"));
            String typeName = rs.getString("vat_type");
            // A line whose rate is not (or no longer) set up: judged by its percentage
            VatType type = typeName != null ? VatType.valueOf(typeName)
                    : rate.compareTo(HUNDRED) >= 0 ? VatType.FULL_VAT : rate.signum() == 0 ? VatType.ZERO : VatType.STANDARD;
            BigDecimal reportRate = rs.getBigDecimal("report_rate") != null ? rs.getBigDecimal("report_rate") : rate;
            return new Line("SALES".equals(rs.getString("type")) ? "SALES" : "PURCHASES", rs.getString("kind"), rs.getLong("id"),
                    rs.getString("invoice_number"), rs.getDate("issue_date").toLocalDate(), rs.getString("party_name"),
                    nz(rs.getBigDecimal("net")).multiply(sign), nz(rs.getBigDecimal("vat")).multiply(sign),
                    rs.getString("tax_name"), rate, type, reportRate);
        }, t, Date.valueOf(from), Date.valueOf(to));

        BigDecimal b1 = BigDecimal.ZERO, b2 = BigDecimal.ZERO, b4 = BigDecimal.ZERO, b6 = BigDecimal.ZERO, b7 = BigDecimal.ZERO, b9 = BigDecimal.ZERO;
        Map<String, BigDecimal[]> byRate = new LinkedHashMap<>();
        Map<String, Line> rateInfo = new HashMap<>();
        Map<Long, BigDecimal[]> byDoc = new LinkedHashMap<>();
        Map<Long, Line> docInfo = new HashMap<>();
        for (Line l : lines) {
            if (l.type() == VatType.NO_VAT) continue;
            boolean sale = "SALES".equals(l.side());
            BigDecimal notional = l.net().multiply(l.reportRate()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal vat = l.vat();
            if (sale) {
                b1 = b1.add(l.vat());
                b6 = b6.add(l.net());
            } else {
                switch (l.type()) {
                    case REVERSE_CHARGE -> { b1 = b1.add(notional); b4 = b4.add(notional); b7 = b7.add(l.net()); vat = notional; }
                    case EC_ACQUISITION -> { b2 = b2.add(notional); b4 = b4.add(notional); b7 = b7.add(l.net()); b9 = b9.add(l.net()); vat = notional; }
                    default -> { b4 = b4.add(l.vat()); b7 = b7.add(l.net()); }
                }
            }
            String key = l.side() + "|" + l.taxName() + "|" + l.type();
            BigDecimal[] r = byRate.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            r[0] = r[0].add(l.net());
            r[1] = r[1].add(vat);
            r[2] = r[2].add(BigDecimal.ONE);
            rateInfo.putIfAbsent(key, l);
            BigDecimal[] d = byDoc.computeIfAbsent(l.invoiceId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            d[0] = d[0].add(l.net());
            d[1] = d[1].add(vat);
            docInfo.putIfAbsent(l.invoiceId(), l);
        }
        BigDecimal b3 = b1.add(b2);
        Boxes boxes = new Boxes(pence(b1), pence(b2), pence(b3), pence(b4), pence(b3.subtract(b4).abs()),
                pounds(b6), pounds(b7), BigDecimal.ZERO, pounds(b9));

        List<RateRow> rates = new ArrayList<>();
        byRate.forEach((k, v) -> {
            Line l = rateInfo.get(k);
            rates.add(new RateRow(l.side(), l.taxName(), l.type(), l.rate(), pence(v[0]), pence(v[1]), v[2].intValue()));
        });
        rates.sort(Comparator.comparing(RateRow::side).reversed().thenComparing(RateRow::net, Comparator.reverseOrder()));
        List<Document> documents = new ArrayList<>();
        byDoc.forEach((id, v) -> {
            Line l = docInfo.get(id);
            documents.add(new Document(id, l.side(), l.kind(), l.number(), l.date(), l.party(), pence(v[0]), pence(v[1])));
        });
        return new VatReturn(from, to, boxes, rates, documents, b3.compareTo(b4) < 0);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal pence(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // HMRC: boxes 6 to 9 in whole pounds, pence left out
    private static BigDecimal pounds(BigDecimal v) {
        return v.setScale(0, RoundingMode.DOWN);
    }
}
