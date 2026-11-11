package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.entity.*;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** New purchase like Cin7: supplier details, accounting details (order tax rule) and shipping details on the order. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchaseHeaderTest {

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Supplier orn;
    Product polo;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString();
        Tenant t = new Tenant();
        t.setName("Header " + id);
        t.setSlug("header-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        as("ROLE_MANAGER");
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse " + id);
        warehouse.setAddress(new Address("Unit 3 Halfpenny Bridge Ind Est", "Lincoln Close", "Rochdale", null, "OL11 1NS", "United Kingdom"));
        warehouse = warehouseRepository.save(warehouse);
        orn = new Supplier();
        orn.setName("Orn Clothing " + id);
        orn.setContactName("Mark Fyffe");
        orn.setPhone("01295 221350");
        orn.setTaxRule("20% (VAT on Expenses)");
        orn.setAddress(new Address("Tingewick Industrial Estate", null, "Milton Keynes", "Buckingham", "MK18 1SU", "GBR"));
        orn = supplierRepository.save(orn);
        polo = new Product();
        polo.setSku("POLO-" + id.substring(0, 6));
        polo.setName("Polo");
        polo.setUnit("Each");
        polo.setCostPrice(new BigDecimal("10.00"));
        polo = productRepository.save(polo);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aNewOrderTakesTheSuppliersContactAndAddressTheWarehousesAddressAndToday() {
        PurchaseOrderResponse po = purchaseOrderService.create(request());
        assertThat(po.getContactName()).isEqualTo("Mark Fyffe");
        assertThat(po.getContactPhone()).isEqualTo("01295 221350");
        assertThat(po.getVendorAddress().getLine1()).isEqualTo("Tingewick Industrial Estate");
        assertThat(po.getShippingAddress().getPostcode()).isEqualTo("OL11 1NS");
        assertThat(po.getShipToCompany()).isNull();
        assertThat(po.getOrderDate()).isEqualTo(LocalDate.now());
        assertThat(po.isBlindReceipt()).isFalse();
        assertThat(po.getTaxRule()).isNull();
        assertThat(po.getDefaultTaxRule()).isEqualTo("20% (VAT on Expenses)");
        assertThat(po.getInventoryAccount()).startsWith("631");
        assertThat(po.getLines().get(0).getTaxName()).isEqualTo("20% (VAT on Expenses)");
    }

    @Test
    void whatIsTypedOnTheOrderIsKeptAndTheOrdersTaxRuleIsWhereLinesStart() {
        PurchaseOrderRequest req = request();
        req.setContactName("Accounts");
        req.setContactPhone("0800 1");
        req.setOrderDate(LocalDate.of(2026, 10, 29));
        req.setBlindReceipt(true);
        req.setShipToCompany("Stitch Embroidery");
        req.setShippingAddress(new Address("2 Mill Lane", null, "Bury", null, "BL9 0AA", null));
        req.setTaxRule("Zero Rated Expenses");
        req.setTaxReason("Children's clothing");
        PurchaseOrderResponse po = purchaseOrderService.create(req);
        assertThat(po.getContactName()).isEqualTo("Accounts");
        assertThat(po.getOrderDate()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(po.isBlindReceipt()).isTrue();
        assertThat(po.getShipToCompany()).isEqualTo("Stitch Embroidery");
        assertThat(po.getShippingAddress().getLine1()).isEqualTo("2 Mill Lane");
        assertThat(po.getTaxRule()).isEqualTo("Zero Rated Expenses");
        assertThat(po.getLines().get(0).getTaxName()).isEqualTo("Zero Rated Expenses");
        assertThat(po.getTotal()).isEqualByComparingTo("20.00");

        // Editing the draft keeps its date when none is sent
        req.setOrderDate(null);
        PurchaseOrderResponse edited = purchaseOrderService.update(po.getId(), req);
        assertThat(edited.getOrderDate()).isEqualTo(LocalDate.of(2026, 10, 29));

        // Less VAT than the supplier's needs a reason; staff may not change it at all
        req.setTaxReason(null);
        assertThatThrownBy(() -> purchaseOrderService.create(req)).hasMessageContaining("reason");
        as("ROLE_STAFF");
        req.setTaxReason("x");
        assertThatThrownBy(() -> purchaseOrderService.create(req)).hasMessageContaining("manager or admin");
    }

    private PurchaseOrderRequest request() {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(orn.getId());
        req.setWarehouseId(warehouse.getId());
        PurchaseOrderRequest.Line line = new PurchaseOrderRequest.Line();
        line.setProductId(polo.getId());
        line.setQuantity(new BigDecimal("2"));
        req.setLines(List.of(line));
        return req;
    }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
