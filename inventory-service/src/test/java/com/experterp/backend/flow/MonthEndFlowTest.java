package com.experterp.backend.flow;

import com.experterp.backend.dto.BillRequest;
import com.experterp.backend.dto.CreditNoteRequest;
import com.experterp.backend.dto.InvoiceRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.ReorderRequest;
import com.experterp.backend.entity.Invoice;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.integration.hmrc.HmrcClient;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.service.BankService;
import com.experterp.backend.service.CreditNoteService;
import com.experterp.backend.service.FinancialReportService;
import com.experterp.backend.service.InventoryMovementService;
import com.experterp.backend.service.LedgerPeriodService;
import com.experterp.backend.service.LedgerService;
import com.experterp.backend.service.MtdVatService;
import com.experterp.backend.service.PurchaseLedgerService;
import com.experterp.backend.service.PurchaseOrderService;
import com.experterp.backend.service.SalesProfitService;
import com.experterp.backend.service.VatReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review flow test F6, month end (docs/review-and-test-plan.md, Part 3): the month's trade, the bank statement
 * reconciled, an accrual by manual journal, the VAT return filed by MTD (fake HMRC), then every report must tie out
 * and nothing more can be entered in the locked month.
 *
 * Bought: 100 gloves at £2.00 from Portwest, billed £200.00 + VAT £40.00, £140.00 paid (£100.00 still owed).
 * Sold: 40 gloves at £6.00 to Abbey (£240.00 + VAT £48.00, paid) and 10 to Baxter (£60.00 + VAT £12.00, unpaid).
 * Bank statement: +£288.00 Abbey, −£140.00 Portwest, −£6.50 bank charges. Accrual: electricity £50.00.
 * VAT: box 1 £60.00, box 4 £40.00, box 5 £20.00, box 6 £300, box 7 £200.
 * P&L: sales £300.00, cost of goods £100.00, gross profit £200.00, expenses £56.50, net profit £143.50.
 * Balance sheet: stock £100.00, debtors £72.00, bank £141.50; creditors £100.00, VAT £20.00, accruals £50.00.
 */
@Import(MonthEndFlowTest.FakeHmrcConfig.class)
class MonthEndFlowTest extends FlowSupport {

    /** HMRC as the sandbox answers, keeping what was sent. */
    static class FakeHmrc implements HmrcClient {
        final List<Submission> submissions = new ArrayList<>();
        LocalDate from, to;

        public boolean configured() { return true; }
        public String authorizeUrl(String state) { return "https://test-api.service.hmrc.gov.uk/oauth/authorize?state=" + state; }
        public Tokens exchangeCode(String code) { return new Tokens("access", "refresh", 14400); }
        public Tokens refresh(String refreshToken) { return new Tokens("access", "refresh", 14400); }
        public List<Obligation> obligations(String vrn, String accessToken, LocalDate f, LocalDate t, Map<String, String> h) {
            return List.of(new Obligation("26M1", from, to, to.plusMonths(1).plusDays(7), "O", null));
        }
        public Receipt submit(String vrn, String accessToken, Submission s, Map<String, String> h) {
            submissions.add(s);
            return new Receipt("2026-11-17T10:15:00.000Z", "000987654321", "DD", "XM002610011595");
        }
    }

    @TestConfiguration
    static class FakeHmrcConfig {
        @Bean
        @Primary
        FakeHmrc monthEndHmrc() { return new FakeHmrc(); }
    }

    @Autowired PurchaseOrderService purchaseOrders;
    @Autowired PurchaseLedgerService purchaseLedger;
    @Autowired CreditNoteService creditNotes;
    @Autowired BankService bankService;
    @Autowired VatReturnService vatReturns;
    @Autowired MtdVatService mtd;
    @Autowired FinancialReportService reports;
    @Autowired SalesProfitService salesProfit;
    @Autowired InventoryMovementService movementSummary;
    @Autowired LedgerPeriodService periods;
    @Autowired SupplierRepository supplierRepository;
    @Autowired FakeHmrc hmrc;

    static final DateTimeFormatter UK = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final MtdVatService.ClientInfo BROWSER = new MtdVatService.ClientInfo("Mozilla/5.0 (X11; Linux x86_64)",
            "beec798b-b366-47fa-b1f8-92cede14a1ce", false, 1920, 1080, 1.0, 24, 1256, 803, "UTC+00:00", "198.51.100.7", 50411);

