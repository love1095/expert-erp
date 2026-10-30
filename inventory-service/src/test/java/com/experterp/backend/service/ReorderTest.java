package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.DashboardResponse.ReorderLine;
import com.experterp.backend.dto.DashboardResponse.ReorderSupplier;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.ReorderRequest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase E4: the reorder screen raises POs in bulk, one per supplier (docs/phase-e-purchasing.md). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReorderTest {

    @Autowired DashboardService dashboardService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Supplier portwest;
    Supplier jsp;
    Product gloves;     // Portwest, min 20, reorder qty 50, 5 in stock
    Product vest;       // Portwest, min 10, no reorder qty, 4 in stock → 6 to order
    Product helmet;     // JSP, min 5, 0 in stock
    Product plenty;     // Portwest, min 5, 30 in stock: not to reorder
    Product orphan;     // no supplier, min 5, 0 in stock

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Reorder " + id);
        t.setSlug("reorder-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse " + id);
        warehouse = warehouseRepository.save(warehouse);
        portwest = supplier("Portwest Clothing Ltd");
        jsp = supplier("JSP Limited");
        gloves = product("GLOVES", portwest, 20, 50, 5, "PW-G", "1.20");
        vest = product("VEST", portwest, 10, 0, 4, "PW-V", "2.50");
        helmet = product("HELMET", jsp, 5, 0, 0, "JSP-H", "8.00");
        plenty = product("PLENTY", portwest, 5, 0, 30, null, null);
        orphan = product("ORPHAN", null, 5, 0, 0, null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void productsToReorderAreListedPerSupplierWithCin7sSuggestedQuantities() {
        List<ReorderSupplier> suppliers = dashboardService.reorderSuppliers();
        assertThat(suppliers).extracting(ReorderSupplier::supplierName, ReorderSupplier::products)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("JSP Limited", 1L),
                        org.assertj.core.groups.Tuple.tuple("Portwest Clothing Ltd", 2L),
                        org.assertj.core.groups.Tuple.tuple(null, 1L));

        List<ReorderLine> pw = dashboardService.reorder(null, portwest.getId(), false, 0, 50).getContent();
        assertThat(pw).extracting(ReorderLine::sku).containsExactlyInAnyOrder("GLOVES", "VEST");
        ReorderLine g = pw.stream().filter(l -> l.sku().equals("GLOVES")).findFirst().orElseThrow();
        // Reorder quantity when set; otherwise up to the minimum
        assertThat(g.suggestedQuantity()).isEqualByComparingTo("50");
        assertThat(g.supplierSku()).isEqualTo("PW-G");
        assertThat(g.unitCost()).isEqualByComparingTo("1.20");
        ReorderLine v = pw.stream().filter(l -> l.sku().equals("VEST")).findFirst().orElseThrow();
        assertThat(v.suggestedQuantity()).isEqualByComparingTo("6");
        assertThat(dashboardService.reorder(null, null, true, 0, 50).getContent()).extracting(ReorderLine::sku).containsExactly("ORPHAN");
    }

    @Test
    void theChosenProductsBecomeOneDraftPoPerSupplierAndCountAsOnOrder() {
        ReorderRequest req = new ReorderRequest();
        req.setWarehouseId(warehouse.getId());
        req.setLines(List.of(line(gloves, 50), line(vest, 6), line(helmet, 5)));

        List<PurchaseOrderResponse> pos = purchaseOrderService.createFromReorder(req);

        assertThat(pos).hasSize(2);
        assertThat(pos).allSatisfy(po -> assertThat(po.getStatus()).isEqualTo(PurchaseOrder.Status.DRAFT));
        PurchaseOrderResponse pw = pos.stream().filter(p -> p.getSupplierName().equals("Portwest Clothing Ltd")).findFirst().orElseThrow();
        assertThat(pw.getLines()).extracting(PurchaseOrderResponse.Line::getSupplierSku).containsExactly("PW-G", "PW-V");
        assertThat(pw.getLines().get(0).getUnitCost()).isEqualByComparingTo("1.20");
        // Now on order: the gloves are above their minimum and leave the list; the vest, ordered up to its minimum,
        // stays (Cin7 lists stock at or below the minimum) with its 6 on order and nothing more suggested
        List<ReorderLine> after = dashboardService.reorder(null, portwest.getId(), false, 0, 50).getContent();
        assertThat(after).extracting(ReorderLine::sku).containsExactly("VEST");
        assertThat(after.get(0).onOrder()).isEqualByComparingTo("6");
        assertThat(after.get(0).suggestedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void productsWithoutASupplierAreRefusedByName() {
        ReorderRequest req = new ReorderRequest();
        req.setWarehouseId(warehouse.getId());
        req.setLines(List.of(line(helmet, 5), line(orphan, 5)));
        assertThatThrownBy(() -> purchaseOrderService.createFromReorder(req))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ORPHAN");
    }

    private Supplier supplier(String name) {
        Supplier s = new Supplier();
        s.setName(name);
        return supplierRepository.save(s);
    }

    private Product product(String sku, Supplier supplier, int min, int reorderQty, int stock, String code, String supplierPrice) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku);
        p.setUnit("Each");
        p.setDefaultSupplier(supplier);
        p.setSupplierProductCode(code);
        p.setMinStock(BigDecimal.valueOf(min));
        if (reorderQty > 0) p.setReorderQuantity(BigDecimal.valueOf(reorderQty));
        if (supplierPrice != null) p.getDetails().setSupplierPrice(new BigDecimal(supplierPrice));
        p = productRepository.save(p);
        if (stock > 0) movementService.createMovement(p, warehouse, BigDecimal.valueOf(stock), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        return p;
    }

    private static ReorderRequest.Line line(Product p, int qty) {
        ReorderRequest.Line l = new ReorderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }
}
