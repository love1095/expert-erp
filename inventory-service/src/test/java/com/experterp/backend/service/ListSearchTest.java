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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Search box on the lists (sale orders, invoices, purchase orders, stock), like Cin7's list toolbar. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ListSearchTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;
    Product boot;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Search " + id);
        t.setSlug("search-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = product("HELMET-01", "Blue hard hat");
        boot = product("BOOT-42", "Safety boot 42");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void saleOrdersMatchNumberCustomerAndReference() {
        SaleOrderResponse acme = saleOrderService.create(order("Acme Builders", "PO 7781"));
        SaleOrderResponse bolt = saleOrderService.create(order("Bolt & Co", null));

        assertThat(numbers(saleOrderService.findAll(null, null, "acme", 0, 20))).containsExactly(acme.getOrderNumber());
        assertThat(numbers(saleOrderService.findAll(null, null, "7781", 0, 20))).containsExactly(acme.getOrderNumber());
        assertThat(numbers(saleOrderService.findAll(null, null, bolt.getOrderNumber(), 0, 20))).containsExactly(bolt.getOrderNumber());
        assertThat(saleOrderService.findAll(null, null, "  ", 0, 20).getTotalElements()).isEqualTo(2);
        // Typed wildcards are plain text
        assertThat(saleOrderService.findAll(null, null, "%", 0, 20).getTotalElements()).isZero();
        // Combined with the status filter
        assertThat(saleOrderService.findAll(null, SaleOrder.Status.QUOTE, "acme", 0, 20).getTotalElements()).isZero();
    }

    @Test
    void invoicesMatchNumberOrPartyAndFilterByStatus() {
        InvoiceResponse draft = invoiceService.create(invoice("Acme Builders"));
        InvoiceResponse issued = invoiceService.issue(invoiceService.create(invoice("Bolt & Co")).getId());

        assertThat(invoiceService.findAll(0, 20, null, null, "bolt").getContent())
                .extracting(InvoiceResponse::getId).containsExactly(issued.getId());
        assertThat(invoiceService.findAll(0, 20, null, null, draft.getInvoiceNumber()).getContent())
                .extracting(InvoiceResponse::getId).containsExactly(draft.getId());
        assertThat(invoiceService.findAll(0, 20, null, Invoice.InvoiceStatus.ISSUED, null).getContent())
                .extracting(InvoiceResponse::getId).containsExactly(issued.getId());
        assertThat(invoiceService.findAll(0, 20, Invoice.InvoiceType.PURCHASE, null, null).getTotalElements()).isZero();
    }

    @Test
    void purchaseOrdersMatchSupplierOrNumber() {
        PurchaseOrderResponse jsp = purchaseOrderService.create(po("JSP Limited"));
        purchaseOrderService.create(po("Portwest"));

        assertThat(purchaseOrderService.findAll(0, 20, null, "jsp").getContent())
                .extracting(PurchaseOrderResponse::getId).containsExactly(jsp.getId());
        assertThat(purchaseOrderService.findAll(0, 20, null, "PO-" + jsp.getId()).getContent())
                .extracting(PurchaseOrderResponse::getId).contains(jsp.getId());
        assertThat(purchaseOrderService.findAll(0, 20, PurchaseOrder.Status.RECEIVED, null).getTotalElements()).isZero();
    }

    @Test
    void stockMatchesSkuOrName() {
        assertThat(movementService.getStockOverviewPage(0, 20, null, "boot").getContent())
                .extracting(StockLevelResponse::getProductSku).containsExactly("BOOT-42");
        assertThat(movementService.getStockOverviewPage(0, 20, null, "helmet-0").getContent())
                .extracting(StockLevelResponse::getProductSku).containsExactly("HELMET-01");
        assertThat(movementService.getStockOverviewPage(0, 20, null, null).getTotalElements()).isEqualTo(2);
    }

    private Product product(String sku, String name) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setUnit("pcs");
        p = productRepository.save(p);
        movementService.createMovement(p, warehouse, BigDecimal.TEN, MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        return p;
    }

    private SaleOrderRequest order(String customer, String reference) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName(customer);
        req.setCustomerReference(reference);
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.ONE);
        l.setUnitPrice(new BigDecimal("10.00"));
        req.setLines(List.of(l));
        return req;
    }

    private InvoiceRequest invoice(String party) {
        InvoiceRequest req = new InvoiceRequest();
        req.setType(Invoice.InvoiceType.SALES);
        req.setPartyName(party);
        req.setIssueDate(LocalDate.now());
        InvoiceRequest.ItemRequest item = new InvoiceRequest.ItemRequest();
        item.setDescription("Service");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("5.00"));
        req.setItems(List.of(item));
        return req;
    }

    private PurchaseOrderRequest po(String supplier) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierName(supplier);
        req.setWarehouseId(warehouse.getId());
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(boot.getId());
        l.setQuantity(BigDecimal.ONE);
        req.setLines(List.of(l));
        return req;
    }

    private static List<String> numbers(org.springframework.data.domain.Page<SaleOrderResponse> page) {
        return page.getContent().stream().map(SaleOrderResponse::getOrderNumber).toList();
    }
}
