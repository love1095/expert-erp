package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.dto.CustomerResponse;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.WarehouseRepository;
import com.experterp.backend.service.PaymentTermService.Method;
import com.experterp.backend.service.PaymentTermService.Term;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The "+" buttons on the sale form, like Cin7: a payment term and a sales representative are added to their lists
 * before they are used, a contact is added to the customer, and the order keeps the contact it was made for.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class QuickAddTest {

    private static final LocalDate OCT_30 = LocalDate.of(2026, 10, 30);

    @Autowired PaymentTermService terms;
    @Autowired CustomerService customers;
    @Autowired SaleOrderService saleOrders;
    @Autowired TenantRepository tenantRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ProductRepository productRepository;

    Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenant();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anAddedPaymentTermIsInTheListAndGivesTheDueDateByItsOwnRule() {
        // A name that says nothing about days: the rule comes from what was chosen in the pop-up
        assertThat(terms.add(" Net monthly ", Method.DAYS_EOM, 15)).isEqualTo(new Term("Net monthly", "15 days after the end of the invoice month"));
        terms.add("Fortnight", Method.DAYS, 14);
        terms.add("Pay now", Method.ON_INVOICE, null);
        assertThat(terms.list()).contains(
                new Term("Net monthly", "15 days after the end of the invoice month"),
                new Term("Fortnight", "14 days after the invoice date"),
                new Term("Pay now", "Due on the invoice date"),
                new Term("30 Day EOM", "30 days after the end of the invoice month"));      // Cin7's usual ones are still offered

        assertThat(terms.dueDate("net MONTHLY", OCT_30)).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(terms.dueDate("Fortnight", OCT_30)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(terms.dueDate("Pay now", OCT_30)).isEqualTo(OCT_30);
        // A term that was never added is still read from its name
        assertThat(terms.dueDate("60 Day", OCT_30)).isEqualTo(LocalDate.of(2026, 12, 29));
        assertThat(terms.dueDate(null, OCT_30)).isEqualTo(OCT_30);
    }

    @Test
    void aPaymentTermNeedsANewNameAndItsDays() {
        terms.add("Fortnight", Method.DAYS, 14);
        assertThatThrownBy(() -> terms.add("fortnight", Method.DAYS, 10)).hasMessageContaining("already a payment term");
        assertThatThrownBy(() -> terms.add("30 day eom", Method.DAYS_EOM, 30)).hasMessageContaining("already a payment term");   // a usual one
        assertThatThrownBy(() -> terms.add("  ", Method.DAYS, 10)).hasMessageContaining("Enter the name");
        assertThatThrownBy(() -> terms.add("Later", Method.DAYS, null)).hasMessageContaining("number of days");
        assertThatThrownBy(() -> terms.add("Never", Method.DAYS, 4000)).hasMessageContaining("number of days");
        assertThatThrownBy(() -> terms.add("No rule", null, 10)).hasMessageContaining("how the due date");

        // Another organisation has its own list
        TenantContext.set(tenant());
        assertThat(terms.list()).extracting(Term::name).doesNotContain("Fortnight");
        assertThat(terms.dueDate("Fortnight", OCT_30)).isEqualTo(OCT_30);
    }

    @Test
    void anAddedSalesRepIsInTheListOnceAndOnlyForItsOrganisation() {
        assertThat(customers.addSalesRep("  Priya Shah ")).isEqualTo("Priya Shah");
        assertThat(customers.salesReps()).contains("Priya Shah");
        assertThatThrownBy(() -> customers.addSalesRep("priya shah")).hasMessageContaining("already a sales representative");
        assertThatThrownBy(() -> customers.addSalesRep(" ")).hasMessageContaining("Enter the sales representative");

        TenantContext.set(tenant());
        assertThat(customers.salesReps()).doesNotContain("Priya Shah");
    }

    @Test
    void aContactIsAddedToTheCustomerAndTheOthersStay() {
        CustomerRequest req = new CustomerRequest();
        req.setName("Northgate Scaffolding");
        req.getContacts().add(contact("Sam Reed", "sam@northgate.test", "01632 960001", false));
        Long id = customers.create(req).getId();

        CustomerResponse after = customers.addContact(id, contact("Jo Park", "jo@northgate.test", null, false));
        assertThat(after.getContacts()).extracting(CustomerResponse.Contact::getName).containsExactly("Sam Reed", "Jo Park");
        assertThat(after.getContacts().get(0).isDefaultContact()).isTrue();

        // Added as the default: it takes over, the old default stays in the list
        customers.addContact(id, contact("Lee Wong", "lee@northgate.test", null, true));
        CustomerResponse reloaded = customers.findById(id);
        assertThat(reloaded.getContacts()).extracting(CustomerResponse.Contact::getName).containsExactly("Lee Wong", "Sam Reed", "Jo Park");
        assertThat(reloaded.getContacts()).filteredOn(CustomerResponse.Contact::isDefaultContact).hasSize(1);

        assertThatThrownBy(() -> customers.addContact(id, contact(" ", null, null, false))).hasMessageContaining("name, e-mail or phone");
    }

    @Test
    void theOrderKeepsTheContactChosenElseTheCustomersDefaultContact() {
        CustomerRequest req = new CustomerRequest();
        req.setName("Harbour Build Co");
        req.getContacts().add(contact("Sam Reed", "sam@harbour.test", "01632 960001", true));
        req.getContacts().add(contact("Jo Park", "jo@harbour.test", "01632 960002", false));
        Long customerId = customers.create(req).getId();

        // Nothing chosen: the default contact with its phone and e-mail
        SaleOrderResponse order = saleOrders.create(order(customerId));
        assertThat(order.getContactName()).isEqualTo("Sam Reed");
        assertThat(order.getContactPhone()).isEqualTo("01632 960001");
        assertThat(order.getContactEmail()).isEqualTo("sam@harbour.test");

        // Another contact chosen on the order: kept as typed, also after an edit
        SaleOrderRequest chosen = order(customerId);
        chosen.setContactName("Jo Park");
        chosen.setContactEmail("jo@harbour.test");
        SaleOrderResponse changed = saleOrders.update(order.getId(), chosen);
        assertThat(changed.getContactName()).isEqualTo("Jo Park");
        assertThat(changed.getContactPhone()).isNull();
        assertThat(changed.getContactEmail()).isEqualTo("jo@harbour.test");
        assertThat(saleOrders.findById(order.getId()).getContactName()).isEqualTo("Jo Park");
    }

    private SaleOrderRequest order(Long customerId) {
        String id = UUID.randomUUID().toString();
        Warehouse w = new Warehouse();
        w.setName("Main " + id);
        w = warehouseRepository.save(w);
        Product p = new Product();
        p.setSku("GLOVE-" + id);
        p.setName("Glove");
        p.setUnit("pcs");
        p = productRepository.save(p);
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customerId);
        req.setWarehouseId(w.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.ONE);
        l.setUnitPrice(new BigDecimal("5.00"));
        req.setLines(List.of(l));
        return req;
    }

    private static CustomerRequest.Contact contact(String name, String email, String phone, boolean isDefault) {
        CustomerRequest.Contact c = new CustomerRequest.Contact();
        c.setName(name);
        c.setEmail(email);
        c.setPhone(phone);
        c.setDefaultContact(isDefault);
        return c;
    }

    private Long tenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Quick add " + id);
        t.setSlug("quick-add-" + id);
        return tenantRepository.save(t).getId();
    }
}
