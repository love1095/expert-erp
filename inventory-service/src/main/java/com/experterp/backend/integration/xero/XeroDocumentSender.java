package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.*;
import com.experterp.backend.service.AccountingExportService;
import com.experterp.backend.service.AccountingExportService.DocLine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Phase L3: invoices, bills and credit notes (both sides) to Xero, exactly as the CSV export writes them (H5): sales
 * lines on the revenue account with the order's price and discount; bill lines on the inventory account (stock) or the
 * purchase charges account, net per unit; the ERP's VAT amount on each line (no penny differences); a "Full VAT" line
 * (import VAT) as its VAT. The ERP's VAT rate names are turned into Xero's TaxType codes from the organisation's own tax
 * rates in Xero (the names the CSV templates use); a name Xero does not have fails the item for a person to fix.
 */
@Component
@RequiredArgsConstructor
public class XeroDocumentSender implements XeroItemSender {

    private final XeroClient xero;
    private final XeroIds ids;
    private final AccountingExportService export;

    @Override
    public Set<String> kinds() { return Set.of("INVOICE", "BILL", "CREDIT_NOTE", "SUPPLIER_CREDIT"); }

    @Override
    public String send(Session s, XeroSyncService.Item item) {
        long invoiceId = Long.parseLong(item.key().substring("DOC:".length()));
        List<DocLine> lines = export.documentLines(invoiceId);
        if (lines.isEmpty()) throw new IllegalArgumentException("The document is no longer issued (cancelled?): nothing to send");
        String contactKey = item.dependsOn();
        String contactId = contactKey == null ? null : ids.mapped(contactKey);
        if (contactId == null) throw new IllegalStateException("Its contact is not in Xero yet");
        AccountingExportService.Settings settings = export.settings();
        Map<String, String> taxTypes = new HashMap<>();
        xero.taxRates(s).forEach(r -> taxTypes.put(r.name().toLowerCase(Locale.ROOT), r.taxType()));

        DocLine head = lines.get(0);
        boolean sale = "SALES".equals(head.type()), credit = "CREDIT_NOTE".equals(head.kind());
        List<LineItem> items = new ArrayList<>();
        for (DocLine l : lines) {
            String taxType = taxType(l.taxName(), taxTypes, item);
            if (sale) {
                items.add(new LineItem(AccountingExportService.description(l), l.qty(), l.unitPrice(), settings.revenueAccount(), taxType,
                        money(l.tax()), null, null, l.discount().signum() == 0 ? null : l.discount()));
            } else {
                BigDecimal unit = l.qty().signum() == 0 ? l.net() : l.net().divide(l.qty(), 4, RoundingMode.HALF_UP);
                if (l.net().signum() == 0 && l.tax().signum() != 0) unit = l.qty().signum() == 0 ? l.tax() : l.tax().divide(l.qty(), 4, RoundingMode.HALF_UP);
                items.add(new LineItem(AccountingExportService.description(l), l.qty(), unit,
                        l.stock() ? settings.inventoryAccount() : settings.purchaseChargesAccount(), taxType, money(l.tax()), null, null, null));
            }
        }
        String date = head.date().toString(), due = head.due().toString();
        if (credit) {
            CreditNote cn = xero.saveCreditNote(s, new CreditNote(null, sale ? "ACCRECCREDIT" : "ACCPAYCREDIT", Contact.ref(contactId), date,
                    head.number(), head.reference(), "Exclusive", items, "AUTHORISED", "GBP", null, null, null, null));
            return cn.creditNoteID();
        }
        Invoice inv = xero.saveInvoice(s, new Invoice(null, sale ? "ACCREC" : "ACCPAY", Contact.ref(contactId), date, due, head.number(),
                head.reference(), "Exclusive", items, "AUTHORISED", "GBP", null, null, null, null, null));
        return inv.invoiceID();
    }

    private static String taxType(String taxName, Map<String, String> taxTypes, XeroSyncService.Item item) {
        if (taxName == null || taxName.isBlank()) return "NONE";
        String t = taxTypes.get(taxName.toLowerCase(Locale.ROOT));
        if (t == null) {
            throw new IllegalArgumentException("The VAT rate \"" + taxName + "\" is not one of Xero's tax rates: add it in Xero with this name, or rename it here, then retry");
        }
        return t;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
