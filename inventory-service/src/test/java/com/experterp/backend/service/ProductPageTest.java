package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.ProductRequest;
import com.experterp.backend.dto.ProductStockResponse;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The product page (Cin7's product details): stock per warehouse and on order, activity log, safe deletes. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductPageTest {

    @Autowired ProductService productService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse main;
    Warehouse van;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Products " + id);
        t.setSlug("products-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        main = warehouse("Expert Warehouse " + id);
        van = warehouse("Van " + id);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void stockIsShownPerWarehouseWithWhatIsOnOrder() {
        Product gloves = productRepository.save(product("GLOVES", Product.Status.ACTIVE));
        movementService.createMovement(gloves, main, new BigDecimal("12"), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        movementService.createMovement(gloves, van, new BigDecimal("3"), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        Supplier s = new Supplier();
        s.setName("Portwest");
        s = supplierRepository.save(s);
        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierId(s.getId());
        po.setWarehouseId(main.getId());
        po.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(gloves.getId());
        l.setQuantity(new BigDecimal("20"));
        l.setUnitCost(BigDecimal.ONE);
        po.setLines(List.of(l));
        String number = purchaseOrderService.create(po).getOrderNumber();

        ProductStockResponse stock = productService.stock(gloves.getId());

        assertThat(stock.warehouses()).extracting(ProductStockResponse.Warehouse::onHand)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("12"), new BigDecimal("3"));
        assertThat(stock.onHand()).isEqualByComparingTo("15");
        assertThat(stock.onOrderTotal()).isEqualByComparingTo("20");
        assertThat(stock.onOrder()).singleElement().satisfies(o -> {
            assertThat(o.orderNumber()).isEqualTo(number);
            assertThat(o.supplierName()).isEqualTo("Portwest");
        });
    }

    @Test
    void changesAreInTheActivityLogAndAProductWithHistoryCannotBeDeleted() {
        Product vest = productRepository.save(product("VEST", Product.Status.ACTIVE));
        ProductRequest req = request(vest);
        req.setSellPrice(new BigDecimal("4.50"));
        productService.update(vest.getId(), req);

        assertThat(auditLogRepository.search("PRODUCT", vest.getId(), "UPDATE", null, PageRequest.of(0, 10)).getContent())
                .singleElement().satisfies(a -> assertThat(a.getChanges()).contains("sellPrice").contains("4.5"));

        movementService.createMovement(vest, main, BigDecimal.ONE, MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        assertThatThrownBy(() -> productService.delete(vest.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mark it deprecated");
    }

    @Test
    void deletingDeprecatedProductsKeepsThoseWithHistory() {
        Product unused = productRepository.save(product("OLD-UNUSED", Product.Status.DEPRECATED));
        Product used = productRepository.save(product("OLD-USED", Product.Status.DEPRECATED));
        Product active = productRepository.save(product("CURRENT", Product.Status.ACTIVE));
        movementService.createMovement(used, main, BigDecimal.ONE, MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);

        ProductService.DeleteDeprecatedResult result = productService.deleteDeprecated();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.kept()).isEqualTo(1);
        assertThat(productRepository.findById(unused.getId())).isEmpty();
        assertThat(productRepository.findById(used.getId())).isPresent();
        assertThat(productRepository.findById(active.getId())).isPresent();
    }

    private Warehouse warehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name);
        return warehouseRepository.save(w);
    }

    private static Product product(String sku, Product.Status status) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku);
        p.setUnit("Each");
        p.setStatus(status);
        return p;
    }

    private static ProductRequest request(Product p) {
        ProductRequest r = new ProductRequest();
        r.setSku(p.getSku());
        r.setName(p.getName());
        r.setUnit(p.getUnit());
        r.setStatus(p.getStatus());
        return r;
    }
}
