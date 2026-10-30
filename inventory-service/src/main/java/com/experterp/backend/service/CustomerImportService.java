package com.experterp.backend.service;

import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact;
import com.experterp.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * Imports a Cin7 Core customer export (Customers_YYYY-MM-DD.csv).
 * <p>
 * Cin7 writes one row per contact: the customer columns repeat on every row of the same customer.
 * Rows are grouped by Name (ignoring case). A customer that already exists with that name is updated,
 * otherwise it is created. Columns missing from the file leave the existing values alone; when the file
 * has contact columns, the customer's contacts are replaced by the ones in the file.
 * Importing the same file twice gives the same result.
 */
@Service
@RequiredArgsConstructor
public class CustomerImportService {

    private static final List<String> CONTACT_COLUMNS =
            List.of("ContactName", "JobTitle", "Phone", "MobilePhone", "Fax", "Email", "Website");

    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final AuditService auditService;

    public record ImportResult(int rows, int customersCreated, int customersUpdated, int contacts, List<String> warnings) {}

    @Transactional
    public ImportResult importCsv(InputStream in) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .build();

        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            CsvColumns cols = new CsvColumns(parser.getHeaderNames());
            if (!cols.has("Name")) {
                throw new IllegalArgumentException("Not a customer export: the file has no 'Name' column");
            }

            List<String> warnings = new ArrayList<>();
            // Rows grouped per customer, in file order
            Map<String, List<CSVRecord>> groups = new LinkedHashMap<>();
            int rows = 0;
            for (CSVRecord r : parser) {
                rows++;
                String name = cols.get(r, "Name");
                if (name == null) {
                    warnings.add("Line " + r.getParser().getCurrentLineNumber() + ": no Name, skipped");
                    continue;
                }
                groups.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
            }

            Map<String, Customer> existing = new HashMap<>();
            for (Customer c : customerRepository.findAll()) {
                existing.putIfAbsent(c.getName().toLowerCase(Locale.ROOT), c);
            }

            int created = 0, updated = 0, contacts = 0;
            boolean hasContactColumns = CONTACT_COLUMNS.stream().anyMatch(cols::has);

            for (Map.Entry<String, List<CSVRecord>> group : groups.entrySet()) {
                List<CSVRecord> records = group.getValue();
                CSVRecord first = records.get(0);
                String name = cols.get(first, "Name");

                Customer customer = existing.get(group.getKey());
                boolean isNew = customer == null;
                if (isNew) customer = new Customer();

                CustomerRequest req = fromCustomer(customer);
                req.setName(isNew ? name : customer.getName());
                Line line = new Line(cols, first, name, warnings);
                line.text("Status", v -> req.setStatus(status(v, name, warnings)));
                line.text("Currency", req::setCurrency);
                line.text("PaymentTerm", req::setPaymentTerm);
                line.text("TaxRule", req::setTaxRule);
                line.text("TaxNumber", req::setTaxNumber);
                line.text("PriceTier", req::setPriceTier);
                line.decimal("Discount", req::setDiscount);
                line.decimal("CreditLimit", req::setCreditLimit);
                line.text("AccountReceivable", req::setAccountReceivable);
                line.text("SaleAccount", req::setSaleAccount);
                line.text("Carrier", req::setCarrier);
                line.text("SalesRepresentative", req::setSalesRepresentative);
                line.text("Location", req::setLocation);
                line.text("Tags", req::setTags);
                line.text("Comments", req::setNotes);

                if (hasContactColumns) {
                    List<CustomerRequest.Contact> fileContacts = new ArrayList<>();
                    for (CSVRecord r : records) {
                        CustomerRequest.Contact c = new CustomerRequest.Contact();
                        c.setName(cols.get(r, "ContactName"));
                        c.setJobTitle(cols.get(r, "JobTitle"));
                        c.setPhone(phone(cols.get(r, "Phone")));
                        c.setMobilePhone(phone(cols.get(r, "MobilePhone")));
                        c.setFax(phone(cols.get(r, "Fax")));
                        c.setEmail(cols.get(r, "Email"));
                        c.setWebsite(cols.get(r, "Website"));
                        c.setComment(cols.get(r, "ContactComment"));
                        c.setDefaultContact("true".equalsIgnoreCase(cols.get(r, "ContactDefault")));
                        c.setIncludeInEmail("true".equalsIgnoreCase(cols.get(r, "ContactIncludeInEmail")));
                        c.setMarketingConsent(consent(cols.get(r, "MarketingConsent")));
                        fileContacts.add(c);
                    }
                    req.setContacts(fileContacts);
                }

                customerService.apply(customer, req);
                customer.setUpdatedAt(LocalDateTime.now());
                if (isNew) {
                    customerRepository.save(customer);
                    existing.put(group.getKey(), customer);
                    created++;
                } else {
                    updated++;
                }
                contacts += customer.getContacts().size();
            }

