package com.experterp.backend.service;

import com.experterp.backend.entity.Supplier;
import com.experterp.backend.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Cin7 Core's Suppliers file (Suppliers_YYYY-MM-DD.csv), both ways. Cin7 writes one row per contact, the supplier's
 * columns repeated; a supplier here has one contact, so the export writes one row per supplier and the import takes
 * the contact marked ContactDefault (else the supplier's first row).
 * <p>
 * Import: suppliers are matched by name (ignoring case), created when new. Columns missing from the file leave the
 * existing values alone. Cin7's columns with nothing to hold them here (AccountPayable, Carrier, attributes, job title,
 * mobile, fax) are written empty and ignored on import. Importing the same file twice gives the same result.
 * Supplier addresses and contacts follow when the supplier page has its address and contact lists.
 */
@Service
@RequiredArgsConstructor
public class SupplierFileService {

    static final String[] SUPPLIERS = {"Name", "Status", "Currency", "PaymentTerm", "TaxRule", "AccountPayable", "Carrier", "Discount",
            "TaxNumber", "AttributeSet", "AdditionalAttribute1", "AdditionalAttribute2", "AdditionalAttribute3", "AdditionalAttribute4",
            "AdditionalAttribute5", "AdditionalAttribute6", "AdditionalAttribute7", "AdditionalAttribute8", "AdditionalAttribute9",
            "AdditionalAttribute10", "Comments", "ContactName", "JobTitle", "Phone", "MobilePhone", "Fax", "Email", "Website",
            "ContactComment", "ContactDefault", "ContactIncludeInEmail", "IsAccountingDimensionEnabled", "DimensionAttribute1",
            "DimensionAttribute2", "DimensionAttribute3", "DimensionAttribute4", "DimensionAttribute5", "DimensionAttribute6",
            "DimensionAttribute7", "DimensionAttribute8", "DimensionAttribute9", "DimensionAttribute10"};

    private static final int MAX_WARNINGS = 200;

    /** The suppliers' files, as on the Export / Import menus. */
    public enum Kind { SUPPLIERS }

    public record ImportResult(int rows, int created, int updated, int unchanged, List<String> warnings) {}

    private final SupplierRepository supplierRepository;
    private final AuditService auditService;

    public String fileName(Kind kind) {
        return "Suppliers_" + LocalDate.now() + ".csv";
    }

    // Not read-only: the export writes a line in the audit log
    @Transactional
    public void export(Kind kind, Writer out) throws IOException {
        Writer buffered = new BufferedWriter(out);
        // BOM so Excel opens it as UTF-8
        buffered.write('﻿');
        CSVPrinter printer = new CSVPrinter(buffered, CSVFormat.DEFAULT.builder().setHeader(SUPPLIERS).build());
        for (Supplier s : supplierRepository.findAll(Sort.by("name"))) {
            List<Object> row = new ArrayList<>(SUPPLIERS.length);
            boolean contact = s.getContactName() != null && !s.getContactName().isBlank();
            row.addAll(Arrays.asList(s.getName(), "Active".equalsIgnoreCase(s.getStatus()) ? "Active" : "Deprecated", nz(s.getCurrency()),
                    nz(s.getPaymentTerm()), nz(s.getTaxRule()), "", "", s.getDiscount() == null ? "0.00" : s.getDiscount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    nz(s.getTaxNumber())));
            for (int i = 0; i < 11; i++) row.add("");                       // AttributeSet, AdditionalAttribute1–10
            row.addAll(Arrays.asList(nz(s.getComments()), nz(s.getContactName()), "", nz(s.getPhone()), "", "", nz(s.getEmail()),
                    nz(s.getWebsite()), "", contact ? "True" : "False", "True", "False"));
            for (int i = 0; i < 10; i++) row.add("");                       // DimensionAttribute1–10
            printer.printRecord(row);
        }
        printer.flush();
        auditService.log("SUPPLIER", null, "EXPORT", Map.of("file", "Suppliers"));
    }

