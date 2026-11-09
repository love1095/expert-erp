package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.PurchaseOrderRequest;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Review R9: the product selection list on order lines. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductPickerTest {

    @Autowired ProductPickerService picker;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse main;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Picker " + id);
        t.setSlug("picker-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        Warehouse w = new Warehouse();
        w.setName("Main " + id);
        main = warehouseRepository.save(w);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listsActiveProductsAToZOnePageAtATimeAndNarrowsBySkuNameOrBarcode() {
        for (String sku : List.of("C-BOOT", "A-GLOVE", "B-HAT", "D-VEST", "E-MASK")) product(sku, Product.Status.ACTIVE, null);
        product("Z-OLD", Product.Status.DEPRECATED, null);
        productRepository.findAll().stream().filter(p -> p.getSku().equals("D-VEST")).findFirst().ifPresent(p -> {
            p.setBarcode("5012345678900");
            productRepository.save(p);
        });

        ProductPickerService.Result first = picker.page(null, null, 0, 2);
        assertThat(first.rows()).extracting(ProductPickerService.Row::sku).containsExactly("A-GLOVE", "B-HAT");
        assertThat(first.more()).isTrue();
        ProductPickerService.Result last = picker.page(null, null, 2, 2);
        assertThat(last.rows()).extracting(ProductPickerService.Row::sku).containsExactly("E-MASK");   // archived not listed
        assertThat(last.more()).isFalse();

        assertThat(picker.page("boot", null, 0, 20).rows()).extracting(ProductPickerService.Row::sku).containsExactly("C-BOOT");
        assertThat(picker.page("50123", null, 0, 20).rows()).extracting(ProductPickerService.Row::sku).containsExactly("D-VEST");
    }

    @Test
    void theSuppliersProductsComeFirstWithStockAvailableAndOnOrder() {
        Supplier portwest = new Supplier();
        portwest.setName("Portwest");
        portwest = supplierRepository.save(portwest);
        Product apron = product("A-APRON", Product.Status.ACTIVE, null);
        Product vest = product("V-VEST", Product.Status.ACTIVE, portwest);
        product("B-BOOT", Product.Status.ACTIVE, null);
        movementService.createMovement(vest, main, new BigDecimal("12"), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);

        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierId(portwest.getId());
        po.setWarehouseId(main.getId());
        po.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(vest.getId());
        l.setQuantity(new BigDecimal("20"));
        l.setUnitCost(BigDecimal.ONE);
        po.setLines(List.of(l));
        purchaseOrderService.create(po);

        List<ProductPickerService.Row> rows = picker.page(null, portwest.getId(), 0, 20).rows();
        assertThat(rows).extracting(ProductPickerService.Row::sku).containsExactly("V-VEST", "A-APRON", "B-BOOT");
        assertThat(rows.get(0).supplierProduct()).isTrue();
        assertThat(rows.get(1).supplierProduct()).isFalse();
        assertThat(rows.get(0).onHand()).isEqualByComparingTo("12");
        assertThat(rows.get(0).available()).isEqualByComparingTo("12");
        assertThat(rows.get(0).onOrder()).isEqualByComparingTo("20");
        assertThat(rows.get(1).onOrder()).isEqualByComparingTo("0");
        // Without a supplier: plain A-Z
        assertThat(picker.page(null, null, 0, 20).rows()).extracting(ProductPickerService.Row::sku).containsExactly("A-APRON", "B-BOOT", "V-VEST");
        assertThat(apron.getId()).isNotNull();
    }

    private Product product(String sku, Product.Status status, Supplier supplier) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku + " name");
        p.setUnit("Each");
        p.setStatus(status);
        p.setDefaultSupplier(supplier);
        return productRepository.save(p);
    }
}
