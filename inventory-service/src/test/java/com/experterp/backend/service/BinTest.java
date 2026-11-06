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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase N1: bins, default bins, stock per bin following the stock, moves, opening bins from Cin7. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BinTest {

    @Autowired BinService bins;
    @Autowired StockMovementService movements;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    String id;
    Warehouse main;
    Product helmet;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Bins " + id);
        t.setSlug("bins-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        helmet = new Product();
        helmet.setSku("HELMET-" + id);
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void stockComesIntoTheDefaultBinMovesBetweenBinsAndLeavesUnbinnedFirst() {
        BinService.Bin a = bins.saveBin(null, new BinService.BinRequest(main.getId(), "A-01", null, null));
        BinService.Bin b = bins.saveBin(null, new BinService.BinRequest(main.getId(), "B-01", null, null));
        assertThat(b.walkOrder()).isGreaterThan(a.walkOrder());
        assertThatThrownBy(() -> bins.saveBin(null, new BinService.BinRequest(main.getId(), "a-01", null, null))).hasMessageContaining("already");

        // 4 counted in with no default bin: unbinned; then A-01 is the default: 10 received go into it
        in(4);
        bins.setDefaultBin(helmet.getId(), main.getId(), a.id());
        in(10);
        BinService.ProductBins pb = bins.productBins(helmet.getId(), main.getId());
        assertThat(pb.warehouseStock()).isEqualByComparingTo("14");
        assertThat(pb.unbinned()).isEqualByComparingTo("4");
        assertThat(qty(pb, a.id())).isEqualByComparingTo("10");

        // Moved by hand: 3 from A-01 to B-01; more than a bin holds refused
        bins.move(helmet.getId(), main.getId(), a.id(), b.id(), new BigDecimal("3"), "test");
        assertThatThrownBy(() -> bins.move(helmet.getId(), main.getId(), a.id(), b.id(), new BigDecimal("8"), "test")).hasMessageContaining("Only 7");
        pb = bins.productBins(helmet.getId(), main.getId());
        assertThat(qty(pb, a.id())).isEqualByComparingTo("7");
        assertThat(qty(pb, b.id())).isEqualByComparingTo("3");

        // 6 leave (a shipment not picked from a bin): the 4 unbinned first, then 2 from the last bin in the walk (B-01)
        out(6);
        pb = bins.productBins(helmet.getId(), main.getId());
        assertThat(pb.warehouseStock()).isEqualByComparingTo("8");
        assertThat(pb.unbinned()).isEqualByComparingTo("0");
        assertThat(qty(pb, b.id())).isEqualByComparingTo("1");
        assertThat(qty(pb, a.id())).isEqualByComparingTo("7");

        // A bin with stock cannot be made inactive; a bin that held stock cannot be deleted; an empty new one can
        assertThatThrownBy(() -> bins.saveBin(a.id(), new BinService.BinRequest(null, "A-01", null, false))).hasMessageContaining("holds stock");
        assertThatThrownBy(() -> bins.deleteBin(b.id())).hasMessageContaining("make it inactive");
        BinService.Bin c = bins.saveBin(null, new BinService.BinRequest(main.getId(), "C-01", 5, null));
        bins.deleteBin(c.id());
        assertThat(bins.bins(main.getId())).extracting(BinService.Bin::code).containsExactly("A-01", "B-01");
    }

    @Test
    void openingBinsFromCin7sAvailabilityExport() throws Exception {
        in(20);
        String csv = """
                Category,SKU,ProductName,Location,Bin,OnHand
                PPE,HELMET-%1$s,Helmet,Main,A-01,12
                PPE,HELMET-%1$s,Helmet,Main,B-02,30
                PPE,NOPE-%1$s,Nothing,Main,A-01,1
                PPE,HELMET-%1$s,Helmet,Main,,5
                """.formatted(id);
        BinService.ImportResult preview = bins.importBins(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), main.getId(), false);
        assertThat(preview.binsCreated()).isEqualTo(2);
        assertThat(bins.bins(main.getId())).isEmpty();                                   // a preview writes nothing
        BinService.ImportResult r = bins.importBins(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), main.getId(), true);
        assertThat(r.warnings()).anyMatch(w -> w.contains("NOPE-" + id)).anyMatch(w -> w.contains("only 8"));
        BinService.ProductBins pb = bins.productBins(helmet.getId(), main.getId());
        assertThat(pb.bins()).extracting(BinService.BinStock::code).containsExactly("A-01", "B-02");
        assertThat(pb.bins().get(0).quantity()).isEqualByComparingTo("12");
        assertThat(pb.bins().get(1).quantity()).isEqualByComparingTo("8");                // capped at what the warehouse has
        assertThat(pb.unbinned()).isEqualByComparingTo("0");

        // Another organisation sees none of it
        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-bins-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThat(bins.bins(main.getId())).isEmpty();
        assertThatThrownBy(() -> bins.saveBin(null, new BinService.BinRequest(main.getId(), "X", null, null))).hasMessageContaining("Warehouse not found");
    }

    private void in(int n) {
        movements.createMovement(helmet, main, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, new BigDecimal("5.00"));
    }

    private void out(int n) {
        movements.createMovement(helmet, main, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_OUT, ReferenceType.MANUAL, "OUT", null);
    }

    private static BigDecimal qty(BinService.ProductBins pb, Long bin) {
        return pb.bins().stream().filter(x -> x.binId().equals(bin)).map(BinService.BinStock::quantity).findFirst().orElse(BigDecimal.ZERO);
    }
}
