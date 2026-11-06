package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.*;
import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase L3: money in and out on documents — a payment on an invoice or bill, a refund on a credit note (PAY:id), into
 * the payment account's bank account in Xero (H1's account codes); and a credit note applied to an invoice or bill
 * (ALLOC:id), which the CSV files could not carry.
 */
@Component
@RequiredArgsConstructor
public class XeroPaymentSender implements XeroItemSender {

    private final JdbcTemplate jdbc;
    private final XeroClient xero;
    private final XeroIds ids;

    @Override
    public Set<String> kinds() { return Set.of("PAYMENT", "ALLOCATION"); }

    @Override
    public String send(Session s, XeroSyncService.Item item) {
        long paymentId = Long.parseLong(item.key().substring(item.key().indexOf(':') + 1));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.invoice_id, p.credit_note_id, p.amount, p.paid_at::date AS paid, p.reference, i.kind,
                       COALESCE(pa.code, (SELECT d.code FROM payment_accounts d WHERE d.tenant_id = p.tenant_id AND d.is_default AND d.active ORDER BY d.code LIMIT 1)) AS bank
                  FROM payments p JOIN invoices i ON i.id = p.invoice_id LEFT JOIN payment_accounts pa ON pa.id = p.payment_account_id
                 WHERE p.tenant_id = ? AND p.id = ?""", TenantContext.get(), paymentId);
        if (rows.isEmpty()) throw new IllegalArgumentException("The payment no longer exists (voided?)");
        Map<String, Object> p = rows.get(0);
        long docId = ((Number) p.get("invoice_id")).longValue();
        BigDecimal amount = (BigDecimal) p.get("amount");
        String date = p.get("paid").toString();
        if ("ALLOCATION".equals(item.kind())) {
            String creditNote = ids.document(s, ((Number) p.get("credit_note_id")).longValue());
            xero.allocate(s, creditNote, new Allocation(new DocRef(ids.document(s, docId), null, null, null), amount, date));
            return "allocation:" + creditNote + ":" + paymentId;
        }
        String bank = (String) p.get("bank");
        if (bank == null) throw new IllegalArgumentException("The payment has no payment account (bank): choose a default payment account, then retry");
        String docXeroId = ids.document(s, docId);
        boolean refund = "CREDIT_NOTE".equals(p.get("kind"));
        Payment saved = xero.createPayment(s, new Payment(null, refund ? null : new DocRef(docXeroId, null, null, null),
                refund ? new DocRef(null, docXeroId, null, null) : null, new AccountRef(bank, null), date, amount, (String) p.get("reference"), null, null));
        return saved.paymentID();
    }
}
