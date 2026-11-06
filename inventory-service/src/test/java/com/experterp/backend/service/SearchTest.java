package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase I2 (spec §8): one search box over orders, invoices, bills, customers, suppliers and products. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SearchTest {

    @Autowired SearchService search;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    String id;
    Long tenantId;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        tenantId = tenant("Search " + id);
        signIn("ROLE_MANAGER");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void findsEachKindByNumberNameReferenceSkuOrBarcodeOnlyInItsOwnOrganisation() {
        Warehouse wh = new Warehouse();
        wh.setName("Main " + id);
        wh = warehouseRepository.save(wh);
        Product boot = new Product();
        boot.setSku("FWBT-ORYX-" + id);
        boot.setName("Expert Oryx Black Comfort S3 Safety Boot");
        boot.setUnit("Pair");
        boot.setBarcode("5059" + id);
        boot = productRepository.save(boot);
        movementService.createMovement(boot, wh, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null);
        Supplier rockfall = new Supplier();
        rockfall.setName("Rock Fall (UK) Ltd " + id);
        rockfall.setEmail("orders@rockfall.example");
        rockfall = supplierRepository.save(rockfall);

        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("AE Yates Ltd " + id);
        req.setCustomerReference("PO-77812");
        req.setWarehouseId(wh.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(boot.getId());
        l.setQuantity(BigDecimal.ONE);
        l.setUnitPrice(new BigDecimal("40"));
        req.setLines(List.of(l));
        SaleOrderResponse so = saleOrderService.confirm(saleOrderService.create(req).getId());
        saleOrderService.fulfill(so.getId(), null);
        OrderInvoiceRequest inv = new OrderInvoiceRequest();
        inv.setIssue(true);
        InvoiceResponse invoice = invoiceService.invoiceOrder(so.getId(), inv);

        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierId(rockfall.getId());
        po.setWarehouseId(wh.getId());
        PurchaseOrderRequest.Line pl = new PurchaseOrderRequest.Line();
        pl.setProductId(boot.getId());
        pl.setQuantity(BigDecimal.ONE);
        pl.setUnitCost(new BigDecimal("16.50"));
        po.setLines(List.of(pl));
        PurchaseOrderResponse purchase = purchaseOrderService.create(po);

        // By number: the order first
        assertThat(search.search(so.getOrderNumber())).first().satisfies(h -> {
            assertThat(h.kind()).isEqualTo("SALE_ORDER");
            assertThat(h.id()).isEqualTo(so.getId());
            assertThat(h.detail()).contains("AE Yates Ltd").contains("Ref PO-77812");
        });
        assertThat(search.search(invoice.getInvoiceNumber())).anySatisfy(h -> {
            assertThat(h.kind()).isEqualTo("INVOICE");
            assertThat(h.detail()).startsWith("Invoice");
        });
        // By customer name (any case, part of it): the customer, its order and its invoice
        assertThat(search.search("ae yates ltd " + id)).extracting(SearchService.Hit::kind)
                .contains("SALE_ORDER", "INVOICE", "CUSTOMER");
        // By the customer's reference, the supplier's e-mail, the barcode, part of a product name
        assertThat(search.search("po-77812")).extracting(SearchService.Hit::kind).containsExactly("SALE_ORDER");
        assertThat(search.search("rockfall.example")).extracting(SearchService.Hit::kind).containsExactly("SUPPLIER");
        assertThat(search.search("5059" + id)).singleElement().satisfies(h -> assertThat(h.kind()).isEqualTo("PRODUCT"));
        assertThat(search.search("Rock Fall (UK) Ltd " + id)).extracting(SearchService.Hit::kind).contains("PURCHASE_ORDER", "SUPPLIER");
        assertThat(search.search(purchase.getOrderNumber())).extracting(SearchService.Hit::id).contains(purchase.getId());
        // Too short, or a % that is not a wildcard
        assertThat(search.search("a")).isEmpty();
        assertThat(search.search("%%")).isEmpty();

        // A role with sales only finds no products, bills, purchase orders or suppliers
        signIn("ROLE_STAFF", "PERM_SALE_VIEW");
        assertThat(search.search("Rock Fall (UK) Ltd " + id)).isEmpty();
        assertThat(search.search("5059" + id)).isEmpty();
        assertThat(search.search("ae yates ltd " + id)).extracting(SearchService.Hit::kind).contains("SALE_ORDER", "INVOICE", "CUSTOMER");

        // Another organisation finds nothing of it
        tenant("Other " + id);
        signIn("ROLE_MANAGER");
        assertThat(search.search("ae yates ltd " + id)).isEmpty();
        assertThat(search.search("5059" + id)).isEmpty();
    }

    private Long tenant(String name) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setSlug(name.toLowerCase().replace(' ', '-') + "-" + UUID.randomUUID().toString().substring(0, 4));
        Long tid = tenantRepository.save(t).getId();
        TenantContext.set(tid);
        return tid;
    }

    private void signIn(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
