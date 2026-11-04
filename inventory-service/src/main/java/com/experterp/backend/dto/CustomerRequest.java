package com.experterp.backend.dto;

import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class CustomerRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 255)
    private String name;

    private Customer.Status status;

    @Size(max = 3)
    private String currency;
    private String paymentTerm;
    private String taxRule;
    private String taxNumber;
    private String priceTier;

    @DecimalMin("0") @DecimalMax("100")
    private BigDecimal discount;

    @PositiveOrZero
    private BigDecimal creditLimit;

    private String accountReceivable;
    private String saleAccount;
    private String carrier;
    private String salesRepresentative;
    private String location;
    private String tags;
    private String notes;

    // Phase G4: replaces the customer's address book; null leaves it as it is (e.g. the CSV import)
    @Valid
    private List<Address> addresses;

    // Replaces all of the customer's contacts
    @Valid
    private List<Contact> contacts = new ArrayList<>();

    @Data
    public static class Address {
        @Size(max = 255) private String line1;
        @Size(max = 255) private String line2;
        @Size(max = 100) private String city;
        @Size(max = 100) private String state;
        @Size(max = 30) private String postcode;
        @Size(max = 100) private String country;
        private com.experterp.backend.entity.CustomerAddress.Type type = com.experterp.backend.entity.CustomerAddress.Type.BILLING;
        private boolean defaultForType;
    }

    @Data
    public static class Contact {
        private String name;
        private String jobTitle;
        private String phone;
        private String mobilePhone;
        private String fax;
        @Email(message = "Contact email is not valid")
        private String email;
        private String website;
        private String comment;
        private boolean defaultContact;
        private boolean includeInEmail;
        private CustomerContact.MarketingConsent marketingConsent;
    }
}
