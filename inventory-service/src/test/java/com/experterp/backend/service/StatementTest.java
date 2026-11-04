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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase G3 (spec §5): customer statements (Activity, Outstanding, like Cin7), ageing and escalating overdue reminders. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StatementTest {

    @Autowired StatementService statementService;
    @Autowired StatementDocuments statementDocuments;
    @Autowired DocumentService documentService;
    @Autowired CreditService creditService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired PdfRenderer renderer;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    Warehouse warehouse;
    Product helmet;
    Customer customer;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Statement " + id);
        t.setSlug("statement-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
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
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void activityAndOutstandingStatementsAgreeAndAgeWhatIsOpen() {
        // £120 invoiced 75 days ago, due 45 days ago, £20 paid 50 days ago; £60 invoiced 5 days ago, not due yet
        InvoiceResponse old = invoice(10, "10", today.minusDays(75), today.minusDays(45));
        pay(old.getId(), "20", today.minusDays(50));
        InvoiceResponse recent = invoice(5, "10", today.minusDays(5), today.plusDays(25));

        StatementService.Statement s = statementService.statement(customer.getId(), StatementService.Type.ACTIVITY, today.minusDays(60), today);
        assertThat(s.opening()).isEqualByComparingTo("120.00");
        assertThat(s.entries()).extracting(StatementService.Entry::kind).containsExactly("PAYMENT", "INVOICE");
        assertThat(s.entries()).extracting(StatementService.Entry::balance).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100"), new BigDecimal("160"));
        assertThat(s.closing()).isEqualByComparingTo("160.00");
        assertThat(s.ageing().days31to60()).isEqualByComparingTo("100.00");
        assertThat(s.ageing().current()).isEqualByComparingTo("60.00");
        assertThat(s.ageing().total()).isEqualByComparingTo(s.closing());

        // From the start: nothing before, all three entries
        StatementService.Statement all = statementService.statement(customer.getId(), null, today.minusDays(80), today);
        assertThat(all.opening()).isEqualByComparingTo("0");
        assertThat(all.entries()).hasSize(3);
        assertThat(all.closing()).isEqualByComparingTo("160.00");

        // Back on the day the payment came in: £100 open on the old invoice, the recent one not issued yet
        StatementService.Statement then = statementService.statement(customer.getId(), StatementService.Type.OUTSTANDING, null, today.minusDays(50));
        assertThat(then.openItems()).singleElement().satisfies(i -> {
            assertThat(i.number()).isEqualTo(old.getInvoiceNumber());
            assertThat(i.open()).isEqualByComparingTo("100.00");
            assertThat(i.daysOverdue()).isZero();
        });

        StatementService.Statement open = statementService.statement(customer.getId(), StatementService.Type.OUTSTANDING, null, null);
        assertThat(open.openItems()).extracting(StatementService.OpenItem::number)
                .containsExactly(old.getInvoiceNumber(), recent.getInvoiceNumber());
        assertThat(open.openItems().get(0).daysOverdue()).isEqualTo(45);
        assertThat(open.closing()).isEqualByComparingTo("160.00");
        assertThat(creditService.status(customer.getId()).balance()).isEqualByComparingTo("160.00");

        String html = html(() -> statementDocuments.statementPrint(s, documentService.company()));
        assertThat(html).contains("Opening balance", "Loudfoot Builders", "£160.00", "31-60 days", old.getInvoiceNumber());
        byte[] pdf = statementDocuments.statements(List.of(customer.getId(), customer.getId()), StatementService.Type.OUTSTANDING, null, null).bytes();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        sample("statement-activity.pdf", statementDocuments.statement(customer.getId(), StatementService.Type.ACTIVITY, today.minusDays(60), today).bytes());
        sample("statement-outstanding.pdf", pdf);
    }

    @Test
    void aCreditNoteLargerThanWhatIsDueIsCountedOnceInWhatTheCustomerOwes() {
        // £12 invoice, £10 paid; credit note for the whole £12: £2 pays off the invoice, £10 stays as credit
        InvoiceResponse inv = invoice(1, "10", today, today.plusDays(30));
        pay(inv.getId(), "10", today);
        CreditNoteRequest req = new CreditNoteRequest();
        CreditNoteRequest.Line line = new CreditNoteRequest.Line();
        line.setInvoiceItemId(inv.getItems().get(0).getId());
        line.setQuantity(BigDecimal.ONE);
        req.setLines(List.of(line));
        InvoiceResponse cn = creditNoteService.create(inv.getId(), req);

        assertThat(statementService.balance(customer.getId())).isEqualByComparingTo("-10.00");
        assertThat(creditService.status(customer.getId()).balance()).isEqualByComparingTo("-10.00");
        StatementService.Statement s = statementService.statement(customer.getId(), StatementService.Type.ACTIVITY, today.minusDays(1), today);
        assertThat(s.entries()).extracting(StatementService.Entry::kind).containsExactly("INVOICE", "CREDIT_NOTE", "PAYMENT");
        assertThat(s.closing()).isEqualByComparingTo("-10.00");
        assertThat(s.openItems()).singleElement().satisfies(i -> {
            assertThat(i.number()).isEqualTo(cn.getInvoiceNumber());
            assertThat(i.open()).isEqualByComparingTo("-10.00");
        });
    }

    @Test
    void remindersListTheOverdueInvoicesAndGetFirmerEachTimeOneIsSent() {
        InvoiceResponse overdue = invoice(2, "10", today.minusDays(40), today.minusDays(10));
        invoice(1, "10", today, today.plusDays(30));
        Customer good = new Customer();
        good.setName("Prompt Payers Ltd");
        good = customerRepository.save(good);
        Long goodId = good.getId();
        assertThatThrownBy(() -> statementService.reminder(goodId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no overdue invoices");

        StatementService.Reminder first = statementService.reminder(customer.getId());
        assertThat(first.level()).isEqualTo(1);
        assertThat(first.items()).extracting(StatementService.OpenItem::number).containsExactly(overdue.getInvoiceNumber());
        assertThat(first.overdue()).isEqualByComparingTo("24.00");
        assertThat(first.balance()).isEqualByComparingTo("36.00");
        // Looking at it records nothing
        statementDocuments.reminder(customer.getId(), false);
        assertThat(statementService.reminder(customer.getId()).level()).isEqualTo(1);

        assertThat(statementService.overdueCustomers()).singleElement().satisfies(o -> {
            assertThat(o.customerName()).isEqualTo("Loudfoot Builders");
            assertThat(o.overdue()).isEqualByComparingTo("24.00");
            assertThat(o.daysOverdue()).isEqualTo(10);
            assertThat(o.nextLevel()).isEqualTo(1);
            assertThat(o.lastReminderAt()).isNull();
        });

        byte[] pdf = statementDocuments.reminder(customer.getId(), true).bytes();
        sample("reminder-1.pdf", pdf);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        StatementService.Reminder second = statementService.reminder(customer.getId());
        assertThat(second.level()).isEqualTo(2);
        assertThat(second.previousReminderAt()).isNotNull();
        assertThat(html(() -> statementDocuments.reminderPrint(second, documentService.company())))
                .contains("Second payment reminder", "We wrote to you on", "£24.00");

        statementDocuments.reminder(customer.getId(), true);
        statementDocuments.reminder(customer.getId(), true);
        StatementService.Reminder last = statementService.reminder(customer.getId());
        sample("reminder-final.pdf", statementDocuments.reminder(customer.getId(), false).bytes());
        assertThat(last.level()).isEqualTo(StatementService.FINAL_LEVEL);
        assertThat(html(() -> statementDocuments.reminderPrint(last, documentService.company())))
                .contains("Final notice", "Despite our earlier reminders");
        assertThat(statementService.overdueCustomers().get(0).lastReminderAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT reminder_count FROM invoices WHERE id = ?", Integer.class, overdue.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'CUSTOMER' AND entity_id = ? AND action = 'OVERDUE_REMINDER'",
                Integer.class, customer.getId())).isEqualTo(3);
    }

    // ---- helpers ----

    // The printed samples, for a look by eye: target/statement-samples
    private static void sample(String name, byte[] pdf) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("target", "statement-samples");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(name), pdf);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // What the template prints, read in one transaction as the PDF endpoints do
    private String html(java.util.function.Supplier<StatementDocuments.Print> print) {
        return tx.execute(st -> renderer.html("statement", print.get()));
    }

    private InvoiceResponse invoice(int qty, String price, LocalDate issued, LocalDate due) {
        // Later orders are for a customer on credit hold (overdue): confirmed by a manager, as G2 asks
        Long so = saleOrderService.confirm(saleOrderService.create(order(qty, price)).getId(), "Statement test").getId();
        saleOrderService.fulfill(so, null);
        OrderInvoiceRequest issue = new OrderInvoiceRequest();
        issue.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(so, issue);
        jdbc.update("UPDATE invoices SET issue_date = ?, due_date = ? WHERE id = ?", issued, due, inv.getId());
        return invoiceService.findById(inv.getId());
    }

    private void pay(Long invoiceId, String amount, LocalDate on) {
        PaymentRequest p = new PaymentRequest();
        p.setAmount(new BigDecimal(amount));
        p.setMethod(Payment.PaymentMethod.BANK);
        invoiceService.addPayment(invoiceId, p);
        jdbc.update("UPDATE payments SET paid_at = ? WHERE invoice_id = ? AND method = 'BANK'", on.atTime(10, 0), invoiceId);
    }

    private SaleOrderRequest order(int qty, String price) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customer.getId());
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        req.setLines(List.of(l));
        return req;
    }
}
