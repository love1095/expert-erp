package com.experterp.backend.flow;

import com.experterp.backend.dto.StockTransferRequest;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.service.BinService;
import com.experterp.backend.service.InventoryMovementService;
import com.experterp.backend.service.LedgerService;
import com.experterp.backend.service.StockAdjustmentService;
import com.experterp.backend.service.StockTakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review flow test F4, stock control: a transfer, adjustments under and over the approval value, a full stock take
 * and a partial one with variances; then the movements, the stock value reports and the ledger must agree.
 *
 * Counted in at Main: 100 gloves at £2.00 (bin A-01) and 20 boots at £15.00 = £500.00.
 * 30 gloves go to the Shop. 2 boots damaged (£30.00, under the £50.00 limit) and 5 stolen (£75.00, a manager approves).
 * Full stock take at Main: 68 gloves counted against 70, 14 boots against 13. Partial at the Shop: 29 gloves against 30.
 * At the end: 97 gloves (£194.00) and 14 boots (£210.00) = £404.00.
 */
class StockControlFlowTest extends FlowSupport {

    @Autowired StockAdjustmentService adjustments;
    @Autowired StockTakeService stockTakes;
    @Autowired BinService bins;
    @Autowired InventoryMovementService movementSummary;

    @Test
    void transferAdjustmentsAndStockTakesLeaveTheStockItsValueAndTheLedgerInStep() {
        Warehouse shop = warehouse("Shop " + id);
        Product gloves = product("GLOVES"), boots = product("BOOTS");
        BinService.Bin a = bins.saveBin(null, new BinService.BinRequest(main.getId(), "A-01", 10, null));
        bins.setDefaultBin(gloves.getId(), main.getId(), a.id());
        in(gloves, main, 100, "2.00");
        in(boots, main, 20, "15.00");

        // ---- transfer: 30 gloves to the shop at their cost; the total worth does not change ----
        StockTransferRequest t = new StockTransferRequest();
        t.setProductId(gloves.getId());
        t.setFromWarehouseId(main.getId());
        t.setToWarehouseId(shop.getId());
        t.setQuantity(new BigDecimal("30"));
        movements.transfer(t);
        assertThat(stock(gloves, main)).isEqualByComparingTo("70");
        assertThat(stock(gloves, shop)).isEqualByComparingTo("30");
        assertThat(bins.productBins(gloves.getId(), main.getId()).bins().get(0).quantity()).isEqualByComparingTo("70");   // out of bin A-01 too
        assertThat(layersValue()).isEqualByComparingTo("500.00");
        assertThat(balance(ledger.trialBalance(today), "631")).isEqualByComparingTo("500.00");

        // ---- adjustments: £30 posts at once, £75 waits for a manager ----
        adjustments.setApprovalValue(new BigDecimal("50"));
        StockAdjustmentService.Request damaged = adjustments.adjust(new StockAdjustmentService.AdjustRequest(boots.getId(), main.getId(), null, new BigDecimal("-2"), "DAMAGE", null));
        assertThat(damaged.status()).isEqualTo("POSTED");
        assertThat(damaged.value()).isEqualByComparingTo("30.00");
        StockAdjustmentService.Request stolen = adjustments.adjust(new StockAdjustmentService.AdjustRequest(boots.getId(), main.getId(), null, new BigDecimal("-5"), "THEFT", null));
        assertThat(stolen.status()).isEqualTo("PENDING");
        assertThat(stock(boots, main)).isEqualByComparingTo("18");                         // not gone until approved
        as("ROLE_USER");
        assertThatThrownBy(() -> adjustments.approve(stolen.id(), null)).isInstanceOf(AccessDeniedException.class);
        as("ROLE_MANAGER");
        assertThat(adjustments.approve(stolen.id(), "Police report 123").status()).isEqualTo("POSTED");
        assertThat(stock(boots, main)).isEqualByComparingTo("13");

        // ---- full stock take at Main: 2 gloves short, 1 boot over ----
        StockTakeService.StockTake full = stockTakes.start(new StockTakeService.StartRequest(main.getId(), null, null, "Year end"));
        assertThat(full.scope()).isEqualTo("FULL");
        assertThat(full.lines()).extracting(StockTakeService.Line::expected).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("70"), new BigDecimal("13"));
        stockTakes.count(full.id(), full.lines().get(0).id(), new BigDecimal("68"));
        StockTakeService.StockTake counted = stockTakes.count(full.id(), full.lines().get(1).id(), new BigDecimal("14"));
        assertThat(counted.varianceValue()).isEqualByComparingTo("11.00");                // −2 × 2.00 + 1 × 15.00
        StockTakeService.PostResult posted = stockTakes.post(full.id());
        assertThat(posted.adjustments()).isEqualTo(2);
        assertThat(posted.warnings()).isEmpty();

