package com.experterp.backend.service;

import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact;
import com.experterp.backend.entity.CustomerPrice;
import com.experterp.backend.entity.Product;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * Imports Cin7 Core's customer files, told apart by their columns:
 * <ul>
 *   <li>the customer export (Customers_YYYY-MM-DD.csv), described below;</li>
 *   <li>customer addresses (CustomerAddresses_YYYY-MM-DD.csv: Name, AddressType, AddressDefaultForType, AddressLine1 …):
 *       each customer in the file gets the file's addresses as their address book (G4);</li>
 *   <li>customer contacts (CustomerContacts_*.csv: Customer, Contact, Phone …): each customer in the file gets the
 *       file's contacts;</li>
 *   <li>custom prices (CustomPrices_YYYY-MM-DD.csv: ProductSKU, CustomerName, Price): Cin7's customer prices have no
 *       dates, so each becomes a contract price (G1) from today with no end. A price already valid today stays when it
 *       is the same; a different one is ended yesterday and the file's price starts today.</li>
 * </ul>
 * Addresses and contacts are only for customers that exist: unknown names are reported, not created.
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
    private final com.experterp.backend.repository.ProductRepository productRepository;
    private final com.experterp.backend.repository.CustomerPriceRepository priceRepository;

    public record ImportResult(int rows, int customersCreated, int customersUpdated, int contacts, int addresses, int prices,
                               List<String> warnings) {}

    /** Cin7's customer files, as chosen on the Import menu. */
    public enum Kind {
        CUSTOMERS("customers"), ADDRESSES("customer addresses"), CONTACTS("customer contacts"), CUSTOM_PRICES("custom prices");
        final String label;
        Kind(String label) { this.label = label; }
    }

    /** Which of Cin7's customer files this is, from its columns (null: none of them). */
    static Kind kindOf(CsvColumns cols) {
        if (cols.has("AddressType") && cols.has("Name")) return Kind.ADDRESSES;
        if (cols.has("Customer") && cols.has("Contact")) return Kind.CONTACTS;
        if (cols.has("ProductSKU") && cols.has("CustomerName") && cols.has("Price")) return Kind.CUSTOM_PRICES;
        if (cols.has("Name")) return Kind.CUSTOMERS;
        return null;
    }

    @Transactional
    public ImportResult importCsv(InputStream in) throws IOException {
        return importCsv(in, null);
    }

    /** {@code expected}: the kind chosen on the menu; a file of another kind is refused, nothing imported. */
    @Transactional
    public ImportResult importCsv(InputStream in, Kind expected) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .build();

        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            CsvColumns cols = new CsvColumns(parser.getHeaderNames());
            Kind kind = kindOf(cols);
            if (expected != null && kind != expected) {
                throw new IllegalArgumentException(kind == null
                        ? "This is not Cin7's " + expected.label + " file: its columns are not the ones Cin7 exports"
                        : "This is Cin7's " + kind.label + " file, not " + expected.label + ": choose Import → " + menuName(kind));
            }
            if (kind == Kind.ADDRESSES) return importAddresses(parser, cols);
            if (kind == Kind.CONTACTS) return importContacts(parser, cols);
            if (kind == Kind.CUSTOM_PRICES) return importCustomPrices(parser, cols);
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
            return new ImportResult(rows, created, updated, contacts, 0, 0, warnings);
        }
    }

    private static String menuName(Kind k) {
        return switch (k) {
            case CUSTOMERS -> "Customers";
            case ADDRESSES -> "Addresses";
            case CONTACTS -> "Contacts";
            case CUSTOM_PRICES -> "Custom prices";
        };
    }

    // ---- Cin7's customer addresses file (G4) ----

    private ImportResult importAddresses(CSVParser parser, CsvColumns cols) {
        List<String> warnings = new ArrayList<>();
        Map<String, List<CSVRecord>> groups = group(parser, cols, "Name", warnings);
        Map<String, Customer> byName = byName();
        int rows = groups.values().stream().mapToInt(List::size).sum(), updated = 0, addresses = 0;
        for (Map.Entry<String, List<CSVRecord>> g : groups.entrySet()) {
            Customer customer = byName.get(g.getKey());
            if (customer == null) {
                warnings.add("'" + cols.get(g.getValue().get(0), "Name") + "' is not a customer here: its " + g.getValue().size() + " address(es) skipped");
                continue;
            }
            List<CustomerRequest.Address> book = new ArrayList<>();
            for (CSVRecord r : g.getValue()) {
                CustomerRequest.Address a = new CustomerRequest.Address();
                a.setLine1(cols.get(r, "AddressLine1"));
                a.setLine2(cols.get(r, "AddressLine2"));
                a.setCity(cols.get(r, "City"));
                a.setState(cols.get(r, "State"));
                a.setPostcode(cols.get(r, "Postcode"));
                a.setCountry(cols.get(r, "Country"));
                a.setType(addressType(cols.get(r, "AddressType"), customer.getName(), warnings));
                a.setDefaultForType("true".equalsIgnoreCase(cols.get(r, "AddressDefaultForType")));
                book.add(a);
            }
            CustomerRequest req = fromCustomer(customer);
            req.setName(customer.getName());
            req.setAddresses(book);
            customerService.apply(customer, req);
            customer.setUpdatedAt(LocalDateTime.now());
            updated++;
            addresses += customer.getAddresses().size();
        }
        auditService.log("CUSTOMER", null, "IMPORT_ADDRESSES", Map.of(
                "rows", rows, "customers", updated, "addresses", addresses, "warnings", warnings.size()));
        return new ImportResult(rows, 0, updated, 0, addresses, 0, warnings);
    }

    private static com.experterp.backend.entity.CustomerAddress.Type addressType(String v, String name, List<String> warnings) {
        if (v == null) return com.experterp.backend.entity.CustomerAddress.Type.BILLING;
        try {
            return com.experterp.backend.entity.CustomerAddress.Type.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add(name + ": address type '" + v + "' unknown, kept as Billing");
            return com.experterp.backend.entity.CustomerAddress.Type.BILLING;
        }
    }

    // ---- Cin7's customer contacts file ----

    private ImportResult importContacts(CSVParser parser, CsvColumns cols) {
        List<String> warnings = new ArrayList<>();
        Map<String, List<CSVRecord>> groups = group(parser, cols, "Customer", warnings);
        Map<String, Customer> byName = byName();
        int rows = groups.values().stream().mapToInt(List::size).sum(), updated = 0, contacts = 0;
        for (Map.Entry<String, List<CSVRecord>> g : groups.entrySet()) {
            Customer customer = byName.get(g.getKey());
            if (customer == null) {
                warnings.add("'" + cols.get(g.getValue().get(0), "Customer") + "' is not a customer here: its " + g.getValue().size() + " contact(s) skipped");
                continue;
            }
            List<CustomerRequest.Contact> list = new ArrayList<>();
            for (CSVRecord r : g.getValue()) {
                CustomerRequest.Contact c = new CustomerRequest.Contact();
                c.setName(cols.get(r, "Contact"));
                c.setJobTitle(cols.get(r, "JobTitle"));
                c.setPhone(phone(cols.get(r, "Phone")));
                c.setMobilePhone(phone(cols.get(r, "MobilePhone")));
                c.setFax(phone(cols.get(r, "Fax")));
                c.setEmail(cols.get(r, "Email"));
                c.setWebsite(cols.get(r, "Website"));
                c.setComment(cols.get(r, "Comment"));
                c.setDefaultContact("true".equalsIgnoreCase(cols.get(r, "Default")));
                c.setIncludeInEmail("true".equalsIgnoreCase(cols.get(r, "IncludeInEmails")));
                c.setMarketingConsent(consent(cols.get(r, "MarketingConsent")));
                list.add(c);
            }
            CustomerRequest req = fromCustomer(customer);
            req.setName(customer.getName());
            req.setContacts(list);
            customerService.apply(customer, req);
            customer.setUpdatedAt(LocalDateTime.now());
            updated++;
            contacts += customer.getContacts().size();
        }
        auditService.log("CUSTOMER", null, "IMPORT_CONTACTS", Map.of(
                "rows", rows, "customers", updated, "contacts", contacts, "warnings", warnings.size()));
        return new ImportResult(rows, 0, updated, contacts, 0, 0, warnings);
    }

    // ---- Cin7's custom prices file → contract prices (G1) ----

    private ImportResult importCustomPrices(CSVParser parser, CsvColumns cols) {
        List<String> warnings = new ArrayList<>();
        Map<String, Customer> byName = byName();
        Map<String, Product> bySku = new HashMap<>();
        LocalDate today = LocalDate.now();
        int rows = 0, added = 0, changed = 0, unchanged = 0;
        Set<Long> customers = new HashSet<>();
        for (CSVRecord r : parser) {
            rows++;
            String sku = cols.get(r, "ProductSKU"), name = cols.get(r, "CustomerName"), priceText = cols.get(r, "Price");
            long line = parser.getCurrentLineNumber();
            Customer customer = name == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
            if (customer == null) { warnings.add("Line " + line + ": customer '" + name + "' is not here, skipped"); continue; }
            Product product = sku == null ? null : bySku.computeIfAbsent(sku, k -> productRepository.findBySku(k).orElse(null));
            if (product == null) { warnings.add("Line " + line + ": SKU '" + sku + "' is not a product here, skipped"); continue; }
            BigDecimal price;
            try {
                price = new BigDecimal(priceText.trim());
            } catch (RuntimeException e) {
                warnings.add("Line " + line + ": price '" + priceText + "' is not a number, skipped");
                continue;
            }
            if (price.signum() < 0) { warnings.add("Line " + line + ": negative price, skipped"); continue; }

            List<CustomerPrice> existing = priceRepository.findByCustomerIdAndProductId(customer.getId(), product.getId());
            CustomerPrice current = existing.stream().filter(cp -> cp.validOn(today)).findFirst().orElse(null);
            if (current != null && current.getUnitPrice().compareTo(price) == 0) { unchanged++; continue; }
            boolean later = existing.stream().anyMatch(cp -> cp.getValidFrom().isAfter(today));
            if (later) {
                warnings.add("Line " + line + ": " + customer.getName() + " already has a future contract price for " + sku + ", skipped");
                continue;
            }
            if (current != null && current.getValidFrom().equals(today)) {
                current.setUnitPrice(price);
            } else {
                if (current != null) current.setValidTo(today.minusDays(1));
                CustomerPrice cp = new CustomerPrice();
                cp.setCustomerId(customer.getId());
                cp.setProduct(product);
                cp.setUnitPrice(price);
                cp.setValidFrom(today);
                cp.setNote("Cin7 custom price");
                cp.setCreatedBy(auditService.currentActorName());
                priceRepository.save(cp);
            }
            if (current != null) changed++; else added++;
            customers.add(customer.getId());
        }
        auditService.log("CUSTOMER", null, "IMPORT_CUSTOM_PRICES", Map.of("rows", rows, "added", added, "changed", changed,
                "unchanged", unchanged, "customers", customers.size(), "warnings", warnings.size()));
        if (unchanged > 0) warnings.add(0, unchanged + " price(s) were already the same, left as they are");
        return new ImportResult(rows, 0, customers.size(), 0, 0, added + changed, warnings);
    }

    // Rows per customer name (ignoring case), in file order
    private static Map<String, List<CSVRecord>> group(CSVParser parser, CsvColumns cols, String column, List<String> warnings) {
        Map<String, List<CSVRecord>> groups = new LinkedHashMap<>();
        for (CSVRecord r : parser) {
            String name = cols.get(r, column);
            if (name == null) {
                warnings.add("Line " + parser.getCurrentLineNumber() + ": no " + column + ", skipped");
                continue;
            }
            groups.computeIfAbsent(name.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
        }
        return groups;
    }

    private Map<String, Customer> byName() {
        Map<String, Customer> m = new HashMap<>();
        for (Customer c : customerRepository.findAll()) m.putIfAbsent(c.getName().trim().toLowerCase(Locale.ROOT), c);
        return m;
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
