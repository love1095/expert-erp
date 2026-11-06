package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.Contact;
import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase L2: customers and suppliers as Xero contacts (keys CONTACT:CUSTOMER:id, CONTACT:SUPPLIER:id). A contact Xero
 * already has with the same name is linked rather than created again (Xero's contact names are unique, and an
 * organisation coming from Cin7 has its customers in Xero already).
 */
@Component
@RequiredArgsConstructor
public class XeroContactSender implements XeroItemSender {

    private final JdbcTemplate jdbc;
    private final XeroClient xero;

    public static String customerKey(Long id) { return "CONTACT:CUSTOMER:" + id; }

    public static String supplierKey(Long id) { return "CONTACT:SUPPLIER:" + id; }

    @Override
    public Set<String> kinds() { return Set.of("CONTACT"); }

    /** A document's party with no customer / supplier record (e.g. an old import): the name only. */
    public static String nameKey(String name) { return "CONTACT:NAME:" + name.trim(); }

    @Override
    public String send(Session session, XeroSyncService.Item item) {
        if (item.key().startsWith("CONTACT:NAME:")) {
            String name = item.key().substring("CONTACT:NAME:".length());
            return xero.findContactByName(session, name).map(Contact::contactID)
                    .orElseGet(() -> xero.saveContact(session, new Contact(null, name, null, null, null, null, null)).contactID());
        }
        String[] k = item.key().split(":");
        boolean customer = "CUSTOMER".equals(k[1]);
        Long id = Long.valueOf(k[2]);
        List<Map<String, Object>> rows = customer
                ? jdbc.queryForList("""
                        SELECT c.name, (SELECT x.email FROM customer_contacts x WHERE x.customer_id = c.id AND x.email IS NOT NULL
                                         ORDER BY x.is_default DESC, x.id LIMIT 1) AS email
                          FROM customers c WHERE c.tenant_id = ? AND c.id = ?""", TenantContext.get(), id)
                : jdbc.queryForList("SELECT s.name, s.email FROM suppliers s WHERE s.tenant_id = ? AND s.id = ?", TenantContext.get(), id);
        if (rows.isEmpty()) throw new IllegalArgumentException((customer ? "Customer " : "Supplier ") + id + " no longer exists");
        String name = ((String) rows.get(0).get("name")).trim();
        Optional<Contact> existing = xero.findContactByName(session, name);
        if (existing.isPresent()) return existing.get().contactID();
        return xero.saveContact(session, new Contact(null, name, (String) rows.get(0).get("email"), customer ? Boolean.TRUE : null,
                customer ? null : Boolean.TRUE, null, null)).contactID();
    }
}
