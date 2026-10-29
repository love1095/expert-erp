package com.experterp.backend.dto;

import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CustomerResponse {
    private Long id;
    private String name;
    private Customer.Status status;
    private String currency;
    private String paymentTerm;
    private String taxRule;
    private String taxNumber;
    private String priceTier;
    private BigDecimal discount;
    private BigDecimal creditLimit;
    private String accountReceivable;
    private String saleAccount;
    private String carrier;
    private String salesRepresentative;
    private String location;
    private String tags;
    private String address;
    private String notes;
    // Default contact first
    private List<Contact> contacts;
    private Customer.Source source;
    private String externalId;
    private long orderCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    public static class Contact {
        private Long id;
        private String name;
        private String jobTitle;
        private String phone;
        private String mobilePhone;
        private String fax;
        private String email;
        private String website;
        private String comment;
        private boolean defaultContact;
        private boolean includeInEmail;
        private CustomerContact.MarketingConsent marketingConsent;
    }
}