        // ---- partial stock take at the Shop, gloves only: 1 short ----
        StockTakeService.StockTake partial = stockTakes.start(new StockTakeService.StartRequest(shop.getId(), List.of(gloves.getId()), null, null));
        assertThat(partial.scope()).isEqualTo("PARTIAL");
        assertThat(partial.lines()).singleElement().satisfies(l -> assertThat(l.expected()).isEqualByComparingTo("30"));
        stockTakes.count(partial.id(), partial.lines().get(0).id(), new BigDecimal("29"));
        assertThat(stockTakes.post(partial.id()).adjustments()).isEqualTo(1);

        // ==== what must agree at the end ====
        as("ROLE_ADMIN");
        // Movements: the stock is what was counted, in the bin and in each warehouse; nothing below zero
        assertThat(stock(gloves, main)).isEqualByComparingTo("68");
        assertThat(stock(gloves, shop)).isEqualByComparingTo("29");
        assertThat(stock(boots, main)).isEqualByComparingTo("14");
        BinService.ProductBins g = bins.productBins(gloves.getId(), main.getId());
        assertThat(g.bins().stream().map(BinService.BinStock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add).add(g.unbinned())).isEqualByComparingTo("68");
        assertThat(g.bins()).allSatisfy(x -> assertThat(x.quantity()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE tenant_id = ? AND reason_code = 'COUNT'", Integer.class, tenantId)).isEqualTo(3);

        // Value: the product pages, the stock overview, the movement summary report, the FIFO layers and the ledger
        BigDecimal worth = new BigDecimal("404.00");                                     // 97 × 2.00 + 14 × 15.00
        assertThat(value(gloves).add(value(boots))).isEqualByComparingTo(worth);
        assertThat(movements.getStockOverviewPage(0, 50, null, null, false, false).getContent().stream()
                .map(l -> l.getStockValue()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(worth);
        InventoryMovementService.Report summary = movementSummary.report(today, today, null, null);
        assertThat(summary.totals().closingValue()).isEqualByComparingTo(worth);
        assertThat(layersValue()).isEqualByComparingTo(worth);
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
        assertThat(tb.warnings()).isEmpty();
        assertThat(balance(tb, "631")).isEqualByComparingTo(worth);
        // Journals: everything went against inventory discrepancy: 500.00 in, 105.00 adjusted out, 6.00 short and 15.00 over on the counts
        assertThat(balance(tb, "495")).isEqualByComparingTo("-404.00");
        assertThat(auditActions()).contains("STOCK_MOVEMENT:TRANSFER", "INVENTORY:ADJUSTMENT_APPROVAL_VALUE", "STOCK_ADJUSTMENT:REQUESTED",
                "STOCK_ADJUSTMENT:POSTED", "STOCK_ADJUSTMENT:APPROVED", "STOCK_TAKE:STARTED", "STOCK_TAKE:POSTED");
    }

    private BigDecimal stock(Product p, Warehouse w) {
        return bins.productBins(p.getId(), w.getId()).warehouseStock();
    }
}
