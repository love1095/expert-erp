package com.experterp.backend.flow;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.*;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review flow test F1, order to cash (docs/review-and-test-plan.md, Part 3): one sale from the quote to the credit
 * note on fixed figures, then the stock, the cost and the money must agree everywhere they are shown.
 *
 * Stock counted in: 100 gloves at £2.00 then 50 at £2.50 (bin A-01), 20 boots at £15.00 (bin B-01) = £625.00.
 * Sale: 120 gloves at the contract price £3.10 + 4 boots at £30.00 + carriage £8.00 = £500.00 + 20 % VAT = £600.00.
 * Cost of the goods shipped (FIFO): 100 × 2.00 + 20 × 2.50 + 4 × 15.00 = £310.00.
 * Paid £200.00, then £450.00 (£50.00 over: customer credit). 10 gloves come back on a credit note kept as credit
 * (£31.00 + VAT = £37.20): the customer is £87.20 in credit, the cost of goods £289.17 and the stock £335.83.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderToCashFlowTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired PricingService pricingService;
    @Autowired CreditService creditService;
    @Autowired PickPackService pickPack;
    @Autowired BinService bins;
    @Autowired CourierService couriers;
    @Autowired MailService mail;
    @Autowired SalesLedgerService salesLedger;
    @Autowired StatementService statementService;
    @Autowired LedgerService ledger;
    @Autowired StockMovementService movements;
    @Autowired StockMovementRepository movementRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    String id;
    Long tenantId;
    Warehouse main;
    Product gloves, boots;
    Customer customer;
    Long bank;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Flow F1 " + id);
        t.setSlug("flow-f1-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        as("ROLE_ADMIN");
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        gloves = product("GLOVES");
        boots = product("BOOTS");
        BinService.Bin a = bins.saveBin(null, new BinService.BinRequest(main.getId(), "A-01", 10, null));
        BinService.Bin b = bins.saveBin(null, new BinService.BinRequest(main.getId(), "B-01", 20, null));
        bins.setDefaultBin(gloves.getId(), main.getId(), a.id());
        bins.setDefaultBin(boots.getId(), main.getId(), b.id());
        in(gloves, 100, "2.00");
        in(gloves, 50, "2.50");
        in(boots, 20, "15.00");
        customer = new Customer();
        customer.setName("Loudfoot Builders " + id);
        CustomerContact accounts = new CustomerContact();
        accounts.setCustomer(customer);
        accounts.setName("Accounts");
        accounts.setEmail("accounts@loudfoot.example");
        accounts.setDefaultContact(true);
        customer.getContacts().add(accounts);
        customer.setCreditLimit(new BigDecimal("500"));
        customer = customerRepository.save(customer);
        pricingService.addContractPrice(customer.getId(), new PricingService.ContractPriceRequest(gloves.getId(), new BigDecimal("3.10"),
                today.minusDays(10), today.plusDays(10), "2026 framework"));
        bank = salesLedger.saveAccounts(List.of(new SalesLedgerService.AccountRequest(null, "650", "Current Account - RBS",
                PaymentAccount.Kind.BANK, true, true))).get(0).id();
        mail.updateSettings(new MailService.SettingsRequest("Expert Safety", "sales@expertsafety.example", null, null, null, null, "secret-1", true));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void fromTheQuoteToTheCreditNoteTheStockTheCostAndTheMoneyAgree() {
        // ---- quote: the contract price is found, nothing is reserved ----
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customer.getId());
        req.setWarehouseId(main.getId());
        req.setQuote(true);
        req.setShippingAddress(new Address("Unit 4, Canal Street", null, "Manchester", null, "M1 3HE", null));
        req.setLines(List.of(line(gloves, "120", null), line(boots, "4", "30.00")));
        SaleOrderRequest.Charge carriage = new SaleOrderRequest.Charge();
        carriage.setDescription("Carriage");
        carriage.setUnitPrice(new BigDecimal("8.00"));
        req.setCharges(List.of(carriage));
        SaleOrderResponse quote = saleOrderService.create(req);
        assertThat(quote.getStatus()).isEqualTo(SaleOrder.Status.QUOTE);
        assertThat(quote.getLines().get(0).getUnitPrice()).isEqualByComparingTo("3.10");
        assertThat(quote.getLines().get(0).getPriceSource()).isEqualTo("Contract price");
        assertThat(quote.getTotal()).isEqualByComparingTo("600.00");

        // ---- order: £600 is over the £500 limit, so a manager confirms it with a reason ----
        SaleOrderResponse draft = saleOrderService.convertQuote(quote.getId());
        Long orderId = draft.getId();
        assertThat(draft.getOrderNumber()).isEqualTo(quote.getOrderNumber());
        assertThat(draft.isCreditHold()).isTrue();
        assertThatThrownBy(() -> saleOrderService.confirm(orderId)).hasMessageContaining("A manager can confirm it anyway");
        as("ROLE_STAFF");
        assertThatThrownBy(() -> saleOrderService.confirm(orderId, "Agreed with the owner")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        as("ROLE_ADMIN");
        assertThat(saleOrderService.confirm(orderId, "Agreed with the owner").getStatus()).isEqualTo(SaleOrder.Status.CONFIRMED);

        // ---- pick from the bins in walk order, pack two cartons, book the courier, ship ----
        PickPackService.PickList pick = pickPack.create(List.of(orderId));
        assertThat(pick.lines()).extracting(PickPackService.PickLine::bin).containsExactly("A-01", "B-01");
        pickPack.confirm(pick.id(), null);
        assertThat(binQuantity(gloves, "A-01")).isEqualByComparingTo("30");
        assertThat(binQuantity(boots, "B-01")).isEqualByComparingTo("16");
        PickPackService.OrderWarehouse w = pickPack.order(orderId);
        pickPack.pack(orderId, List.of(
                new PickPackService.Carton(new BigDecimal("6"), null, null, null, List.of(new PickPackService.PackLine(lineOf(w, "GLOVES"), new BigDecimal("120")))),
                new PickPackService.Carton(new BigDecimal("5"), null, null, null, List.of(new PickPackService.PackLine(lineOf(w, "BOOTS"), new BigDecimal("4"))))));
        CourierService.Shipment booked = couriers.book(orderId, "DPD-NEXT-DAY");
        assertThat(booked.cartons()).containsExactly(1, 2);
        assertThat(pickPack.ship(orderId, null, null).stage()).isEqualTo("SHIPPED");
        assertThat(couriers.shipment(booked.id()).status()).isEqualTo("SHIPPED");
        assertThat(stock(gloves)).isEqualByComparingTo("30");
        assertThat(stock(boots)).isEqualByComparingTo("16");
        // FIFO: the 100 gloves at £2.00 go first, then 20 of those at £2.50, and 4 boots at £15.00
        assertThat(saleOrderService.findById(orderId).getCostOfGoods()).isEqualByComparingTo("310.00");

        // ---- invoice, e-mailed to the customer ----
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse invoice = invoiceService.invoiceOrder(orderId, all);
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("500.00");
        assertThat(invoice.getTaxTotal()).isEqualByComparingTo("100.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("600.00");
        MailService.Sent sent = mail.send(new MailService.Request("INVOICE", invoice.getId()));
        assertThat(sent.to()).isEqualTo("accounts@loudfoot.example");
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("600.00");

        // ---- part payment, then a payment £50 over: the rest becomes the customer's credit ----
        assertThat(pay(invoice.getId(), "200.00").getStatus()).isEqualTo(Invoice.InvoiceStatus.ISSUED);
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("400.00");
        InvoiceResponse paid = pay(invoice.getId(), "450.00");
        assertThat(paid.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(paid.getAmountPaid()).isEqualByComparingTo("600.00");
        assertThat(salesLedger.credits(customer.getId())).singleElement().satisfies(c -> assertThat(c.available()).isEqualByComparingTo("50.00"));
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("-50.00");

        // ---- 10 gloves come back: credit note with restock, kept as credit ----
        Long glovesItem = invoice.getItems().stream().filter(i -> gloves.getId().equals(i.getProductId())).findFirst().orElseThrow().getId();
        CreditNoteRequest cr = new CreditNoteRequest();
        CreditNoteRequest.Line back = new CreditNoteRequest.Line();
        back.setInvoiceItemId(glovesItem);
        back.setQuantity(BigDecimal.TEN);
        back.setRestock(true);
        cr.setLines(List.of(back));
        cr.setReason("Wrong size");
        cr.setSettle(CreditNoteRequest.Settle.KEEP_AS_CREDIT);
        InvoiceResponse creditNote = creditNoteService.create(invoice.getId(), cr);
        assertThat(creditNote.getTotalAmount()).isEqualByComparingTo("37.20");
        assertThat(stock(gloves)).isEqualByComparingTo("40");
        assertThat(saleOrderService.findById(orderId).getLines().get(0).getReturnedQuantity()).isEqualByComparingTo("10");

        // The goods cannot come back twice: no more is returned than was shipped and is not yet back
        SaleReturnRequest tooMany = new SaleReturnRequest();
        SaleReturnRequest.Line rl = new SaleReturnRequest.Line();
        rl.setSaleOrderLineId(lineOf(w, "GLOVES"));
        rl.setQuantity(new BigDecimal("111"));
        rl.setRestock(true);
        tooMany.setLines(List.of(rl));
        assertThatThrownBy(() -> saleOrderService.createReturn(orderId, tooMany)).hasMessageContaining("only 110 shipped and not yet returned");
        assertThat(stock(gloves)).isEqualByComparingTo("40");

        // ==== what must agree at the end ====

        // Stock: the warehouse figure is what is in the bins plus what is in no bin; nothing below zero
        BinService.ProductBins g = bins.productBins(gloves.getId(), main.getId());
        assertThat(g.warehouseStock()).isEqualByComparingTo("40");
        assertThat(g.bins().stream().map(BinService.BinStock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add).add(g.unbinned())).isEqualByComparingTo("40");
        assertThat(g.bins()).allSatisfy(x -> assertThat(x.quantity()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
        assertThat(g.unbinned()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Cost: the order's cost of goods after the return is what the ledger holds on 310, and stock on 631 is the rest
        // The 10 gloves come back at what this order's gloves cost on average (£250.00 / 120 = £2.0833): £20.83 off the £310.00
        BigDecimal cogs = saleOrderService.findById(orderId).getCostOfGoods();
        assertThat(cogs).isEqualByComparingTo("289.17");
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
        assertThat(tb.warnings()).isEmpty();
        assertThat(balance(tb, "310")).isEqualByComparingTo(cogs);
        assertThat(balance(tb, "631")).isEqualByComparingTo(new BigDecimal("625.00").subtract(cogs));
        assertThat(balance(tb, "495")).isEqualByComparingTo("-625.00");
        // ... and that is what the FIFO layers still hold, to the penny (30 × 2.50 + 16 × 15.00 + the 10 back at 2.083333)
        assertThat(jdbc.queryForObject("SELECT ROUND(COALESCE(SUM(remaining * unit_cost), 0), 2) FROM cost_layers WHERE tenant_id = ?", BigDecimal.class, tenantId))
                .isEqualByComparingTo(balance(tb, "631"));

        // Money: revenue and VAT less the credit note, the bank what was received
        assertThat(balance(tb, "200")).isEqualByComparingTo("-469.00");                 // 500.00 − 31.00
        assertThat(balance(tb, "820")).isEqualByComparingTo("-93.80");                  // 100.00 − 6.20
        assertThat(balance(tb, "650")).isEqualByComparingTo("650.00");                  // 200.00 + 450.00

        // The customer: one balance, on the customer, the statement, aged debtors, the credit check and the ledger
        BigDecimal owed = new BigDecimal("-87.20");                                      // 600.00 − 650.00 − 37.20
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo(owed);
        assertThat(statementService.statement(customer.getId(), StatementService.Type.ACTIVITY, today.minusDays(1), today).closing()).isEqualByComparingTo(owed);
        assertThat(creditService.status(customer.getId()).balance()).isEqualByComparingTo(owed);
        assertThat(salesLedger.agedDebtors(null).customers()).singleElement().satisfies(d -> assertThat(d.total()).isEqualByComparingTo(owed));
        assertThat(balance(tb, "610").add(balance(tb, "802"))).isEqualByComparingTo(owed);  // debtors + customer credit

        // The audit log has the steps
        List<String> actions = jdbc.queryForList("SELECT DISTINCT entity_type || ':' || action FROM audit_log WHERE tenant_id = ?", String.class, tenantId);
        assertThat(actions).contains("CUSTOMER:CONTRACT_PRICE", "SALE_ORDER:CREATE", "SALE_ORDER:CONVERT", "SALE_ORDER:CREDIT_OVERRIDE", "SALE_ORDER:CONFIRM",
                "PICK_LIST:CREATE", "PICK_LIST:PICKED", "SALE_ORDER:PACKED", "SALE_ORDER:COURIER_BOOKED", "SALE_ORDER:FULFILL", "INVOICE:CREATE",
                "MAIL:SEND", "INVOICE:PAYMENT", "INVOICE:CREDIT_NOTE", "SALE_ORDER:RETURN");
    }

    // ---- helpers ----

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("flow", null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    private Product product(String sku) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        return productRepository.save(p);
    }

    private void in(Product p, int n, String cost) {
        movements.createMovement(p, main, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "OPENING", null, null, new BigDecimal(cost));
    }

    private static SaleOrderRequest.Line line(Product p, String qty, String price) {
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(new BigDecimal(qty));
        if (price != null) l.setUnitPrice(new BigDecimal(price));
        return l;
    }

    private static Long lineOf(PickPackService.OrderWarehouse w, String sku) {
        return w.lines().stream().filter(l -> l.sku().startsWith(sku)).findFirst().orElseThrow().saleOrderLineId();
    }

    private BigDecimal binQuantity(Product p, String code) {
        return bins.productBins(p.getId(), main.getId()).bins().stream().filter(b -> b.code().equals(code)).findFirst().orElseThrow().quantity();
    }

    private BigDecimal stock(Product p) {
        return movementRepository.calculateStock(p.getId(), main.getId());
    }

    private InvoiceResponse pay(Long invoiceId, String amount) {
        PaymentRequest p = new PaymentRequest();
        p.setAmount(new BigDecimal(amount));
        p.setAccountId(bank);
        return invoiceService.addPayment(invoiceId, p);
    }

    private static BigDecimal balance(LedgerService.TrialBalance tb, String code) {
        return tb.rows().stream().filter(r -> r.code().equals(code)).findFirst()
                .map(r -> r.debit() != null ? r.debit() : r.credit().negate()).orElse(BigDecimal.ZERO);
    }
}