    @Test
    void theMonthsTradeReconciledAccruedAndFiledTiesOutOnEveryReportAndIsThenLocked() throws Exception {
        LocalDate from = today.withDayOfMonth(1);
        hmrc.from = from;
        hmrc.to = today;
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        portwest = supplierRepository.save(portwest);
        Product gloves = product("GLOVES");
        gloves.setDefaultSupplier(portwest);
        gloves.getDetails().setSupplierPrice(new BigDecimal("2.00"));
        gloves = productRepository.save(gloves);

        // ---- bought: 100 gloves at £2.00, received, billed £240.00, £140.00 paid ----
        ReorderRequest reorder = new ReorderRequest();
        reorder.setWarehouseId(main.getId());
        ReorderRequest.Line rl = new ReorderRequest.Line();
        rl.setProductId(gloves.getId());
        rl.setQuantity(new BigDecimal("100"));
        reorder.setLines(List.of(rl));
        Long poId = purchaseOrders.createFromReorder(reorder).get(0).getId();
        PurchaseOrderResponse po = purchaseOrders.markOrdered(poId);
        purchaseOrders.receive(poId);
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber("PW-" + id);
        BillRequest.Line bl = new BillRequest.Line();
        bl.setPurchaseOrderLineId(po.getLines().get(0).getId());
        bl.setQuantity(new BigDecimal("100"));
        bl.setUnitPrice(new BigDecimal("2.00"));
        b.setLines(List.of(bl));
        InvoiceResponse bill = invoiceService.billPurchaseOrder(poId, b);
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("240.00");
        pay(bill.getId(), "140.00");

        // ---- sold: Abbey 40 (paid), Baxter 10 (not paid) ----
        Long abbeyOrder = saleOrderService.confirm(saleOrderService.create(order("Abbey", line(gloves, "40", "6.00"))).getId()).getId();
        saleOrderService.fulfill(abbeyOrder, null);
        InvoiceResponse abbey = invoiceAll(abbeyOrder);
        assertThat(abbey.getTotalAmount()).isEqualByComparingTo("288.00");
        pay(abbey.getId(), "288.00");
        Long baxterOrder = saleOrderService.confirm(saleOrderService.create(order("Baxter", line(gloves, "10", "6.00"))).getId()).getId();
        saleOrderService.fulfill(baxterOrder, null);
        InvoiceResponse baxter = invoiceAll(baxterOrder);
        assertThat(baxter.getTotalAmount()).isEqualByComparingTo("72.00");

        // ---- the bank statement: imported, both payments matched, the charges created on 404 ----
        String csv = """
                Date,Description,Paid in,Paid out,Balance
                %1$s,ABBEY %2$s,288.00,,288.00
                %1$s,PORTWEST PW-%3$s,,140.00,148.00
                %1$s,SERVICE CHARGE,,6.50,141.50
                """.formatted(today.format(UK), abbey.getInvoiceNumber(), id);
        BankService.ImportResult imported = bankService.importStatement("650", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "rbs.csv", true);
        assertThat(imported.lines()).isEqualTo(3);
        List<BankService.StatementLine> open = bankService.lines("650", "UNRECONCILED");
        assertThat(open).hasSize(3);
        List<BankService.Item> unmatched = bankService.unmatched("650");
        bankService.match(open.get(0).id(), List.of(itemOf(unmatched, "288.00")));
        bankService.match(open.get(1).id(), List.of(itemOf(unmatched, "-140.00")));
        bankService.create(open.get(2).id(), new BankService.CreateRequest(null, null,
                List.of(new BankService.CreateLine("404", "Service charge", new BigDecimal("6.50"), null))));
        assertThat(bankService.lines("650", "UNRECONCILED")).isEmpty();
        BankService.BankAccount rbs = bankService.accounts().stream().filter(a -> a.code().equals("650")).findFirst().orElseThrow();
        assertThat(rbs.toReconcile()).isZero();
        assertThat(rbs.statementBalance()).isEqualByComparingTo("141.50");
        assertThat(rbs.ledgerBalance()).isEqualByComparingTo("141.50");                  // the statement and the books agree

        // ---- accrual: electricity £50.00 not billed yet ----
        ledger.saveManual(null, new LedgerService.ManualJournalRequest(today, "Electricity accrued " + id, List.of(
                new LedgerService.ManualLine("445", "Electricity", new BigDecimal("50.00"), null),
                new LedgerService.ManualLine("805", "Electricity", null, new BigDecimal("50.00"))), true));

        // ==== tie-out before filing ====
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
        assertThat(tb.warnings()).isEmpty();

        // VAT: the return (H3) and the MTD review are the same nine boxes, and the VAT account moved by box 5
        VatReturnService.Boxes boxes = vatReturns.vatReturn(from, today).boxes();
        assertThat(boxes.box1()).isEqualByComparingTo("60.00");
        assertThat(boxes.box4()).isEqualByComparingTo("40.00");
        assertThat(boxes.box5()).isEqualByComparingTo("20.00");
        assertThat(boxes.box6()).isEqualByComparingTo("300");
        assertThat(boxes.box7()).isEqualByComparingTo("200");
        MtdVatService.Review review = mtd.review(from, today);
        assertThat(review.vatReturn().boxes()).isEqualTo(boxes);
        assertThat(review.ledgerVatMovement()).isEqualByComparingTo("20.00");
        assertThat(review.warnings()).isEmpty();
        assertThat(balance(tb, "820")).isEqualByComparingTo("-20.00");

        // P&L = the sales profit report (with cost of goods) for gross profit; net profit after the bank charges and the accrual
        FinancialReportService.ProfitAndLoss pl = reports.profitAndLoss(from, today, 0, FinancialReportService.Step.MONTH);
        assertThat(pl.tradingIncome().totals().get(0)).isEqualByComparingTo("300.00");
        assertThat(pl.costOfSales().totals().get(0)).isEqualByComparingTo("100.00");
        assertThat(pl.grossProfit().values().get(0)).isEqualByComparingTo("200.00");
        assertThat(pl.operatingExpenses().totals().get(0)).isEqualByComparingTo("56.50");
        assertThat(pl.netProfit().values().get(0)).isEqualByComparingTo("143.50");
        SalesProfitService.Totals profit = salesProfit.report(from, today, SalesProfitService.CogsMode.WITHIN_DATES, SalesProfitService.GroupBy.CUSTOMER).totals();
        assertThat(profit.sale()).isEqualByComparingTo(pl.tradingIncome().totals().get(0));
        assertThat(profit.cogs()).isEqualByComparingTo(pl.costOfSales().totals().get(0));
        assertThat(profit.profit()).isEqualByComparingTo(pl.grossProfit().values().get(0));

        // Balance sheet: stock = the inventory valuation; debtors / creditors = the aged reports; bank = the reconciled account
        FinancialReportService.BalanceSheet bs = reports.balanceSheet(today, 0, FinancialReportService.Step.MONTH);
        assertThat(bs.warnings()).isEmpty();
        BigDecimal stock = movementSummary.report(from, today, null, null).totals().closingValue();
        assertThat(stock).isEqualByComparingTo("100.00");                                  // 50 × 2.00
        assertThat(layersValue()).isEqualByComparingTo(stock);
        assertThat(bsRow(bs, "631")).isEqualByComparingTo(stock);
        assertThat(bsRow(bs, "610")).isEqualByComparingTo(salesLedger.agedDebtors(today).totals().total()).isEqualByComparingTo("72.00");
        assertThat(bsRow(bs, "800")).isEqualByComparingTo(purchaseLedger.agedCreditors(today).totals().total()).isEqualByComparingTo("100.00");
        assertThat(bsRow(bs, "650")).isEqualByComparingTo(rbs.ledgerBalance());
        assertThat(bsRow(bs, "820")).isEqualByComparingTo("20.00");
        assertThat(bsRow(bs, "805")).isEqualByComparingTo("50.00");
        assertThat(bs.totalAssets().values().get(0)).isEqualByComparingTo("313.50");
        assertThat(bs.totalLiabilities().values().get(0)).isEqualByComparingTo("170.00");
        assertThat(bs.netAssets().values().get(0)).isEqualByComparingTo(pl.netProfit().values().get(0));
        assertThat(bs.equity().totals().get(0)).isEqualByComparingTo(bs.netAssets().values().get(0));

        // ---- filed by MTD (fake HMRC): what HMRC received is the return; the books lock to the period end ----
        mtd.setVrn("123456789");
        String url = mtd.connect();
        mtd.callback("code", url.substring(url.indexOf("state=") + 6));
        assertThat(mtd.obligations(null, null, BROWSER)).singleElement().satisfies(p -> assertThat(p.periodKey()).isEqualTo("26M1"));
        MtdVatService.Filed filed = mtd.submit("26M1", from, today, null, true, BROWSER);
        assertThat(filed.boxes()).isEqualTo(boxes);
        HmrcClient.Submission sent = hmrc.submissions.get(hmrc.submissions.size() - 1);
        assertThat(sent.vatDueSales()).isEqualByComparingTo("60.00");
        assertThat(sent.vatReclaimedCurrPeriod()).isEqualByComparingTo("40.00");
        assertThat(sent.netVatDue()).isEqualByComparingTo("20.00");
        assertThat(periods.periods().lockDate()).isEqualTo(today);

        // ==== nothing more can go into the filed month: for everyone but advisers (the "all users" lock, like Xero) ====
        String locked = "books are locked";
        as("ROLE_MANAGER");
        assertThatThrownBy(() -> ledger.saveManual(null, new LedgerService.ManualJournalRequest(today, "Late", List.of(
                new LedgerService.ManualLine("445", null, BigDecimal.ONE, null), new LedgerService.ManualLine("805", null, null, BigDecimal.ONE)), true)))
                .hasMessageContaining(locked);
        assertThatThrownBy(() -> bankService.createTransaction(new BankService.TransactionRequest("SPEND", today, "650", null, "Viking", null, true,
                List.of(new BankService.CreateLine("461", "Paper", new BigDecimal("12.00"), "20% (VAT on Expenses)"))))).hasMessageContaining(locked);
        assertThatThrownBy(() -> pay(baxter.getId(), "72.00")).hasMessageContaining(locked);
        assertThatThrownBy(() -> invoiceService.cancel(baxter.getId())).hasMessageContaining(locked);
        // A sales invoice, a credit note or an invoice typed in by hand would change the filed return too
        Long late = saleOrderService.confirm(saleOrderService.create(order("Carter", line(gloves, "5", "6.00"))).getId()).getId();
        saleOrderService.fulfill(late, null);
        assertThatThrownBy(() -> invoiceAll(late)).hasMessageContaining(locked);
        CreditNoteRequest cr = new CreditNoteRequest();
        CreditNoteRequest.Line cl = new CreditNoteRequest.Line();
        cl.setInvoiceItemId(baxter.getItems().get(0).getId());
        cl.setQuantity(BigDecimal.ONE);
        cr.setLines(List.of(cl));
        assertThatThrownBy(() -> creditNotes.create(baxter.getId(), cr)).hasMessageContaining(locked);
        InvoiceRequest typed = new InvoiceRequest();
        typed.setType(Invoice.InvoiceType.SALES);
        typed.setPartyName("Cash sale " + id);
        typed.setIssueDate(today.minusDays(today.getDayOfMonth() > 1 ? 1 : 0));
        InvoiceRequest.ItemRequest item = new InvoiceRequest.ItemRequest();
        item.setDescription("Gloves");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("6.00"));
        typed.setItems(List.of(item));
        Long draft = invoiceService.create(typed).getId();                                // a draft is not in the books
        assertThatThrownBy(() -> invoiceService.issue(draft)).hasMessageContaining(locked);
        // The admin is an adviser and may still post; the hard lock stops them too
        as("ROLE_ADMIN");
        LedgerPeriodService.Periods p = periods.periods();
        periods.update(new LedgerPeriodService.Periods(p.yearEndDay(), p.yearEndMonth(), today, today));
        assertThatThrownBy(() -> ledger.saveManual(null, new LedgerService.ManualJournalRequest(today, "Late", List.of(
                new LedgerService.ManualLine("445", null, BigDecimal.ONE, null), new LedgerService.ManualLine("805", null, null, BigDecimal.ONE)), true)))
                .hasMessageContaining(locked);
        assertThatThrownBy(() -> invoiceAll(late)).hasMessageContaining(locked);
        assertThatThrownBy(() -> creditNotes.create(baxter.getId(), cr)).hasMessageContaining(locked);
        // The filed figures did not move
        assertThat(vatReturns.vatReturn(from, today).boxes()).isEqualTo(boxes);
        LedgerService.TrialBalance after = ledger.trialBalance(today);
        assertThat(after.debits()).isEqualByComparingTo(tb.debits());
        assertThat(balance(after, "200")).isEqualByComparingTo("-300.00");

        assertThat(auditActions()).contains("PURCHASE_ORDER:RECEIVE", "INVOICE:PAYMENT", "MTD:SUBMIT");
    }

    private static String itemOf(List<BankService.Item> items, String amount) {
        return items.stream().filter(i -> i.amount().compareTo(new BigDecimal(amount)) == 0).findFirst().orElseThrow(
                () -> new AssertionError("Nothing of " + amount + " to match in " + items)).sourceKey();
    }

    /** An account's figure on the balance sheet, as shown (assets and liabilities both positive). */
    private static BigDecimal bsRow(FinancialReportService.BalanceSheet bs, String code) {
        return java.util.stream.Stream.concat(bs.assets().stream(), bs.liabilities().stream())
                .flatMap(s -> s.rows().stream()).filter(r -> Objects.equals(r.code(), code)).findFirst()
                .map(r -> r.values().get(0)).orElse(BigDecimal.ZERO);
    }
}
