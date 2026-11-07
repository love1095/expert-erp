package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.OrderInvoiceRequest;
import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase G2 (spec §5): credit hold when the limit or payment terms are breached; warning at order entry. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CreditTest {

    @Autowired CreditService creditService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Warehouse warehouse;
    Product helmet;
    Customer customer;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Credit " + id);
        t.setSlug("credit-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Main");
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELM-" + id.substring(0, 6));
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        movementService.createMovement(helmet, warehouse, BigDecimal.valueOf(100), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "T", null);
        customer = new Customer();
        customer.setName("Loudfoot Builders");
        customer.setCreditLimit(new BigDecimal("100"));
        customer = customerRepository.save(customer);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void whatIsOwedAndOnOrderCountsAgainstTheLimitAndAnOrderOverItWarnsAndNeedsAManager() {
        // £45 + 20 % VAT = £54 invoiced, unpaid
        Long shipped = saleOrderService.confirm(saleOrderService.create(order(3, "15")).getId()).getId();
        saleOrderService.fulfill(shipped, null);
        OrderInvoiceRequest issue = new OrderInvoiceRequest();
        issue.setIssue(true);
        invoiceService.invoiceOrder(shipped, issue);
        // £12 confirmed, not shipped or invoiced yet
        saleOrderService.confirm(saleOrderService.create(order(1, "10")).getId());

        CreditService.CreditStatus st = creditService.status(customer.getId());
        assertThat(st.balance()).isEqualByComparingTo("54.00");
        assertThat(st.notInvoiced()).isEqualByComparingTo("12.00");
        assertThat(st.available()).isEqualByComparingTo("34.00");
        assertThat(st.held()).isFalse();

        // A £36 order would take them £2 over
        SaleOrderResponse draft = saleOrderService.create(order(3, "10"));
        assertThat(draft.isCreditHold()).isTrue();
        assertThat(draft.getCreditWarning()).contains("£102.00 owed and on order with this order is £2.00 over the £100.00 credit limit");
        assertThat(saleOrderService.preview(order(3, "10")).isCreditHold()).isTrue();
        assertThat(saleOrderService.preview(order(1, "10")).isCreditHold()).isFalse();

        assertThatThrownBy(() -> saleOrderService.confirm(draft.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("A manager can confirm it anyway");
        as("ROLE_STAFF");
        assertThatThrownBy(() -> saleOrderService.confirm(draft.getId(), "Paying on collection")).isInstanceOf(AccessDeniedException.class);
        as("ROLE_MANAGER");
        SaleOrderResponse confirmed = saleOrderService.confirm(draft.getId(), "Paying on collection");
        assertThat(confirmed.getStatus()).isEqualTo(SaleOrder.Status.CONFIRMED);
        assertThat(confirmed.getCreditOverride()).isEqualTo("Paying on collection");
    }

    @Test
    void anOverdueInvoiceHoldsTheCustomerWhateverTheLimit() {
        customer.setCreditLimit(null);
        customerRepository.save(customer);
        Long so = saleOrderService.confirm(saleOrderService.create(order(1, "10")).getId()).getId();
        saleOrderService.fulfill(so, null);
        OrderInvoiceRequest issue = new OrderInvoiceRequest();
        issue.setIssue(true);
        Long invoiceId = invoiceService.invoiceOrder(so, issue).getId();
        assertThat(creditService.status(customer.getId()).held()).isFalse();

        jdbc.update("UPDATE invoices SET due_date = CURRENT_DATE - 5 WHERE id = ?", invoiceId);
        CreditService.CreditStatus st = creditService.status(customer.getId());
        assertThat(st.held()).isTrue();
        assertThat(st.overdue()).isEqualByComparingTo("12.00");
        assertThat(st.daysOverdue()).isEqualTo(5);
        assertThat(st.warning()).contains("£12.00 overdue");
        assertThat(st.available()).isNull();
    }

    @Test
    void aManagerPutsACustomerOnHoldWithAReasonAndReleasesThem() {
        assertThatThrownBy(() -> creditService.setHold(customer.getId(), true, " ")).isInstanceOf(IllegalArgumentException.class);
        CreditService.CreditStatus held = creditService.setHold(customer.getId(), true, "Cheque bounced");
        assertThat(held.held()).isTrue();
        assertThat(held.warning()).isEqualTo("Credit hold: put on hold: Cheque bounced");
        assertThatThrownBy(() -> saleOrderService.confirm(saleOrderService.create(order(1, "1")).getId()))
                .hasMessageContaining("Cheque bounced");
        assertThat(creditService.setHold(customer.getId(), false, null).held()).isFalse();
        assertThat(saleOrderService.confirm(saleOrderService.create(order(1, "1")).getId()).getStatus()).isEqualTo(SaleOrder.Status.CONFIRMED);
    }

    @Test
    void aHoldAfterConfirmingStopsTheShippingUntilAManagerGivesAReason() {
        as("ROLE_MANAGER");
        Long id = saleOrderService.confirm(saleOrderService.create(order(2, "10")).getId()).getId();
        creditService.setHold(customer.getId(), true, "Cheque bounced");
        assertThatThrownBy(() -> saleOrderService.fulfill(id, null)).hasMessageContaining("Shipping is stopped").hasMessageContaining("Cheque bounced");
        as("ROLE_USER");
        FulfilmentRequest anyway = new FulfilmentRequest();
        anyway.setCreditOverride("Paid by card on collection");
        assertThatThrownBy(() -> saleOrderService.fulfill(id, anyway)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        as("ROLE_MANAGER");
        SaleOrderResponse shipped = saleOrderService.fulfill(id, anyway);
        assertThat(shipped.getStatus()).isEqualTo(SaleOrder.Status.COMPLETED);
        assertThat(shipped.getCreditOverride()).isEqualTo("Paid by card on collection");
    }

    // ---- helpers ----

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    private SaleOrderRequest order(int qty, String price) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customer.getId());
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        req.setLines(List.of(l));
        return req;
    }
}