    @Transactional
    public ImportResult importCsv(Kind kind, byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true).build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            CsvColumns cols = new CsvColumns(parser.getHeaderNames());
            if (!cols.has("Name") || cols.has("AddressType") || cols.has("Contact") && cols.has("Supplier")
                    || !(cols.has("PaymentTerm") || cols.has("Currency") || cols.has("ContactName") || cols.has("TaxNumber"))) {
                throw new IllegalArgumentException(cols.has("AddressType") || cols.has("Supplier")
                        ? "This is Cin7's supplier addresses or contacts file, not the suppliers file: those come with the supplier address and contact lists"
                        : "This is not Cin7's suppliers file: its columns are not the ones Cin7 exports");
            }
            List<String> warnings = new ArrayList<>();
            // Rows per supplier, in file order; the default contact's row (else the first) gives the contact
            Map<String, List<CSVRecord>> groups = new LinkedHashMap<>();
            int rows = 0;
            for (CSVRecord r : parser) {
                rows++;
                String name = cols.get(r, "Name");
                if (name == null) {
                    warn(warnings, "Line " + r.getParser().getCurrentLineNumber() + ": no Name, skipped");
                    continue;
                }
                groups.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
            }
            Map<String, Supplier> existing = new HashMap<>();
            supplierRepository.findAll().forEach(s -> existing.putIfAbsent(s.getName().toLowerCase(Locale.ROOT), s));
            int created = 0, updated = 0, unchanged = 0;
            for (Map.Entry<String, List<CSVRecord>> g : groups.entrySet()) {
                CSVRecord first = g.getValue().get(0);
                CSVRecord contact = g.getValue().stream().filter(r -> "True".equalsIgnoreCase(cols.get(r, "ContactDefault"))).findFirst().orElse(first);
                Supplier s = existing.get(g.getKey());
                boolean isNew = s == null;
                if (isNew) {
                    s = new Supplier();
                    s.setName(cols.get(first, "Name"));
                }
                String before = isNew ? null : fingerprint(s);
                if (cols.has("Status")) s.setStatus("Deprecated".equalsIgnoreCase(cols.get(first, "Status")) ? "Deprecated" : "Active");
                if (cols.has("Currency")) s.setCurrency(cols.get(first, "Currency"));
                if (cols.has("PaymentTerm")) s.setPaymentTerm(cols.get(first, "PaymentTerm"));
                if (cols.has("TaxRule")) s.setTaxRule(cols.get(first, "TaxRule"));
                if (cols.has("TaxNumber")) s.setTaxNumber(cols.get(first, "TaxNumber"));
                if (cols.has("Comments")) s.setComments(cols.get(first, "Comments"));
                if (cols.has("Discount")) {
                    String d = cols.get(first, "Discount");
                    try {
                        BigDecimal v = d == null ? null : new BigDecimal(d.replace(",", "").replace("%", ""));
                        s.setDiscount(v == null || v.signum() == 0 ? null : v);
                    } catch (NumberFormatException e) {
                        warn(warnings, s.getName() + ": Discount '" + d + "' is not a number, left as it was");
                    }
                }
                if (cols.has("ContactName")) s.setContactName(cols.get(contact, "ContactName"));
                if (cols.has("Phone")) s.setPhone(firstNonNull(cols.get(contact, "Phone"), cols.get(contact, "MobilePhone")));
                if (cols.has("Email")) s.setEmail(cols.get(contact, "Email"));
                if (cols.has("Website")) s.setWebsite(cols.get(contact, "Website"));
                if (isNew) {
                    supplierRepository.save(s);
                    existing.put(g.getKey(), s);
                    created++;
                } else if (before.equals(fingerprint(s))) {
                    unchanged++;
                } else {
                    updated++;
                }
            }
            auditService.log("SUPPLIER", null, "IMPORT", Map.of("file", "Suppliers", "rows", rows, "created", created, "updated", updated));
            return new ImportResult(rows, created, updated, unchanged, warnings);
        }
    }

    private static String fingerprint(Supplier s) {
        return String.join("|", Arrays.asList(s.getName(), s.getStatus(), s.getCurrency(), s.getPaymentTerm(), s.getTaxRule(), s.getTaxNumber(),
                s.getComments(), s.getDiscount() == null ? null : s.getDiscount().stripTrailingZeros().toPlainString(), s.getContactName(),
                s.getPhone(), s.getEmail(), s.getWebsite()).stream().map(v -> v == null ? "" : v).toList());
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(message);
    }
}
