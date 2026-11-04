package com.experterp.backend.service;

import com.experterp.backend.entity.Address;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerAddress;
import com.experterp.backend.entity.CustomerContact;
import com.experterp.backend.entity.CustomerPrice;
import com.experterp.backend.repository.CustomerPriceRepository;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.service.CustomerImportService.Kind;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The customers' files in Cin7 Core's own columns (customers, addresses, contacts, custom prices), so each can be
 * imported back with {@link CustomerImportService} (here, or into Cin7).
 */
@Service
@RequiredArgsConstructor
public class CustomerExportService {

    static final String[] CUSTOMERS = {"Name", "Status", "Currency", "PaymentTerm", "TaxRule", "AccountReceivable", "SaleAccount",
            "PriceTier", "Discount", "CreditLimit", "Carrier", "SalesRepresentative", "Location", "TaxNumber", "Tags", "Comments",
            "ContactName", "JobTitle", "Phone", "MobilePhone", "Fax", "Email", "Website", "ContactComment", "ContactDefault",
            "ContactIncludeInEmail", "MarketingConsent"};
    static final String[] ADDRESSES = {"Action", "Name", "AddressType", "AddressDefaultForType", "AddressLine1", "AddressLine2",
            "City", "State", "Postcode", "Country", "IsParent"};
    static final String[] CONTACTS = {"Action", "Customer", "Contact", "Phone", "MobilePhone", "JobTitle", "Fax", "Email", "Website",
            "Comment", "Default", "IncludeInEmails", "MarketingConsent"};
    static final String[] CUSTOM_PRICES = {"Action", "ProductSKU", "ProductName", "CustomerName", "Price"};

    private final CustomerRepository customerRepository;
    private final CustomerPriceRepository priceRepository;

    public String fileName(Kind kind) {
        String day = LocalDate.now().toString();
        return switch (kind) {
            case CUSTOMERS -> "Customers_" + day + ".csv";
            case ADDRESSES -> "CustomerAddresses_" + day + ".csv";
            case CONTACTS -> "CustomerContacts_" + day + ".csv";
            case CUSTOM_PRICES -> "CustomPrices_" + day + ".csv";
        };
    }

    @Transactional(readOnly = true)
    public void export(Kind kind, Writer out) throws IOException {
        Writer buffered = new BufferedWriter(out);
        // BOM so Excel opens it as UTF-8
        buffered.write('﻿');
        String[] header = switch (kind) {
            case CUSTOMERS -> CUSTOMERS;
            case ADDRESSES -> ADDRESSES;
            case CONTACTS -> CONTACTS;
            case CUSTOM_PRICES -> CUSTOM_PRICES;
        };
        CSVPrinter printer = new CSVPrinter(buffered, CSVFormat.DEFAULT.builder().setHeader(header).build());
        List<Customer> customers = customerRepository.findAll(Sort.by("name"));
        switch (kind) {
            case CUSTOMERS -> {
                for (Customer c : customers) {
                    List<Object> account = Arrays.asList(c.getName(), c.getStatus() == Customer.Status.ACTIVE ? "Active" : "Deprecated",
                            c.getCurrency(), c.getPaymentTerm(), c.getTaxRule(), c.getAccountReceivable(), c.getSaleAccount(),
                            c.getPriceTier(), number(c.getDiscount()), number(c.getCreditLimit()), c.getCarrier(), c.getSalesRepresentative(),
                            c.getLocation(), c.getTaxNumber(), c.getTags(), c.getNotes());
                    // One row per contact, the customer's columns repeated (Cin7's layout)
                    if (c.getContacts().isEmpty()) {
                        List<Object> row = new ArrayList<>(account);
                        row.addAll(Arrays.asList(new Object[11]));
                        printer.printRecord(row);
                    }
                    for (CustomerContact cc : c.getContacts()) {
                        List<Object> row = new ArrayList<>(account);
                        row.addAll(Arrays.asList(cc.getName(), cc.getJobTitle(), cc.getPhone(), cc.getMobilePhone(), cc.getFax(),
                                cc.getEmail(), cc.getWebsite(), cc.getComment(), bool(cc.isDefaultContact()), bool(cc.isIncludeInEmail()),
                                consent(cc.getMarketingConsent())));
                        printer.printRecord(row);
                    }
                }
            }
            case ADDRESSES -> {
                for (Customer c : customers) {
                    for (CustomerAddress ca : c.getAddresses()) {
                        Address a = ca.getAddress();
                        printer.printRecord("Create/Update", c.getName(), type(ca.getType()), bool(ca.isDefaultForType()),
                                a.getLine1(), a.getLine2(), a.getCity(), a.getState(), a.getPostcode(), a.getCountry(), "False");
                    }
                }
            }
            case CONTACTS -> {
                for (Customer c : customers) {
                    for (CustomerContact cc : c.getContacts()) {
                        printer.printRecord("Create/Update", c.getName(), cc.getName(), cc.getPhone(), cc.getMobilePhone(),
                                cc.getJobTitle(), cc.getFax(), cc.getEmail(), cc.getWebsite(), cc.getComment(),
                                bool(cc.isDefaultContact()), bool(cc.isIncludeInEmail()), consent(cc.getMarketingConsent()));
                    }
                }
            }
            case CUSTOM_PRICES -> {
                // The contract prices valid today (Cin7's custom prices have no dates)
                Map<Long, Customer> byId = customers.stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
                LocalDate today = LocalDate.now();
                List<CustomerPrice> prices = priceRepository.findAll().stream()
                        .filter(cp -> cp.validOn(today) && byId.containsKey(cp.getCustomerId()))
                        .sorted(Comparator.comparing((CustomerPrice cp) -> cp.getProduct().getSku())
                                .thenComparing(cp -> byId.get(cp.getCustomerId()).getName()))
                        .toList();
                for (CustomerPrice cp : prices) {
                    printer.printRecord("", cp.getProduct().getSku(), cp.getProduct().getName(), byId.get(cp.getCustomerId()).getName(),
                            cp.getUnitPrice().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
                }
            }
        }
        printer.flush();
    }

    private static String bool(boolean b) {
        return b ? "True" : "False";
    }

    private static String number(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String type(CustomerAddress.Type t) {
        return switch (t) {
            case BILLING -> "Billing";
            case BUSINESS -> "Business";
            case SHIPPING -> "Shipping";
        };
    }

    private static String consent(CustomerContact.MarketingConsent m) {
        if (m == null) return "Unknown";
        return switch (m) {
            case OPT_IN -> "Opt In";
            case OPT_OUT -> "Opt Out";
            case UNKNOWN -> "Unknown";
        };
    }
}
