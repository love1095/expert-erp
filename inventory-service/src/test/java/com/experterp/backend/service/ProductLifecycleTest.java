package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase P6: Active / Discontinued (sold while stock lasts, never reordered) / Archived. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductLifecycleTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired ProductService productService;
    @Autowired ProductImportService importService;
    @Autowired DashboardService dashboardService;
    @Autowired StockMovementService movements;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    String id;
    Warehouse main;
    Product visor;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Life " + id);
        t.setSlug("life-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        visor = new Product();
        visor.setSku("VISOR-" + id);
        visor.setName("Visor");
        visor.setUnit("Each");
        visor.setStatus(Product.Status.DISCONTINUED);
        visor.setMinStock(new BigDecimal("10"));
        visor = productRepository.save(visor);
        movements.createMovement(visor, main, new BigDecimal("5"), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, BigDecimal.ONE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aDiscontinuedProductSellsOnlyWhatIsLeftAndIsNeverReordered() {
        SaleOrderResponse three = order(3);
        saleOrderService.confirm(three.getId());                                     // 5 in stock
        SaleOrderResponse three2 = order(3);
        assertThatThrownBy(() -> saleOrderService.confirm(three2.getId())).hasMessageContaining("discontinued").hasMessageContaining("2 left");
        saleOrderService.confirm(order(2).getId());                                  // the last 2
        assertThatThrownBy(() -> saleOrderService.confirm(order(1).getId())).hasMessageContaining("0 left");

        // Offered in order dropdowns, below its minimum but not on the reorder list
        assertThat(productService.options(Product.Status.ACTIVE)).anyMatch(o -> o.id().equals(visor.getId()));
        assertThat(dashboardService.reorder(null, 0, 50).getContent()).noneMatch(r -> r.sku().equals(visor.getSku()));
    }

    @Test
    void theCsvCarriesTheLifecycle() throws Exception {
        String csv = """
                ProductCode,Name,Status
                VISOR-%1$s,Visor,Archived
                MASK-%1$s,Mask,Discontinued
                """.formatted(id);
        importService.importCsv(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), false);
        assertThat(productRepository.findById(visor.getId()).orElseThrow().getStatus()).isEqualTo(Product.Status.DEPRECATED);
        assertThat(productRepository.findBySkuIn(List.of("MASK-" + id)).get(0).getStatus()).isEqualTo(Product.Status.DISCONTINUED);
        StringWriter out = new StringWriter();
        importService.exportCsv(null, out);
        assertThat(out.toString()).contains("MASK-" + id + ",Mask,,,Stock,DISCONTINUED");
    }

    private SaleOrderResponse order(int qty) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName("Abbey " + id);
        so.setWarehouseId(main.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(visor.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(BigDecimal.TEN);
        so.setLines(List.of(l));
        return saleOrderService.create(so);
    }
}
