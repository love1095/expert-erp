package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
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

/** Phase N5: adjustments with reasons, valued at cost, approval over a set value; stock takes freeze, count, scan, post. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockCountTest {

    @Autowired StockAdjustmentService adjustments;
    @Autowired StockTakeService stockTakes;
    @Autowired BinService bins;
    @Autowired StockMovementService movements;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountingExportService exports;

    String id;
    Warehouse main;
    Product gloves, boots;
    BinService.Bin a, b;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Count " + id);
        t.setSlug("count-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        as("ROLE_ADMIN");
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        gloves = product("GLOVES", "5012345678917");
        boots = product("BOOTS", null);
        a = bins.saveBin(null, new BinService.BinRequest(main.getId(), "A-01", 10, null));
        b = bins.saveBin(null, new BinService.BinRequest(main.getId(), "B-01", 20, null));
        bins.setDefaultBin(gloves.getId(), main.getId(), a.id());
        in(gloves, 10, "4.00");      // into A-01
        in(boots, 6, "20.00");       // no bin
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void adjustmentsCarryAReasonAreValuedAtCostAndWaitForAManagerOverTheLimit() {
        assertThatThrownBy(() -> adjust(gloves, null, "-1", "BROKEN")).hasMessageContaining("Choose a reason");
        assertThatThrownBy(() -> adjust(gloves, null, "-11", "DAMAGE")).hasMessageContaining("below zero");
        assertThatThrownBy(() -> adjust(gloves, b.id(), "-1", "DAMAGE")).hasMessageContaining("Only 0 in that bin");

        // No limit: posted at once, from the bin, valued at the FIFO cost, with its reason on the movement
        StockAdjustmentService.Request r = adjust(gloves, a.id(), "-2", "damage");
        assertThat(r.status()).isEqualTo("POSTED");
        assertThat(r.value()).isEqualByComparingTo("8.00");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM stock_movements WHERE id = ?", String.class, r.movementId())).isEqualTo("DAMAGE");
        assertThat(bins.productBins(gloves.getId(), main.getId()).bins().get(0).quantity()).isEqualByComparingTo("8");
        // Journalled with the existing mapping: Dr stock discrepancy, Cr inventory, at cost
        String day = jdbc.queryForObject("SELECT created_at::date::text FROM stock_movements WHERE id = ?", String.class, r.movementId());
        assertThat(exports.journal("JNL:ADJUSTMENT_OUT:ADJ-" + r.id() + ":" + day)).hasValueSatisfying(j -> {
            assertThat(j.amount()).isEqualByComparingTo("8.00");
            assertThat(j.narration()).contains("Stock adjustment out");
        });

        // Found stock put straight into the bin where it was found (not the default bin)
        adjust(gloves, b.id(), "1", "FOUND");
        BinService.ProductBins pb = bins.productBins(gloves.getId(), main.getId());
        assertThat(pb.bins()).extracting(BinService.BinStock::code).containsExactly("A-01", "B-01");
        assertThat(pb.bins().get(1).quantity()).isEqualByComparingTo("1");

        // Over £50 waits for a manager; a sales user cannot set the limit or approve
        adjustments.setApprovalValue(new BigDecimal("50"));
        StockAdjustmentService.Request big = adjust(boots, null, "-3", "THEFT");     // 3 × £20 = £60
        assertThat(big.status()).isEqualTo("PENDING");
        assertThat(big.movementId()).isNull();
        assertThat(bins.productBins(boots.getId(), main.getId()).warehouseStock()).isEqualByComparingTo("6");
        assertThat(adjust(boots, null, "-1", "SAMPLE").status()).isEqualTo("POSTED");   // £20: under the limit
        as("ROLE_USER");
        assertThatThrownBy(() -> adjustments.approve(big.id(), null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> adjustments.setApprovalValue(null)).isInstanceOf(AccessDeniedException.class);
        as("ROLE_MANAGER");
        StockAdjustmentService.Request done = adjustments.approve(big.id(), "Police report 123");
        assertThat(done.status()).isEqualTo("POSTED");
        assertThat(done.decidedBy()).isNotNull();
        assertThat(bins.productBins(boots.getId(), main.getId()).warehouseStock()).isEqualByComparingTo("2");
        assertThatThrownBy(() -> adjustments.reject(big.id(), null)).hasMessageContaining("posted");
        assertThat(adjustments.requests("PENDING")).isEmpty();
    }

    @Test
    void aStockTakeFreezesCountsByScreenAndScannerAndPostsTheVariances() {
        StockTakeService.StockTake st = stockTakes.start(new StockTakeService.StartRequest(main.getId(), null, null, "Year end"));
        assertThat(st.scope()).isEqualTo("FULL");
        assertThat(st.lines()).extracting(StockTakeService.Line::bin).containsExactly("A-01", null);   // gloves in A-01, boots unbinned
        assertThatThrownBy(() -> stockTakes.start(new StockTakeService.StartRequest(main.getId(), null, null, null))).hasMessageContaining("still being counted");

        // Gloves: 3 scanned by barcode, 5 more typed → 8 counted against 10; boots 7 counted against 6; 2 gloves found in B-01
        Long glovesLine = st.lines().get(0).id(), bootsLine = st.lines().get(1).id();
        stockTakes.scan(st.id(), "5012345678917", a.id(), null);
        stockTakes.scan(st.id(), "gloves-" + id, a.id(), new BigDecimal("2"));
        stockTakes.count(st.id(), glovesLine, new BigDecimal("8"));
        stockTakes.count(st.id(), bootsLine, new BigDecimal("7"));
        st = stockTakes.scan(st.id(), "GLOVES-" + id, b.id(), new BigDecimal("2"));
        Long takeId = st.id();
        assertThatThrownBy(() -> stockTakes.scan(takeId, "NOPE", null, null)).hasMessageContaining("No product");
        assertThat(st.lines()).hasSize(3);
        assertThat(st.countedLines()).isEqualTo(3);
        // Variance value: −2 × £4 + 2 × £4 + 1 × £20 = £20
        assertThat(st.varianceValue()).isEqualByComparingTo("20.00");

        // A sale ships 1 boot while counting: warned, the boot's +1 still posted from the frozen figure
        movements.createMovement(boots, main, BigDecimal.ONE, MovementType.SALE_OUT, ReferenceType.MANUAL, "SO", null);
        as("ROLE_USER");
        assertThatThrownBy(() -> stockTakes.post(takeId)).isInstanceOf(AccessDeniedException.class);
        as("ROLE_MANAGER");
        StockTakeService.PostResult r = stockTakes.post(takeId);
        assertThat(r.adjustments()).isEqualTo(3);
        assertThat(r.warnings()).singleElement().satisfies(w -> assertThat(w).contains("BOOTS-" + id).contains("6 when the count started, 5 now"));
        assertThat(r.stockTake().status()).isEqualTo("POSTED");
        BinService.ProductBins g = bins.productBins(gloves.getId(), main.getId());
        assertThat(g.warehouseStock()).isEqualByComparingTo("10");                    // 8 in A-01 + 2 in B-01
        assertThat(g.bins()).extracting(BinService.BinStock::quantity).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("8"), new BigDecimal("2"));
        BinService.ProductBins bt = bins.productBins(boots.getId(), main.getId());
        assertThat(bt.warehouseStock()).isEqualByComparingTo("6");                    // 6 − 1 sold + 1 found
        assertThat(bt.unbinned()).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE tenant_id = ? AND reason_code = 'COUNT'", Integer.class,
                TenantContext.get())).isEqualTo(3);
        assertThatThrownBy(() -> stockTakes.count(takeId, glovesLine, BigDecimal.ONE)).hasMessageContaining("posted");
    }

    @Test
    void aPartialStockTakeLeavesUncountedLinesAloneAndOtherOrganisationsSeeNothing() {
        StockTakeService.StockTake st = stockTakes.start(new StockTakeService.StartRequest(main.getId(), List.of(boots.getId()), null, null));
        assertThat(st.scope()).isEqualTo("PARTIAL");
        assertThat(st.lines()).singleElement().satisfies(l -> assertThat(l.sku()).isEqualTo("BOOTS-" + id));
        as("ROLE_MANAGER");
        StockTakeService.PostResult r = stockTakes.post(st.id());                     // nothing counted: nothing changes
        assertThat(r.adjustments()).isZero();
        assertThat(bins.productBins(boots.getId(), main.getId()).warehouseStock()).isEqualByComparingTo("6");
        StockTakeService.StockTake again = stockTakes.start(new StockTakeService.StartRequest(main.getId(), List.of(gloves.getId()), null, null));
        assertThat(stockTakes.cancel(again.id()).status()).isEqualTo("CANCELLED");
        adjust(gloves, null, "-1", "OTHER");

        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-count-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThat(stockTakes.stockTakes(null)).isEmpty();
        assertThat(adjustments.requests(null)).isEmpty();
        assertThat(adjustments.approvalValue()).isNull();
        assertThatThrownBy(() -> stockTakes.stockTake(st.id())).hasMessageContaining("not found");
        assertThatThrownBy(() -> stockTakes.start(new StockTakeService.StartRequest(main.getId(), null, null, null))).hasMessageContaining("Warehouse not found");
    }

    // ---- helpers ----

    private StockAdjustmentService.Request adjust(Product p, Long bin, String qty, String reason) {
        return adjustments.adjust(new StockAdjustmentService.AdjustRequest(p.getId(), main.getId(), bin, new BigDecimal(qty), reason, null));
    }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u-" + role, null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    private Product product(String sku, String barcode) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        p.setBarcode(barcode);
        return productRepository.save(p);
    }

    private void in(Product p, int n, String cost) {
        movements.createMovement(p, main, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, new BigDecimal(cost));
    }
}
