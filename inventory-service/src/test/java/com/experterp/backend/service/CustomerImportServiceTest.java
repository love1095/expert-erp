package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.dto.CustomerResponse;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact.MarketingConsent;
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
        req.setAddress("1 Mill Lane");
        Long id = customerService.create(req).getId();

        importService.importCsv(csv("Name,Status,PaymentTerm\nOld Mill Plumbing,Active,60 Day EOM\n"));

        CustomerResponse c = customerService.findById(id);
        assertThat(c.getName()).isEqualTo("OLD MILL PLUMBING");
        assertThat(c.getPaymentTerm()).isEqualTo("60 Day EOM");
        assertThat(c.getAddress()).isEqualTo("1 Mill Lane");
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