            auditService.log("CUSTOMER", null, "IMPORT", Map.of(
                    "rows", rows, "created", created, "updated", updated, "contacts", contacts, "warnings", warnings.size()));
            return new ImportResult(rows, created, updated, contacts, warnings);
        }
    }

    /** Current values of a customer as a request, so columns absent from the file keep them. */
    private static CustomerRequest fromCustomer(Customer c) {
        CustomerRequest r = new CustomerRequest();
        r.setStatus(c.getStatus());
        r.setCurrency(c.getCurrency());
        r.setPaymentTerm(c.getPaymentTerm());
        r.setTaxRule(c.getTaxRule());
        r.setTaxNumber(c.getTaxNumber());
        r.setPriceTier(c.getPriceTier());
        r.setDiscount(c.getDiscount());
        r.setCreditLimit(c.getCreditLimit());
        r.setAccountReceivable(c.getAccountReceivable());
        r.setSaleAccount(c.getSaleAccount());
        r.setCarrier(c.getCarrier());
        r.setSalesRepresentative(c.getSalesRepresentative());
        r.setLocation(c.getLocation());
        r.setTags(c.getTags());
        r.setAddress(c.getAddress());
        r.setNotes(c.getNotes());
        r.setContacts(c.getContacts().stream().map(cc -> {
            CustomerRequest.Contact x = new CustomerRequest.Contact();
            x.setName(cc.getName());
            x.setJobTitle(cc.getJobTitle());
            x.setPhone(cc.getPhone());
            x.setMobilePhone(cc.getMobilePhone());
            x.setFax(cc.getFax());
            x.setEmail(cc.getEmail());
            x.setWebsite(cc.getWebsite());
            x.setComment(cc.getComment());
            x.setDefaultContact(cc.isDefaultContact());
            x.setIncludeInEmail(cc.isIncludeInEmail());
            x.setMarketingConsent(cc.getMarketingConsent());
            return x;
        }).toList());
        return r;
    }

    private static Customer.Status status(String v, String name, List<String> warnings) {
        if (v == null) return Customer.Status.ACTIVE;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "active" -> Customer.Status.ACTIVE;
            case "deprecated", "inactive" -> Customer.Status.DEPRECATED;
            default -> {
                warnings.add(name + ": unknown Status '" + v + "', set to Active");
                yield Customer.Status.ACTIVE;
            }
        };
    }

    private static CustomerContact.MarketingConsent consent(String v) {
        if (v == null) return CustomerContact.MarketingConsent.UNKNOWN;
        return switch (v.toLowerCase(Locale.ROOT).replace(" ", "")) {
            case "optin" -> CustomerContact.MarketingConsent.OPT_IN;
            case "optout" -> CustomerContact.MarketingConsent.OPT_OUT;
            default -> CustomerContact.MarketingConsent.UNKNOWN;
        };
    }

    // Spreadsheets prefix numbers like +44... with ' to keep them as text
    private static String phone(String v) {
        if (v == null) return null;
        String p = v.startsWith("'") ? v.substring(1).trim() : v;
        return p.isEmpty() ? null : p;
    }

    /** Header lookup that ignores case and surrounding spaces. */
    /** Applies the customer-level columns of one row; a column missing from the file is skipped. */
    private record Line(CsvColumns cols, CSVRecord record, String customerName, List<String> warnings) {

        void text(String column, Consumer<String> setter) {
            if (cols.has(column)) setter.accept(cols.get(record, column));
        }

        void decimal(String column, Consumer<BigDecimal> setter) {
            if (!cols.has(column)) return;
            String v = cols.get(record, column);
            try {
                // Cin7 writes thousands separators: "2,500.00"
                setter.accept(v == null ? null : new BigDecimal(v.replace(",", "")));
            } catch (NumberFormatException e) {
                warnings.add(customerName + ": " + column + " '" + v + "' is not a number, left empty");
                setter.accept(null);
            }
        }
    }
}
