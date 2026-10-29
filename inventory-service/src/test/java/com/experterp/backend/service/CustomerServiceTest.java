package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.dto.CustomerResponse;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerServiceTest {

    @Autowired CustomerService customerService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Long tenantA;
    Long tenantB;
    Warehouse warehouse;
    Product product;

    @BeforeEach
    void setUp() {
        tenantA = newTenant();
        tenantB = newTenant();
        TenantContext.set(tenantA);

        warehouse = new Warehouse();
        warehouse.setName("WH-" + UUID.randomUUID());
        warehouse = warehouseRepository.save(warehouse);

        product = new Product();
        product.setSku("SKU-" + UUID.randomUUID());
        product.setName("Helmet");
        product.setUnit("pcs");
        product = productRepository.save(product);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createUpdateAndSearch() {
        CustomerResponse created = customerService.create(request("  Acme Ltd ", "buy@acme.test"));
        assertThat(created.getName()).isEqualTo("Acme Ltd");
        assertThat(created.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(created.getSource()).isEqualTo(Customer.Source.MANUAL);
        assertThat(created.getOrderCount()).isZero();
        assertThat(created.getContacts()).singleElement()
                .satisfies(c -> assertThat(c.getEmail()).isEqualTo("buy@acme.test"))
                .satisfies(c -> assertThat(c.isDefaultContact()).isTrue());

        CustomerRequest change = request("Acme Limited", null);
        change.setStatus(Customer.Status.DEPRECATED);
        CustomerResponse updated = customerService.update(created.getId(), change);
        assertThat(updated.getName()).isEqualTo("Acme Limited");
        assertThat(updated.getStatus()).isEqualTo(Customer.Status.DEPRECATED);
        assertThat(updated.getContacts()).isEmpty();

        assertThat(customerService.findAll("limited", 0, 20).getContent())
                .extracting(CustomerResponse::getId).containsExactly(created.getId());
        assertThat(customerService.findAll("nobody", 0, 20).getContent()).isEmpty();
    }

    @Test
    void searchFindsCustomersByContactDetailsAndKeepsOneDefaultContact() {
        CustomerRequest req = request("Builders Co", null);
        req.getContacts().add(contact("Jo Smith", "jo@builders.test", false));
        req.getContacts().add(contact("Sam Lee", "sam@builders.test", true));
        req.getContacts().add(contact("Al Green", null, true));
        CustomerResponse created = customerService.create(req);

        // The first contact marked default wins and is listed first
        assertThat(created.getContacts()).extracting(CustomerResponse.Contact::getName)
                .containsExactly("Sam Lee", "Jo Smith", "Al Green");
        assertThat(created.getContacts()).filteredOn(CustomerResponse.Contact::isDefaultContact).hasSize(1);

        assertThat(customerService.findAll("sam@builders", 0, 20).getContent())
                .extracting(CustomerResponse::getId).containsExactly(created.getId());
        assertThat(customerService.findAll("jo smith", 0, 20).getContent())
                .extracting(CustomerResponse::getId).containsExactly(created.getId());
    }

    @Test
    void orderByIdUsesThatCustomerAndKeepsTheNameAtOrderTime() {
        CustomerResponse acme = customerService.create(request("Acme", null));

        SaleOrderResponse so = saleOrderService.create(order(acme.getId(), null));
        assertThat(so.getCustomerId()).isEqualTo(acme.getId());
        assertThat(so.getCustomerName()).isEqualTo("Acme");

        customerService.update(acme.getId(), request("Acme Renamed", null));
        assertThat(saleOrderService.findById(so.getId()).getCustomerName()).isEqualTo("Acme");
        assertThat(customerService.findById(acme.getId()).getOrderCount()).isEqualTo(1);
    }

    @Test
    void orderByNameReusesAnExistingCustomerIgnoringCaseOrCreatesOne() {
        CustomerResponse acme = customerService.create(request("Acme", null));

        SaleOrderResponse reused = saleOrderService.create(order(null, "ACME"));
        assertThat(reused.getCustomerId()).isEqualTo(acme.getId());

        SaleOrderResponse created = saleOrderService.create(order(null, "Brand New Co"));
        assertThat(created.getCustomerId()).isNotEqualTo(acme.getId());
        assertThat(customerService.findById(created.getCustomerId()).getName()).isEqualTo("Brand New Co");

        assertThat(saleOrderService.findAll(acme.getId(), 0, 20).getContent())
                .extracting(SaleOrderResponse::getId).containsExactly(reused.getId());
    }

    @Test
    void customerWithOrdersCannotBeDeleted() {
        CustomerResponse acme = customerService.create(request("Acme", null));
        saleOrderService.create(order(acme.getId(), null));

        assertThatThrownBy(() -> customerService.delete(acme.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 sale order(s) and cannot be deleted");

        CustomerResponse unused = customerService.create(request("Unused", null));
        customerService.delete(unused.getId());
        assertThat(customerRepository.findById(unused.getId())).isEmpty();
    }

    @Test
    void customersAreIsolatedPerTenant() {
        CustomerResponse acme = customerService.create(request("Acme", null));

        TenantContext.set(tenantB);
        assertThat(customerService.findAll("", 0, 20).getContent()).isEmpty();
        assertThatThrownBy(() -> customerService.findById(acme.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        // Same name in another tenant creates that tenant's own customer
        assertThat(customerService.resolveForOrder(null, "Acme").getId()).isNotEqualTo(acme.getId());
    }

    @Test
    void externalIdsAreUniquePerSourceWithinATenant() {
        Customer shopify = new Customer();
        shopify.setName("Shopify buyer");
        shopify.setSource(Customer.Source.SHOPIFY);
        shopify.setExternalId("gid://shopify/Customer/1");
        customerRepository.saveAndFlush(shopify);

        assertThat(customerRepository.findBySourceAndExternalId(Customer.Source.SHOPIFY, "gid://shopify/Customer/1"))
                .map(Customer::getId).contains(shopify.getId());

        Customer duplicate = new Customer();
        duplicate.setName("Duplicate");
        duplicate.setSource(Customer.Source.SHOPIFY);
        duplicate.setExternalId("gid://shopify/Customer/1");
        assertThatThrownBy(() -> customerRepository.saveAndFlush(duplicate))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Long newTenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Test " + id);
        t.setSlug("test-" + id);
        return tenantRepository.save(t).getId();
    }

    private CustomerRequest request(String name, String email) {
        CustomerRequest r = new CustomerRequest();
        r.setName(name);
        if (email != null) r.getContacts().add(contact(null, email, false));
        return r;
    }

    private CustomerRequest.Contact contact(String name, String email, boolean isDefault) {
        CustomerRequest.Contact c = new CustomerRequest.Contact();
        c.setName(name);
        c.setEmail(email);
        c.setDefaultContact(isDefault);
        return c;
    }

    private SaleOrderRequest order(Long customerId, String customerName) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customerId);
        req.setCustomerName(customerName);
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(product.getId());
        l.setQuantity(BigDecimal.ONE);
        req.setLines(List.of(l));
        return req;
    }
}
