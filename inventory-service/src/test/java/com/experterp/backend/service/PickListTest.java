package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Settings: the payment terms and sales representatives lists are renamed and removed without losing what uses them. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PickListTest {

    @Autowired PickListService pickLists;
    @Autowired PaymentTermService paymentTerms;
    @Autowired CustomerService customers;
    @Autowired SupplierRepository supplierRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Supplier orn;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString();
        Tenant t = new Tenant();
        t.setName("Lists " + id);
        t.setSlug("lists-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        orn = new Supplier();
        orn.setName("Orn " + id);
        orn.setPaymentTerm("30 Day EOM");
        orn = supplierRepository.save(orn);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aTermAddedByHandIsRenamedWithItsUsesAndRemovedOnlyWhenUnused() {
        paymentTerms.add("45 Net", PaymentTermService.Method.DAYS, 45);
        assertThat(entry("45 Net").added()).isTrue();
        assertThat(entry("45 Net").uses()).isZero();
        orn.setPaymentTerm("45 net");
        supplierRepository.save(orn);
        assertThat(entry("45 Net").uses()).isEqualTo(1);

        pickLists.renamePaymentTerm("45 net", "45 Days Net");
        assertThat(supplierRepository.findById(orn.getId()).orElseThrow().getPaymentTerm()).isEqualTo("45 Days Net");
        assertThat(paymentTerms.dueDate("45 Days Net", LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(pickLists.paymentTerms()).noneMatch(e -> e.name().equals("45 Net"));

        assertThatThrownBy(() -> pickLists.removePaymentTerm("45 Days Net")).hasMessageContaining("used on 1");
        orn.setPaymentTerm(null);
        supplierRepository.save(orn);
        pickLists.removePaymentTerm("45 Days Net");
        assertThat(pickLists.paymentTerms()).noneMatch(e -> e.name().equals("45 Days Net"));
    }

    @Test
    void aTermReadFromItsNameKeepsItsRuleUnderANewName() {
        // "30 Day EOM" came with the supplier (Cin7): due 30 days after the end of the invoice month
        assertThat(entry("30 Day EOM").added()).isFalse();
        LocalDate before = paymentTerms.dueDate("30 Day EOM", LocalDate.of(2026, 1, 10));
        pickLists.renamePaymentTerm("30 Day EOM", "Month end plus thirty");
        assertThat(supplierRepository.findById(orn.getId()).orElseThrow().getPaymentTerm()).isEqualTo("Month end plus thirty");
        assertThat(paymentTerms.dueDate("Month end plus thirty", LocalDate.of(2026, 1, 10))).isEqualTo(before);
        assertThat(entry("Month end plus thirty").added()).isTrue();

        paymentTerms.add("Cash", PaymentTermService.Method.ON_INVOICE, null);
        assertThatThrownBy(() -> pickLists.renamePaymentTerm("Month end plus thirty", "cash")).hasMessageContaining("already a payment term");
        assertThatThrownBy(() -> pickLists.renamePaymentTerm("Nope", "X")).hasMessageContaining("no payment term");
        assertThatThrownBy(() -> pickLists.renamePaymentTerm("Cash", " ")).hasMessageContaining("new name");
    }

    @Test
    void aSalesRepIsRenamedOnCustomersAndTheShopifySettingAndRemovedOnlyWhenUnused() {
        customers.addSalesRep("Jo Smith");
        Long t = TenantContext.get();
        jdbc.update("INSERT INTO customers (tenant_id, name, status, sales_representative) VALUES (?, 'Acme', 'ACTIVE', 'jo smith')", t);
        jdbc.update("UPDATE tenants SET web_sales_rep = 'Jo Smith' WHERE id = ?", t);
        assertThat(pickLists.salesReps()).anyMatch(e -> e.name().equalsIgnoreCase("Jo Smith") && e.uses() == 1);

        pickLists.renameSalesRep("Jo Smith", "Joanne Smith");
        assertThat(jdbc.queryForObject("SELECT sales_representative FROM customers WHERE tenant_id = ?", String.class, t)).isEqualTo("Joanne Smith");
        assertThat(jdbc.queryForObject("SELECT web_sales_rep FROM tenants WHERE id = ?", String.class, t)).isEqualTo("Joanne Smith");
        assertThat(customers.salesReps()).contains("Joanne Smith").doesNotContain("Jo Smith");

        assertThatThrownBy(() -> pickLists.removeSalesRep("Joanne Smith")).hasMessageContaining("Shopify");
        jdbc.update("UPDATE tenants SET web_sales_rep = 'Web' WHERE id = ?", t);
        assertThatThrownBy(() -> pickLists.removeSalesRep("Joanne Smith")).hasMessageContaining("on 1 customers");
        jdbc.update("UPDATE customers SET sales_representative = NULL WHERE tenant_id = ?", t);
        pickLists.removeSalesRep("Joanne Smith");
        assertThat(customers.salesReps()).doesNotContain("Joanne Smith");
    }

    private PickListService.Entry entry(String name) {
        return pickLists.paymentTerms().stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst().orElseThrow();
    }
}
