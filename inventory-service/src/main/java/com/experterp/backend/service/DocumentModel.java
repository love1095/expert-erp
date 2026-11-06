package com.experterp.backend.service;

import java.util.List;

/**
 * What a printed document shows (phase D5), already formatted: one shape for quotes, sale orders, invoices, credit
 * notes and purchase orders, so one template prints them all. Empty parts are left out of the page.
 */
public record DocumentModel(
        String title,               // "Invoice", "Purchase order"...
        String number,              // INV-1042
        String stamp,               // "Draft", "Cancelled"... (printed across the top), or null
        Company company,
        List<Party> parties,        // "Invoice to", "Supplier", "Deliver to"...
        List<Field> fields,         // Date, Due date, Your reference...
        boolean showCode,           // SKU column
        boolean showSupplierCode,   // supplier's code column (purchase orders)
        boolean showDiscount,
        List<Line> lines,
        List<Total> totals,
        List<VatRow> vatSummary,
        String notes,
        String payment,             // how to pay (invoices): bank details and the reference to quote
        String fileName,
        Style style) {              // the template's layout (phase I4)

    /** As before I4: the standard layout. */
    public DocumentModel(String title, String number, String stamp, Company company, List<Party> parties, List<Field> fields,
                         boolean showCode, boolean showSupplierCode, boolean showDiscount, List<Line> lines, List<Total> totals,
                         List<VatRow> vatSummary, String notes, String payment, String fileName) {
        this(title, number, stamp, company, parties, fields, showCode, showSupplierCode, showDiscount, lines, totals, vatSummary,
                notes, payment, fileName, Style.STANDARD);
    }

    /** Accent colour; prices shown (false: a delivery note / pick list); text above the lines. */
    public record Style(String accentColor, boolean showPrices, String introText) {
        public static final Style STANDARD = new Style("#d42020", true, null);
    }

    public record Company(String name, List<String> address, String phone, String email, String website,
                          String vatNumber, String companyNumber, String logoDataUri, String footer) {}

    public record Party(String label, String name, List<String> lines) {}

    public record Field(String label, String value) {}

    // A product line, or an additional charge / cost (no code)
    public record Line(String code, String supplierCode, String description, String quantity, String unitPrice,
                       String discount, String tax, String net, boolean charge) {}

    public record Total(String label, String value, boolean strong) {}

    public record VatRow(String rate, String net, String vat) {}
}
