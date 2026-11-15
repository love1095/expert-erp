package com.experterp.backend.integration.xero;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.multitenancy.TenantContext;

import java.util.List;
import java.util.Set;

/**
 * What was sent to Xero and then undone here, like Cin7's link does: an invoice or bill cancelled here is voided in
 * Xero (VOID:id), a payment voided here is deleted in Xero (UNPAY:id). Xero refuses to void a document that still has
 * a payment or a credit note on it: the item then fails with Xero's reason, to put right there by hand.
 */
@Component
@RequiredArgsConstructor
public class XeroUndoSender implements XeroItemSender {

    private final JdbcTemplate jdbc;
    private final XeroClient xero;

    @Override
    public Set<String> kinds() { return Set.of("VOID", "PAYMENT_DELETE"); }

    @Override
    public String send(Session s, XeroSyncService.Item item) {
        String sentKey = ("VOID".equals(item.kind()) ? "DOC:" : "PAY:") + item.key().substring(item.key().indexOf(':') + 1);
        List<String> ids = jdbc.queryForList("SELECT xero_id FROM xero_sync_items WHERE tenant_id = ? AND item_key = ? AND status = 'SENT' AND xero_id IS NOT NULL",
                String.class, TenantContext.get(), sentKey);
        if (ids.isEmpty()) throw new IllegalStateException("Xero's ID for " + sentKey + " is not known here: put it right in Xero by hand");
        if ("VOID".equals(item.kind())) xero.voidInvoice(s, ids.get(0)); else xero.deletePayment(s, ids.get(0));
        return ids.get(0);
    }
}
