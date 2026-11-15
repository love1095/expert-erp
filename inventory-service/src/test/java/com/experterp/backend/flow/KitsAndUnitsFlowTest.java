package com.experterp.backend.flow;

import com.experterp.backend.dto.CreditNoteRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Product;
import com.experterp.backend.service.CreditNoteService;
import com.experterp.backend.service.KitService;
import com.experterp.backend.service.LedgerService;
import com.experterp.backend.service.ProductUnitService;
import com.experterp.backend.service.StatementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review flow test F3, kits and units: a kit and boxes of 100 are sold, one of each comes back.
 *
 * Stock counted in: 1,000 gloves at £0.20 and 10 glasses at £5.00 = £250.00. A starter kit is 2 gloves + 1 glasses.
 * Sale: 3 kits at £30.00 and 4 boxes of 100 gloves at the box's own £45.00 = £270.00 + VAT = £324.00.
 * Shipped: 406 gloves and 3 glasses, cost £96.20. One kit and one box come back on a credit note (£75.00 + VAT):
 * 102 gloves and 1 glasses return at the cost they left at (£25.40). Cost of goods £70.80, stock £179.20.
 */
class KitsAndUnitsFlowTest extends FlowSupport {

    @Autowired KitService kits;
    @Autowired ProductUnitService units;
    @Autowired CreditNoteService creditNoteService;
    @Autowired StatementService statementService;

    @Test
    void aKitAndBoxesAreSoldAndOneOfEachComesBackInTheProductsOwnUnitAtItsOwnCost() {
        Product gloves = product("GLOVES"), glasses = product("GLASSES"), starter = product("STARTER", Product.Type.KIT);
        in(gloves, main, 1000, "0.20");
        in(glasses, main, 10, "5.00");
        kits.setComponents(starter.getId(), List.of(new KitService.ComponentRequest(gloves.getId(), new BigDecimal("2")),
                new KitService.ComponentRequest(glasses.getId(), BigDecimal.ONE)));
        units.save(gloves.getId(), List.of(new ProductUnitService.UnitRequest("Box of 100", new BigDecimal("100"), null, new BigDecimal("45.00"), null)));
        assertThat(kits.buildable(starter.getId()).get(0).available()).isEqualByComparingTo("10");    // the glasses make 10

        // ---- 3 kits and 4 boxes: the box's own price, 406 gloves and 3 glasses reserved then shipped ----
        SaleOrderRequest.Line boxes = line(gloves, "4", null);
        boxes.setUnitName("Box of 100");
        SaleOrderResponse so = saleOrderService.confirm(saleOrderService.create(order("Abbey", line(starter, "3", "30.00"), boxes)).getId());
        assertThat(so.getLines().get(1).getUnitPrice()).isEqualByComparingTo("45.00");
        assertThat(so.getTotal()).isEqualByComparingTo("324.00");
        saleOrderService.fulfill(so.getId(), null);
        assertThat(onHand(gloves)).isEqualByComparingTo("594");
        assertThat(onHand(glasses)).isEqualByComparingTo("7");
        assertThat(saleOrderService.findById(so.getId()).getCostOfGoods()).isEqualByComparingTo("96.20");   // 406 × 0.20 + 3 × 5.00

        // ---- invoiced; one kit and one box come back on a credit note ----
        InvoiceResponse invoice = invoiceAll(so.getId());
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("324.00");
        CreditNoteRequest cr = new CreditNoteRequest();
        cr.setLines(invoice.getItems().stream().map(i -> {
            CreditNoteRequest.Line l = new CreditNoteRequest.Line();
            l.setInvoiceItemId(i.getId());
            l.setQuantity(BigDecimal.ONE);
            l.setRestock(true);
            return l;
        }).toList());
        InvoiceResponse creditNote = creditNoteService.create(invoice.getId(), cr);
        assertThat(creditNote.getTotalAmount()).isEqualByComparingTo("90.00");             // 30.00 + 45.00, + VAT
        assertThat(onHand(gloves)).isEqualByComparingTo("696");                            // + 2 from the kit + 100 from the box
        assertThat(onHand(glasses)).isEqualByComparingTo("8");
        // Each component comes back at the cost it left at
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT l.original_unit_cost FROM cost_layers l JOIN stock_movements m ON m.id = l.movement_id
                 WHERE m.type = 'SALE_RETURN_IN' AND m.product_id = ?""", BigDecimal.class, gloves.getId()))
                .singleElement().satisfies(c -> assertThat(c).isEqualByComparingTo("0.20"));
        assertThat(jdbc.queryForObject("""
                SELECT l.original_unit_cost FROM cost_layers l JOIN stock_movements m ON m.id = l.movement_id
                 WHERE m.type = 'SALE_RETURN_IN' AND m.product_id = ?""", BigDecimal.class, glasses.getId())).isEqualByComparingTo("5.00");
        // The kit holds no stock of its own
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ?", Integer.class, starter.getId())).isZero();

        // ==== what must agree at the end ====
        BigDecimal cogs = saleOrderService.findById(so.getId()).getCostOfGoods();
        assertThat(cogs).isEqualByComparingTo("70.80");                                    // 96.20 − (102 × 0.20 + 5.00)
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
        assertThat(tb.warnings()).isEmpty();
        assertThat(balance(tb, "310")).isEqualByComparingTo(cogs);
        assertThat(value(gloves).add(value(glasses))).isEqualByComparingTo("179.20");      // 696 × 0.20 + 8 × 5.00
        assertThat(balance(tb, "631")).isEqualByComparingTo("179.20");
        assertThat(layersValue()).isEqualByComparingTo("179.20");
        assertThat(balance(tb, "200")).isEqualByComparingTo("-195.00");                    // 270.00 − 75.00
        assertThat(balance(tb, "820")).isEqualByComparingTo("-39.00");
        // The customer owes the invoice less the credit note, on the statement and in the ledger
        Long customerId = invoice.getCustomerId();
        assertThat(statementService.balance(customerId)).isEqualByComparingTo("234.00");
        assertThat(balance(tb, "610")).isEqualByComparingTo("234.00");
        assertThat(auditActions()).contains("SALE_ORDER:CREATE", "SALE_ORDER:CONFIRM", "SALE_ORDER:FULFILL", "INVOICE:CREATE", "INVOICE:CREDIT_NOTE", "SALE_ORDER:RETURN");
    }
}
