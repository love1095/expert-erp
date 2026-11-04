package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.CreditNoteRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.OrderInvoiceRequest;
import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase H1 (spec §6): payments with date / account / reference, customer credit, allocations, voids, aged debtors. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SalesLedgerTest {

    @Autowired SalesLedgerService ledger;
    @Autowired StatementService statementService;
    @Autowired CreditService creditService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Warehouse warehouse;
    Product helmet;
    Customer customer;
    LocalDate today = LocalDate.now();
    Long bank, card;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Ledger " + id);
        t.setSlug("ledger-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        as("ROLE_MANAGER");
        warehouse = new Warehouse();
        warehouse.setName("Main");
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELM-" + id.substring(0, 6));
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        movementService.createMovement(helmet, warehouse, BigDecimal.valueOf(100), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "T", null);
        customer = new Customer();
        customer.setName("Loudfoot Builders");
        customer = customerRepository.save(customer);
        List<SalesLedgerService.AccountView> accounts = ledger.saveAccounts(List.of(
                new SalesLedgerService.AccountRequest(null, "650", "Current Account - RBS", PaymentAccount.Kind.BANK, true, true),
                new SalesLedgerService.AccountRequest(null, "643", "Card Payments", PaymentAccount.Kind.CARD, true, true)));
        bank = accounts.stream().filter(a -> a.code().equals("650")).findFirst().orElseThrow().id();
        card = accounts.stream().filter(a -> a.code().equals("643")).findFirst().orElseThrow().id();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void paymentAccountsKeepOneDefaultAndAUsedOneIsSwitchedOffNotRemoved() {
        assertThat(ledger.accounts()).filteredOn(SalesLedgerService.AccountView::defaultAccount)
                .extracting(SalesLedgerService.AccountView::code).containsExactly("650");
        pay(invoice(1, "10").getId(), "12", null, null, "BACS 1");
        // Removing both: 650 has a payment, so it stays (switched off); 643 goes
        List<SalesLedgerService.AccountView> left = ledger.saveAccounts(List.of());
        assertThat(left).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("650");
            assertThat(a.active()).isFalse();
            assertThat(a.used()).isTrue();
        });
        assertThatThrownBy(() -> ledger.saveAccounts(List.of(
                new SalesLedgerService.AccountRequest(null, "1", "A", PaymentAccount.Kind.BANK, true, false),
                new SalesLedgerService.AccountRequest(null, "1", "B", PaymentAccount.Kind.BANK, true, false))))
                .hasMessageContaining("Two payment accounts have the code 1");
    }

    @Test
    void aPaymentKeepsItsDayAccountAndReferenceAndAnOverpaymentBecomesCreditUsedOnTheNextInvoice() {
        InvoiceResponse first = invoice(5, "10");                     // £60, invoiced 5 days ago
        jdbc.update("UPDATE invoices SET issue_date = ? WHERE id = ?", today.minusDays(5), first.getId());
        InvoiceResponse paid = pay(first.getId(), "100", today.minusDays(3), card, "Card 4417");
        assertThat(paid.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(paid.getPayments()).singleElement().satisfies(p -> {
            assertThat(p.getAmount()).isEqualByComparingTo("60");
            assertThat(p.getMethod()).isEqualTo(Payment.PaymentMethod.CARD);
            assertThat(p.getAccount()).isEqualTo("643: Card Payments");
            assertThat(p.getReference()).isEqualTo("Card 4417");
            assertThat(p.getPaidAt().toLocalDate()).isEqualTo(today.minusDays(3));
        });
        // £40 over: the customer's credit, which the statement shows and the balance takes off
        assertThat(ledger.credits(customer.getId())).singleElement().satisfies(c -> {
            assertThat(c.available()).isEqualByComparingTo("40");
            assertThat(c.sourceInvoiceNumber()).isEqualTo(first.getInvoiceNumber());
        });
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("-40");
        StatementService.Statement s = statementService.statement(customer.getId(), StatementService.Type.ACTIVITY, today.minusDays(10), today);
        assertThat(s.entries()).extracting(StatementService.Entry::kind).containsExactly("INVOICE", "PAYMENT", "CREDIT");
        assertThat(s.closing()).isEqualByComparingTo("-40");

        // The next invoice (£24) is paid from the credit; £16 left; the ledger does not count it twice
        InvoiceResponse second = invoice(2, "10");
        InvoiceResponse used = ledger.useCustomerCredit(second.getId(), null);
        assertThat(used.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(used.getPayments()).singleElement().satisfies(p -> assertThat(p.getMethod()).isEqualTo(Payment.PaymentMethod.CUSTOMER_CREDIT));
        assertThat(ledger.credits(customer.getId()).get(0).available()).isEqualByComparingTo("16");
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("-16");
        assertThat(creditService.status(customer.getId()).balance()).isEqualByComparingTo("-16");
        assertThat(statementService.statement(customer.getId(), null, today.minusDays(10), today).closing()).isEqualByComparingTo("-16");

        // Money before any invoice: also credit
        ledger.receiveOnAccount(customer.getId(), new SalesLedgerService.CreditRequest(new BigDecimal("50"), today, bank, null, "BACS 9", null));
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("-66");

        assertThatThrownBy(() -> pay(invoice(1, "10").getId(), "5", today.plusDays(1), bank, null))
                .hasMessageContaining("future");
    }

    @Test
    void aCreditNoteKeptAsCreditIsAllocatedToAnotherInvoiceOfTheSameCustomer() {
        InvoiceResponse first = invoice(1, "10");                     // £12
        CreditNoteRequest req = new CreditNoteRequest();
        CreditNoteRequest.Line line = new CreditNoteRequest.Line();
        line.setInvoiceItemId(first.getItems().get(0).getId());
        line.setQuantity(BigDecimal.ONE);
        req.setLines(List.of(line));
        req.setSettle(CreditNoteRequest.Settle.KEEP_AS_CREDIT);
        InvoiceResponse cn = creditNoteService.create(first.getId(), req);

        InvoiceResponse second = invoice(3, "10");                    // £36
        InvoiceResponse after = ledger.allocateCreditNote(second.getId(), cn.getId(), null);
        assertThat(after.getAmountPaid()).isEqualByComparingTo("12");
        assertThat(invoiceService.findById(cn.getId()).getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        // first £12 + second £36 − credit note £12
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("36");

        Customer other = new Customer();
        other.setName("Someone Else Ltd");
        Long otherId = customerRepository.save(other).getId();
        InvoiceResponse theirs = invoiceFor(otherId, 1, "10");
        assertThatThrownBy(() -> ledger.allocateCreditNote(theirs.getId(), cn.getId(), null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aManagerVoidsAPaymentWithAReasonTheInvoiceOpensAgainAndTheVoidIsKept() {
        InvoiceResponse inv = invoice(1, "10");
        Long paymentId = pay(inv.getId(), "12", null, bank, "Wrong customer").getPayments().get(0).getId();

        as("ROLE_STAFF");
        assertThatThrownBy(() -> ledger.voidPayment(inv.getId(), paymentId, "Mistake")).isInstanceOf(AccessDeniedException.class);
        as("ROLE_MANAGER");
        assertThatThrownBy(() -> ledger.voidPayment(inv.getId(), paymentId, " ")).hasMessageContaining("Say why");
        InvoiceResponse after = ledger.voidPayment(inv.getId(), paymentId, "Paid by another customer");
        assertThat(after.getStatus()).isEqualTo(Invoice.InvoiceStatus.ISSUED);
        assertThat(after.getPayments()).isEmpty();
        assertThat(after.getVoidedPayments()).singleElement().satisfies(v -> {
            assertThat(v.getAmount()).isEqualByComparingTo("12");
            assertThat(v.getReason()).isEqualTo("Paid by another customer");
        });
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("12");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'INVOICE' AND entity_id = ? AND action = 'PAYMENT_VOID'",
                Integer.class, inv.getId())).isEqualTo(1);
    }

    @Test
    void agedDebtorsAgesWhatEachCustomerOwesAndAddsItUp() {
        InvoiceResponse late = invoice(10, "10");                    // £120, 45 days overdue, £20 paid
        jdbc.update("UPDATE invoices SET issue_date = ?, due_date = ? WHERE id = ?", today.minusDays(75), today.minusDays(45), late.getId());
        pay(late.getId(), "20", today.minusDays(50), bank, null);
        invoice(5, "10");                                            // £60, due later (no terms: due on issue → current)
        Customer other = new Customer();
        other.setName("Prepaid Ltd");
        Long otherId = customerRepository.save(other).getId();
        ledger.receiveOnAccount(otherId, new SalesLedgerService.CreditRequest(new BigDecimal("30"), today, bank, null, null, null));

        SalesLedgerService.AgedDebtors r = ledger.agedDebtors(null);
        assertThat(r.customers()).extracting(SalesLedgerService.AgedDebtor::customerName).containsExactly("Loudfoot Builders", "Prepaid Ltd");
        SalesLedgerService.AgedDebtor loudfoot = r.customers().get(0);
        assertThat(loudfoot.days31to60()).isEqualByComparingTo("100");
        assertThat(loudfoot.total()).isEqualByComparingTo("160");
        assertThat(r.customers().get(1).credit()).isEqualByComparingTo("-30");
        assertThat(r.customers().get(1).total()).isEqualByComparingTo("-30");
        assertThat(r.totals().total()).isEqualByComparingTo("130");
    }

    @Test
    void aShopifyOrdersInvoiceIsPaidWhenIssuedOnTheShopifyAccount() {
        ledger.saveAccounts(List.of(new SalesLedgerService.AccountRequest(bank, "650", "Current Account - RBS", PaymentAccount.Kind.BANK, true, true),
                new SalesLedgerService.AccountRequest(null, "645", "Shopify Payments", PaymentAccount.Kind.SHOPIFY, true, false)));
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customer.getId());
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.ONE);
        l.setUnitPrice(new BigDecimal("10"));
        req.setLines(List.of(l));
        Long so = saleOrderService.confirm(saleOrderService.create(req).getId(), "Ledger test").getId();
        // As the Shopify import makes it: paid at checkout
        jdbc.update("UPDATE sale_orders SET source = 'SHOPIFY', external_reference = '#1042' WHERE id = ?", so);
        saleOrderService.fulfill(so, null);
        OrderInvoiceRequest issue = new OrderInvoiceRequest();
        issue.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(so, issue);
        assertThat(inv.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(inv.getPayments()).singleElement().satisfies(p -> {
            assertThat(p.getMethod()).isEqualTo(Payment.PaymentMethod.SHOPIFY);
            assertThat(p.getAccount()).isEqualTo("645: Shopify Payments");
            assertThat(p.getReference()).isEqualTo("#1042");
        });
        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("0");
    }

    // ---- helpers ----

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    private InvoiceResponse invoice(int qty, String price) {
        return invoiceFor(customer.getId(), qty, price);
    }

    private InvoiceResponse invoiceFor(Long customerId, int qty, String price) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customerId);
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        req.setLines(List.of(l));
        Long so = saleOrderService.confirm(saleOrderService.create(req).getId(), "Ledger test").getId();
        saleOrderService.fulfill(so, null);
        OrderInvoiceRequest issue = new OrderInvoiceRequest();
        issue.setIssue(true);
        return invoiceService.invoiceOrder(so, issue);
    }

    private InvoiceResponse pay(Long invoiceId, String amount, LocalDate on, Long accountId, String reference) {
        PaymentRequest p = new PaymentRequest();
        p.setAmount(new BigDecimal(amount));
        p.setPaidOn(on);
        p.setAccountId(accountId);
        p.setReference(reference);
        return invoiceService.addPayment(invoiceId, p);
    }
}
