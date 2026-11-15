package com.experterp.backend.flow;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.OrderInvoiceRequest;
import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.entity.PaymentAccount;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.WarehouseRepository;
import com.experterp.backend.service.InvoiceService;
import com.experterp.backend.service.LedgerService;
import com.experterp.backend.service.ProductService;
import com.experterp.backend.service.SaleOrderService;
import com.experterp.backend.service.SalesLedgerService;
import com.experterp.backend.service.StockMovementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the review's flow tests (docs/review-and-test-plan.md, Part 3) share: a fresh organisation with one warehouse
 * and a bank account, signed in as an admin, and the helpers to read the stock, its worth and the ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
abstract class FlowSupport {

    @Autowired protected SaleOrderService saleOrderService;
    @Autowired protected InvoiceService invoiceService;
    @Autowired protected SalesLedgerService salesLedger;
    @Autowired protected LedgerService ledger;
    @Autowired protected ProductService productService;
    @Autowired protected StockMovementService movements;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected WarehouseRepository warehouseRepository;
    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected JdbcTemplate jdbc;

    protected String id;
    protected Long tenantId;
    protected Warehouse main;
    protected Long bank;
    protected final LocalDate today = LocalDate.now();

    @BeforeEach
    void organisation() {
        id = UUID.randomUUID().toString().substring(0, 6);
        tenantId = newOrganisation("Flow " + getClass().getSimpleName() + " " + id, "flow-" + id);
        TenantContext.set(tenantId);
        as("ROLE_ADMIN");
        main = warehouse("Main " + id);
        bank = salesLedger.saveAccounts(List.of(new SalesLedgerService.AccountRequest(null, "650", "Current Account - RBS",
                PaymentAccount.Kind.BANK, true, true))).get(0).id();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    protected Long newOrganisation(String name, String slug) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setSlug(slug);
        return tenantRepository.save(t).getId();
    }

    protected void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("flow", null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    protected Warehouse warehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name);
        return warehouseRepository.save(w);
    }

    protected Product product(String sku) {
        return product(sku, Product.Type.STOCK);
    }

    protected Product product(String sku, Product.Type type) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        p.setType(type);
        return productRepository.save(p);
    }

    /** Stock counted in at a cost (an adjustment: Dr inventory 631, Cr inventory discrepancy 495). */
    protected void in(Product p, Warehouse w, int n, String cost) {
        movements.createMovement(p, w, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "OPENING", null, null, new BigDecimal(cost));
    }

    protected static SaleOrderRequest.Line line(Product p, String qty, String price) {
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(new BigDecimal(qty));
        if (price != null) l.setUnitPrice(new BigDecimal(price));
        return l;
    }

    protected SaleOrderRequest order(String customer, SaleOrderRequest.Line... lines) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName(customer + " " + id);
        so.setWarehouseId(main.getId());
        so.setLines(List.of(lines));
        return so;
    }

    protected InvoiceResponse invoiceAll(Long orderId) {
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        return invoiceService.invoiceOrder(orderId, all);
    }

    protected InvoiceResponse pay(Long invoiceId, String amount) {
        PaymentRequest p = new PaymentRequest();
        p.setAmount(new BigDecimal(amount));
        p.setAccountId(bank);
        return invoiceService.addPayment(invoiceId, p);
    }

    protected BigDecimal onHand(Product p) {
        return productService.stock(p.getId()).onHand();
    }

    protected BigDecimal value(Product p) {
        return productService.stock(p.getId()).stockValue();
    }

    /** What the FIFO layers still hold, to the penny. */
    protected BigDecimal layersValue() {
        return jdbc.queryForObject("SELECT ROUND(COALESCE(SUM(remaining * unit_cost), 0), 2) FROM cost_layers WHERE tenant_id = ?", BigDecimal.class, tenantId);
    }

    /** An account's balance on the trial balance: a debit is positive, a credit negative. */
    protected static BigDecimal balance(LedgerService.TrialBalance tb, String code) {
        return tb.rows().stream().filter(r -> r.code().equals(code)).findFirst()
                .map(r -> r.debit() != null ? r.debit() : r.credit().negate()).orElse(BigDecimal.ZERO);
    }

    protected List<String> auditActions() {
        return jdbc.queryForList("SELECT DISTINCT entity_type || ':' || action FROM audit_log WHERE tenant_id = ?", String.class, tenantId);
    }
}
