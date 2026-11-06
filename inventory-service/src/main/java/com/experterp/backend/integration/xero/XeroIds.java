package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Phase L3: the Xero ID of something already in Xero — sent through the queue (its mapping), or sent earlier in a CSV
 * file (looked up in Xero by its number).
 */
@Component
@RequiredArgsConstructor
public class XeroIds {

    private final JdbcTemplate jdbc;
    private final XeroClient xero;

    /** From the queue's mapping, or null. */
    public String mapped(String key) {
        List<String> r = jdbc.queryForList("SELECT xero_id FROM xero_sync_items WHERE tenant_id = ? AND item_key = ? AND status = 'SENT' AND xero_id IS NOT NULL",
                String.class, TenantContext.get(), key);
        return r.isEmpty() ? null : r.get(0);
    }

    /** An invoice / bill / credit note's Xero ID: the mapping, else found in Xero by its number. */
    public String document(Session s, long invoiceId) {
        String id = mapped("DOC:" + invoiceId);
        if (id != null) return id;
        List<Map<String, Object>> r = jdbc.queryForList("SELECT type, kind, invoice_number FROM invoices WHERE tenant_id = ? AND id = ?", TenantContext.get(), invoiceId);
        if (r.isEmpty()) throw new IllegalArgumentException("Document " + invoiceId + " no longer exists");
        boolean sale = "SALES".equals(r.get(0).get("type")), credit = "CREDIT_NOTE".equals(r.get(0).get("kind"));
        String number = (String) r.get(0).get("invoice_number");
        String found = credit
                ? xero.findCreditNoteByNumber(s, sale ? "ACCRECCREDIT" : "ACCPAYCREDIT", number).map(XeroApi.CreditNote::creditNoteID).orElse(null)
                : xero.findInvoiceByNumber(s, sale ? "ACCREC" : "ACCPAY", number).map(XeroApi.Invoice::invoiceID).orElse(null);
        if (found == null) throw new IllegalStateException(number + " is not in Xero: send it first");
        return found;
    }
}
