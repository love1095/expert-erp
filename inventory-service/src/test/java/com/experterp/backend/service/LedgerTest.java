package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase K1: the general ledger posted from every document, manual journals, conversion balances (like Xero). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LedgerTest {

    @Autowired LedgerService ledger;
    @Autowired ChartOfAccountsService chart;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PaymentAccountRepository paymentAccountRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;
    Warehouse warehouse;
    Product helmet;
    PaymentAccount bank;
    String id;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Ledger " + id);
        t.setSlug("ledger-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELMET-" + id);
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        bank = new PaymentAccount();
        bank.setCode("650");
        bank.setName("Current Account - RBS");
        bank.setDefaultAccount(true);
        bank = paymentAccountRepository.save(bank);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * 10 helmets counted in at £6; 10 bought at £5 and billed, the bill paid from 650; 2 sold at £10 (+ VAT) and shipped
     * (COGS 2 × £6), £30 received on the £24 invoice (£6 kept as customer credit).
     */
    @Test
    void everyDocumentPostsABalancedJournalWithTheXeroMapping() {
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL,
                "OPENING", null, null, new BigDecimal("6.00"));
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        portwest = supplierRepository.save(portwest);
        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierId(portwest.getId());
        po.setWarehouseId(warehouse.getId());
        po.setOrdered(true);
        PurchaseOrderRequest.Line pl = new PurchaseOrderRequest.Line();
        pl.setProductId(helmet.getId());
        pl.setQuantity(BigDecimal.TEN);
        pl.setUnitCost(new BigDecimal("5.00"));
        po.setLines(List.of(pl));
        PurchaseOrderResponse purchase = purchaseOrderService.create(po);
        purchaseOrderService.receive(purchase.getId());
        BillRequest billReq = new BillRequest();
        billReq.setSupplierInvoiceNumber("B-" + id);
        InvoiceResponse bill = invoiceService.billPurchaseOrder(purchase.getId(), billReq);
        invoiceService.addPayment(bill.getId(), pay(bill.getTotalAmount()));

        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName("Abbey " + id);
        so.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(2));
        l.setUnitPrice(BigDecimal.TEN);
        so.setLines(List.of(l));
        SaleOrderResponse order = saleOrderService.confirm(saleOrderService.create(so).getId());
        saleOrderService.fulfill(order.getId(), null);
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse invoice = invoiceService.invoiceOrder(order.getId(), all);
        invoiceService.addPayment(invoice.getId(), pay(new BigDecimal("30.00")));

        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
        assertThat(tb.warnings()).isEmpty();
        assertThat(balance(tb, "610")).isEqualByComparingTo("0");                       // invoice paid
        assertThat(balance(tb, "800")).isEqualByComparingTo("0");                       // bill paid
        assertThat(balance(tb, "631")).isEqualByComparingTo("98.00");                   // 60 counted + 50 billed − 12 sold
        assertThat(balance(tb, "495")).isEqualByComparingTo("-60.00");                  // the count, against inventory discrepancy
        assertThat(balance(tb, "310")).isEqualByComparingTo("12.00");                   // COGS 2 × £6 (FIFO)
        assertThat(balance(tb, "200")).isEqualByComparingTo("-20.00");                  // revenue, a credit
        assertThat(balance(tb, "802")).isEqualByComparingTo("-6.00");                   // overpaid: customer credit
        BigDecimal vat = invoice.getTotalAmount().subtract(new BigDecimal("20.00")).negate()
                .add(bill.getTotalAmount().subtract(new BigDecimal("50.00")));
        assertThat(balance(tb, "820")).isEqualByComparingTo(vat);                       // input VAT less output VAT
        assertThat(balance(tb, "650")).isEqualByComparingTo(new BigDecimal("30.00").subtract(bill.getTotalAmount()));
        assertThat(chart.list(false)).anySatisfy(a -> {
            assertThat(a.code()).isEqualTo("650");
            assertThat(a.type()).isEqualTo(ChartOfAccountsService.Type.BANK);           // the payment account is a bank account
        });

        // Nothing changed: nothing posted again
        int journals = count();
        ledger.sync(true);
        assertThat(count()).isEqualTo(journals);

        // The invoice's journal, and account transactions with a running balance
        assertThat(ledger.journalReport(null, null, "DOC:" + invoice.getId())).singleElement()
                .satisfies(j -> assertThat(j.lines()).extracting(LedgerService.Line::account).contains("610", "200", "820"));
        LedgerService.AccountTransactions bankLines = ledger.accountTransactions("650", today.minusDays(1), today);
        assertThat(bankLines.opening()).isEqualByComparingTo("0");
        assertThat(bankLines.rows()).hasSize(3);                                         // bill paid, invoice paid, credit received
        assertThat(bankLines.closing()).isEqualByComparingTo(balance(tb, "650"));
    }

    @Test
    void manualJournalsBalanceAndAVoidPostsAReversal() {
        assertThatThrownBy(() -> ledger.saveManual(null, manual("Wrong", line("400", "100", null), line("650", null, "90"), false)))
                .hasMessageContaining("must be equal");
        assertThatThrownBy(() -> ledger.saveManual(null, manual("Wrong", line("999", "100", null), line("650", null, "100"), false)))
                .hasMessageContaining("999 is not in the chart");

        LedgerService.ManualJournal draft = ledger.saveManual(null, manual("Advert in Safety Weekly", line("400", "100", null), line("650", null, "100"), false));
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(balance(ledger.trialBalance(today), "400")).isEqualByComparingTo("0");   // a draft posts nothing

        ledger.post(draft.id());
        assertThat(balance(ledger.trialBalance(today), "400")).isEqualByComparingTo("100.00");
        assertThatThrownBy(() -> ledger.saveManual(draft.id(), manual("Changed", line("400", "1", null), line("650", null, "1"), false)))
                .hasMessageContaining("Only a draft");

        ledger.voidOrDelete(draft.id());
        assertThat(balance(ledger.trialBalance(today), "400")).isEqualByComparingTo("0");
        assertThat(ledger.journalReport(null, null, "MJ:" + draft.id())).hasSize(2)
                .anySatisfy(j -> assertThat(j.reversal()).isTrue());                     // never edited: reversed
    }

    @Test
    void conversionBalancesStandForEverythingBeforeTheConversionDate() {
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL,
                "OPENING", null, null, new BigDecimal("6.00"));
        assertThat(balance(ledger.trialBalance(today), "631")).isEqualByComparingTo("60.00");

        LedgerService.Settings s = ledger.settings();
        ledger.updateSettings(new LedgerService.Settings(today.plusDays(1), s.revenue(), s.inventory(), s.cogs(), s.discrepancy(),
                s.purchaseCharges(), s.customerCredit(), s.supplierDeposits(), s.receivable(), s.payable(), s.vat(), s.retainedEarnings(),
                s.suspense(), s.rounding(), s.historical()));
        LedgerService.Opening o = ledger.saveOpening(List.of(new LedgerService.OpeningBalance("650", new BigDecimal("1000"), null),
                new LedgerService.OpeningBalance("960", null, new BigDecimal("800"))));
        assertThat(o.historicalAdjustment()).isEqualByComparingTo("-200.00");

        // The stock count is before the conversion date: reversed; the balances stand on the day before, the rest in 840
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(balance(tb, "631")).isEqualByComparingTo("0");
        assertThat(balance(tb, "650")).isEqualByComparingTo("1000.00");
        assertThat(balance(tb, "960")).isEqualByComparingTo("-800.00");
        assertThat(balance(tb, "840")).isEqualByComparingTo("-200.00");
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
    }

    @Test
    void xerosChartImportAddsAndUpdatesAccounts() throws Exception {
        chart.loadStandardChart();
        String csv = """
                *Code,*Name,*Type,*Tax Code,Description,Dashboard,Expense Claims,Enable Payments
                200,Sales,Revenue,20% (VAT on Income),,No,No,No
                429,General Expenses (Expert),Overhead,20% (VAT on Expenses),,No,Yes,No
                652,Deposit Account - RBS,Bank,No VAT,,Yes,No,No
                999,Mystery,Galactic,No VAT,,No,No,No
                """;
        ChartOfAccountsService.ImportResult preview = chart.importXeroCsv(in(csv), false);
        assertThat(preview.added()).isEqualTo(1);
        assertThat(preview.updated()).isEqualTo(1);
        assertThat(preview.unchanged()).isEqualTo(1);
        assertThat(preview.warnings()).singleElement().asString().contains("Galactic");
        assertThat(chart.list(false)).noneMatch(a -> a.code().equals("652"));
        chart.importXeroCsv(in(csv), true);
        assertThat(chart.list(false)).anySatisfy(a -> {
            assertThat(a.code()).isEqualTo("652");
            assertThat(a.type()).isEqualTo(ChartOfAccountsService.Type.BANK);
        }).anySatisfy(a -> assertThat(a.name()).isEqualTo("General Expenses (Expert)"));
        assertThatThrownBy(() -> chart.save(null, new ChartOfAccountsService.AccountRequest("200", "Again", ChartOfAccountsService.Type.REVENUE, null, null, null)))
                .hasMessageContaining("used already");
    }

    private PaymentRequest pay(BigDecimal amount) {
        PaymentRequest p = new PaymentRequest();
        p.setAmount(amount);
        p.setAccountId(bank.getId());
        return p;
    }

    private static LedgerService.ManualJournalRequest manual(String narration, LedgerService.ManualLine a, LedgerService.ManualLine b, boolean post) {
        return new LedgerService.ManualJournalRequest(LocalDate.now(), narration, List.of(a, b), post);
    }

    private static LedgerService.ManualLine line(String code, String debit, String credit) {
        return new LedgerService.ManualLine(code, null, debit == null ? null : new BigDecimal(debit), credit == null ? null : new BigDecimal(credit));
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journals WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** Debit positive, credit negative; 0 when the account has no balance. */
    private static BigDecimal balance(LedgerService.TrialBalance tb, String code) {
        return tb.rows().stream().filter(r -> r.code().equals(code)).findFirst()
                .map(r -> r.debit() != null ? r.debit() : r.credit().negate()).orElse(BigDecimal.ZERO);
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
