package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.dto.CustomerResponse;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact.MarketingConsent;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Imports src/test/resources/customers/cin7-customers.csv: made-up rows in the Cin7 Core export layout. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerImportServiceTest {

    @Autowired CustomerImportService importService;
    @Autowired CustomerService customerService;
    @Autowired PricingService pricingService;
    @Autowired CustomerExportService exportService;
    @Autowired com.experterp.backend.repository.ProductRepository productRepository;
    @Autowired TenantRepository tenantRepository;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Import " + id);
        t.setSlug("import-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void importsOneCustomerPerNameWithItsContacts() throws IOException {
        CustomerImportService.ImportResult result = importService.importCsv(fixture());

        assertThat(result.rows()).isEqualTo(4);
        assertThat(result.customersCreated()).isEqualTo(3);
        assertThat(result.customersUpdated()).isZero();
        assertThat(result.contacts()).isEqualTo(3);

        CustomerResponse northwind = byName("Northwind Scaffolding Ltd");
        assertThat(northwind.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(northwind.getCreditLimit()).isEqualByComparingTo("2500");
        assertThat(northwind.getPaymentTerm()).isEqualTo("30 Day EOM");
        assertThat(northwind.getPriceTier()).isEqualTo("Contract");
        assertThat(northwind.getTaxRule()).isEqualTo("20% (VAT on Income)");
        assertThat(northwind.getTaxNumber()).isEqualTo("GB123456789");
        assertThat(northwind.getAccountReceivable()).isEqualTo("610");
        assertThat(northwind.getCarrier()).isEqualTo("Own Van");
        assertThat(northwind.getSalesRepresentative()).isEqualTo("Rep One");
        assertThat(northwind.getNotes()).isEqualTo("Called 11/08 - quoted for hard hats\nFollow up next week");

        assertThat(northwind.getContacts()).hasSize(2);
        CustomerResponse.Contact pat = northwind.getContacts().get(0);
        assertThat(pat.getName()).isEqualTo("Pat Jones");
        assertThat(pat.isDefaultContact()).isTrue();
        assertThat(pat.getPhone()).isEqualTo("+447700900001");   // spreadsheet ' removed
        assertThat(pat.getJobTitle()).isEqualTo("Buyer");
        assertThat(pat.isIncludeInEmail()).isTrue();
        assertThat(pat.getMarketingConsent()).isEqualTo(MarketingConsent.OPT_IN);
        assertThat(northwind.getContacts().get(1).getMobilePhone()).isEqualTo("07700900002");

        CustomerResponse oldMill = byName("Old Mill Plumbing");
        assertThat(oldMill.getStatus()).isEqualTo(Customer.Status.DEPRECATED);
        // No contact was marked default, so the only one becomes the default
        assertThat(oldMill.getContacts()).singleElement()
                .satisfies(c -> assertThat(c.isDefaultContact()).isTrue())
                .satisfies(c -> assertThat(c.getMarketingConsent()).isEqualTo(MarketingConsent.OPT_OUT));

        CustomerResponse quiet = byName("Quiet Co");
        assertThat(quiet.getContacts()).isEmpty();
        assertThat(quiet.getCreditLimit()).isNull();
        assertThat(result.warnings()).singleElement().asString().contains("Quiet Co", "CreditLimit", "lots");
    }

    @Test
    void importingTheSameFileAgainUpdatesInsteadOfDuplicating() throws IOException {
        importService.importCsv(fixture());
        CustomerResponse before = byName("Northwind Scaffolding Ltd");

        CustomerImportService.ImportResult again = importService.importCsv(fixture());

        assertThat(again.customersCreated()).isZero();
        assertThat(again.customersUpdated()).isEqualTo(3);
        assertThat(customerService.findAll("", 0, 50).getTotalElements()).isEqualTo(3);
        CustomerResponse after = byName("Northwind Scaffolding Ltd");
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getContacts()).hasSize(2);
    }

    @Test
    void anExistingCustomerIsMatchedIgnoringCaseAndKeepsFieldsTheFileDoesNotHave() throws IOException {
        CustomerRequest req = new CustomerRequest();
        req.setName("OLD MILL PLUMBING");
        CustomerRequest.Address mill = new CustomerRequest.Address();
        mill.setLine1("1 Mill Lane");
        req.setAddresses(List.of(mill));
        Long id = customerService.create(req).getId();

        importService.importCsv(csv("Name,Status,PaymentTerm\nOld Mill Plumbing,Active,60 Day EOM\n"));

        CustomerResponse c = customerService.findById(id);
        assertThat(c.getName()).isEqualTo("OLD MILL PLUMBING");
        assertThat(c.getPaymentTerm()).isEqualTo("60 Day EOM");
        assertThat(c.getAddress()).isEqualTo("1 Mill Lane");
        assertThat(c.getAddresses()).singleElement().satisfies(a -> assertThat(a.isDefaultForType()).isTrue());
    }

    @Test
    void cin7sAddressFileFillsTheAddressBooksOfKnownCustomers() throws IOException {
        CustomerRequest req = new CustomerRequest();
        req.setName("A Forrest");
        Long id = customerService.create(req).getId();
        String file = """
                Action,Name,AddressType,AddressDefaultForType,AddressLine1,AddressLine2,City,State,Postcode,Country,IsParent
                "Create/Update","A Forrest",Billing,True,"18 Crescent Road","","Sheffield","ENG","S7 1HL","United Kingdom","False",""
                "Create/Update","a forrest",Shipping,False,"Unit 2, Mill Yard","","Rotherham","","S60 1AA","United Kingdom","False",""
                "Create/Update","A Forrest",Shipping,True,"18 Crescent Road","","Sheffield","ENG","S7 1HL","United Kingdom","False",""
                "Create/Update","Nobody Ltd",Billing,True,"1 Lost Lane","","Leeds","","LS1","United Kingdom","False",""
                """;

        CustomerImportService.ImportResult r = importService.importCsv(csv(file));
        assertThat(r.customersUpdated()).isEqualTo(1);
        assertThat(r.customersCreated()).isZero();
        assertThat(r.addresses()).isEqualTo(3);
        assertThat(r.warnings()).singleElement().asString().contains("Nobody Ltd", "skipped");

        CustomerResponse c = customerService.findById(id);
        assertThat(c.getAddress()).isEqualTo("18 Crescent Road, Sheffield ENG S7 1HL, United Kingdom");
        assertThat(c.getAddresses()).filteredOn(a -> a.getType() == com.experterp.backend.entity.CustomerAddress.Type.SHIPPING)
                .extracting(CustomerResponse.Address::getLine1, CustomerResponse.Address::isDefaultForType)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Unit 2, Mill Yard", false),
                        org.assertj.core.groups.Tuple.tuple("18 Crescent Road", true));
        // The same file again gives the same address book
        importService.importCsv(csv(file));
        assertThat(customerService.findById(id).getAddresses()).hasSize(3);
    }

    @Test
    void cin7sContactsFileReplacesTheContactsOfKnownCustomers() throws IOException {
        CustomerRequest req = new CustomerRequest();
        req.setName("A M E Services Ltd");
        Long id = customerService.create(req).getId();
        importService.importCsv(csv("""
                Action,Customer,Contact,Phone,MobilePhone,JobTitle,Fax,Email,Website,Comment,Default,IncludeInEmails,MarketingConsent
                "Create/Update","A M E Services Ltd",Dan Henshaw,01706861029,"","","","","","","True","False","Unknown",""
                "Create/Update","A M E Services Ltd",Accounts,"","","","","accounts@ame.test","","","False","True","Unknown",""
                """));
        CustomerResponse c = customerService.findById(id);
        assertThat(c.getContacts()).extracting(CustomerResponse.Contact::getName).containsExactly("Dan Henshaw", "Accounts");
        assertThat(c.getContacts().get(1).isIncludeInEmail()).isTrue();
    }

    @Test
    void cin7sCustomPricesBecomeContractPricesFromTodayAndAChangedOneReplacesTheOld() throws IOException {
        CustomerRequest req = new CustomerRequest();
        req.setName("AE Yates Ltd");
        Long id = customerService.create(req).getId();
        Product mask = new Product();
        mask.setSku("*RPDM-" + UUID.randomUUID().toString().substring(0, 6) + "*");
        mask.setName("Respirator *Expired*");
        mask.setUnit("Box");
        mask = productRepository.save(mask);
        String file = "Action,ProductSKU,ProductName,CustomerName,Price\n"
                + ",\"" + mask.getSku() + "\",\"Respirator\",AE Yates Ltd,5.5000\n"
                + ",NO-SUCH-SKU,Thing,AE Yates Ltd,1.00\n";

        CustomerImportService.ImportResult r = importService.importCsv(csv(file));
        assertThat(r.prices()).isEqualTo(1);
        assertThat(r.warnings()).singleElement().asString().contains("NO-SUCH-SKU");
        List<PricingService.ContractPriceView> prices = pricingService.contractPrices(id);
        assertThat(prices).singleElement().satisfies(p -> {
            assertThat(p.unitPrice()).isEqualByComparingTo("5.50");
            assertThat(p.validFrom()).isEqualTo(java.time.LocalDate.now());
            assertThat(p.validTo()).isNull();
        });

        // Same file again: nothing changes; a new price today replaces today's
        assertThat(importService.importCsv(csv(file)).prices()).isZero();
        importService.importCsv(csv(file.replace("5.5000", "5.2500")));
        assertThat(pricingService.contractPrices(id)).singleElement().satisfies(p -> assertThat(p.unitPrice()).isEqualByComparingTo("5.25"));
    }

    @Test
    void eachExportImportsBackAsTheSameKindAndAFileOfAnotherKindIsRefused() throws IOException {
        String addresses = """
                Action,Name,AddressType,AddressDefaultForType,AddressLine1,AddressLine2,City,State,Postcode,Country,IsParent
                "Create/Update","Round Trip Ltd",Billing,True,"1 Mill Lane","","Bury","","BL9 9SX","United Kingdom","False"
                "Create/Update","Round Trip Ltd",Shipping,True,"Yard 2","","Bolton","","","United Kingdom","False"
                """;
        CustomerRequest req = new CustomerRequest();
        req.setName("Round Trip Ltd");
        CustomerRequest.Contact dan = new CustomerRequest.Contact();
        dan.setName("Dan");
        dan.setEmail("dan@trip.test");
        dan.setDefaultContact(true);
        req.setContacts(List.of(dan));
        Long id = customerService.create(req).getId();

        assertThatThrownBy(() -> importService.importCsv(csv(addresses), CustomerImportService.Kind.CUSTOMERS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customer addresses file, not customers")
                .hasMessageContaining("Import → Addresses");
        assertThat(customerService.findById(id).getAddresses()).as("nothing imported").isEmpty();
        importService.importCsv(csv(addresses), CustomerImportService.Kind.ADDRESSES);

        for (CustomerImportService.Kind kind : CustomerImportService.Kind.values()) {
            java.io.StringWriter out = new java.io.StringWriter();
            exportService.export(kind, out);
            String file = out.toString();
            assertThat(file).as(kind.name()).contains(kind == CustomerImportService.Kind.CUSTOM_PRICES ? "ProductSKU" : "Round Trip Ltd");
            CustomerImportService.ImportResult r = importService.importCsv(csv(file), kind);
            assertThat(r.warnings()).as(kind.name()).isEmpty();
        }
        CustomerResponse c = customerService.findById(id);
        assertThat(c.getAddresses()).hasSize(2);
        assertThat(c.getContacts()).singleElement().satisfies(x -> assertThat(x.getEmail()).isEqualTo("dan@trip.test"));
    }

    @Test
    void aFileWithoutANameColumnIsRejected() {
        assertThatThrownBy(() -> importService.importCsv(csv("Sku,Qty\nA,1\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Name' column");
    }

    private CustomerResponse byName(String name) {
        return customerService.findAll(name, 0, 5).getContent().stream()
                .filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    private InputStream fixture() {
        return getClass().getResourceAsStream("/customers/cin7-customers.csv");
    }

    private static InputStream csv(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
