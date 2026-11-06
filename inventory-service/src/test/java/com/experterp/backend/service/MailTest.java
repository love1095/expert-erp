package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase M: e-mail through the fake sender — every document type, templates, statements, reminders, the log. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MailTest {

    @Autowired MailService mail;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired CustomerRepository customerRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    String id;
    Warehouse warehouse;
    Product helmet;
    Customer abbey;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Mail " + id);
        t.setSlug("mail-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        signIn("ROLE_ADMIN");
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELMET-" + id);
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        abbey = customer("Abbey " + id, "accounts@abbey.example");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void eachDocumentTypeMakesTheRightMailWithItsPdf() {
        assertThatThrownBy(() -> mail.send(new MailService.Request("SALE_ORDER", order(abbey, true).getId()))).hasMessageContaining("sender address");
        mail.updateSettings(new MailService.SettingsRequest("Expert Safety", "sales@expertsafety.example", null, null, null, null, "secret-1", true));
        assertThat(mail.settings().smtpPasswordSet()).isTrue();
        assertThat(jdbc.queryForObject("SELECT smtp_password_encrypted FROM mail_settings WHERE tenant_id = ?", String.class, TenantContext.get())).isNotEqualTo("secret-1");

        // A quote, then the order
        SaleOrderResponse quote = order(abbey, true);
        MailService.Draft d = mail.preview(new MailService.Request("SALE_ORDER", quote.getId()));
        assertThat(d.docType()).isEqualTo(MailService.DocType.QUOTE);
        assertThat(d.to()).isEqualTo("accounts@abbey.example");
        assertThat(d.subject()).isEqualTo("Quote " + quote.getOrderNumber() + " from " + d.subject().substring(d.subject().indexOf("from ") + 5));
        assertThat(d.body()).contains("Dear Abbey " + id).contains("£24.00");               // 2 × £10 + VAT
        MailService.Sent sent = mail.send(new MailService.Request("SALE_ORDER", quote.getId()));
        assertThat(sent.status()).isEqualTo("KEPT");                              // fake: kept in the outbox, not sent
        assertThat(sent.mode()).isEqualTo("fake");
        assertThat(new String(mail.attachment(sent.id()).bytes(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        SaleOrderResponse order = order(abbey, false);
        assertThat(mail.send(new MailService.Request("SALE_ORDER", order.getId())).docType()).isEqualTo(MailService.DocType.SALES_ORDER);

        // Invoice (with its due date), credit note
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(order.getId(), all);
        MailService.Sent invMail = mail.send(new MailService.Request("INVOICE", inv.getId()));
        assertThat(invMail.subject()).startsWith("Invoice " + inv.getInvoiceNumber());
        assertThat(invMail.body()).contains("£24.00").contains("due on " + inv.getDueDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        CreditNoteRequest cr = new CreditNoteRequest();
        CreditNoteRequest.Line l = new CreditNoteRequest.Line();
        l.setInvoiceItemId(inv.getItems().get(0).getId());
        l.setQuantity(BigDecimal.ONE);
        cr.getLines().add(l);
        InvoiceResponse cn = creditNoteService.create(inv.getId(), cr);
        assertThat(mail.send(new MailService.Request("CREDIT_NOTE", cn.getId())).docType()).isEqualTo(MailService.DocType.CREDIT_NOTE);
        assertThat(mail.outbox("DOC:" + inv.getId(), 10, null)).singleElement().satisfies(s -> assertThat(s.createdBy()).isEqualTo("admin"));  // its log

        // Purchase order: to the supplier's e-mail; a supplier without one: a clear error, unless an address is typed
        Supplier portwest = supplier("Portwest " + id, "orders@portwest.example");
        assertThat(mail.send(new MailService.Request("PURCHASE_ORDER", po(portwest).getId())).to()).isEqualTo("orders@portwest.example");
        Supplier quiet = supplier("Quiet " + id, null);
        Long poId = po(quiet).getId();
        assertThatThrownBy(() -> mail.send(new MailService.Request("PURCHASE_ORDER", poId))).hasMessageContaining("Quiet " + id + " has no e-mail address");
        assertThatThrownBy(() -> mail.send(new MailService.Request("PURCHASE_ORDER", poId, null, null, null, "not-an-address", null, null, null)))
                .hasMessageContaining("is not an e-mail address");
        assertThat(mail.send(new MailService.Request("PURCHASE_ORDER", poId, null, null, null, "buyer@quiet.example", null, "Our order", "Words")).subject())
                .isEqualTo("Our order");
    }

    @Test
    void statementsInBulkRemindersCountedTemplatesAndRoles() {
        mail.updateSettings(new MailService.SettingsRequest("Expert Safety", "accounts@expertsafety.example", null, null, null, null, null, true));
        Customer nobody = customer("Nobody " + id, null);
        SaleOrderResponse order = order(abbey, false);
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(order.getId(), all);
        jdbc.update("UPDATE invoices SET due_date = ? WHERE id = ?", java.sql.Date.valueOf(LocalDate.now().minusDays(20)), inv.getId());   // overdue

        // Statements to two customers: one has no e-mail address and is listed, not stopped on
        List<MailService.BulkResult> r = mail.sendStatements(List.of(abbey.getId(), nobody.getId()), "OUTSTANDING", null, null);
        assertThat(r).anySatisfy(x -> assertThat(x.status()).isEqualTo("KEPT"))
                .anySatisfy(x -> { assertThat(x.status()).isEqualTo("SKIPPED"); assertThat(x.message()).contains("no e-mail address"); });
        assertThat(mail.outbox("CUST:" + abbey.getId(), 10, null)).singleElement().satisfies(s -> assertThat(s.docType()).isEqualTo(MailService.DocType.STATEMENT));

        // Overdue reminders (G3 levels): the first one, counted on the invoice once sent
        List<MailService.BulkResult> rem = mail.sendReminders(null);
        assertThat(rem).singleElement().satisfies(x -> assertThat(x.customerId()).isEqualTo(abbey.getId()));
        assertThat(jdbc.queryForObject("SELECT reminder_count FROM invoices WHERE id = ?", Integer.class, inv.getId())).isEqualTo(1);
        assertThat(mail.outbox("CUST:" + abbey.getId(), 10, null).get(0).docType()).isEqualTo(MailService.DocType.REMINDER_1);
        assertThat(mail.preview(new MailService.Request("REMINDER", abbey.getId())).docType()).isEqualTo(MailService.DocType.REMINDER_2);

        // Templates: the organisation's words, unknown placeholders left showing; back to standard
        mail.saveTemplate(MailService.DocType.INVOICE, "Inv {{number}} {{nope}}", "Hello {{customer}}");
        MailService.Draft d = mail.preview(new MailService.Request("INVOICE", inv.getId()));
        assertThat(d.subject()).isEqualTo("Inv " + inv.getInvoiceNumber() + " {{nope}}");
        assertThat(d.body()).isEqualTo("Hello Abbey " + id);
        assertThat(mail.resetTemplate(MailService.DocType.INVOICE).custom()).isFalse();

        // Roles: staff cannot send reminders nor change templates
        signIn("ROLE_STAFF");
        assertThatThrownBy(() -> mail.send(new MailService.Request("REMINDER", abbey.getId()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mail.saveTemplate(MailService.DocType.INVOICE, "x", "y")).isInstanceOf(AccessDeniedException.class);

        // Another organisation sees none of it
        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-mail-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThat(mail.outbox(null, 100, null)).isEmpty();
        assertThat(mail.settings().fromAddress()).isNull();
    }

    // ---- helpers ----

    private SaleOrderResponse order(Customer c, boolean quote) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerId(c.getId());
        so.setWarehouseId(warehouse.getId());
        so.setQuote(quote);
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(2));
        l.setUnitPrice(BigDecimal.TEN);
        so.setLines(List.of(l));
        SaleOrderResponse r = saleOrderService.create(so);
        return quote ? r : saleOrderService.confirm(r.getId());
    }

    private PurchaseOrderResponse po(Supplier s) {
        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierId(s.getId());
        po.setWarehouseId(warehouse.getId());
        PurchaseOrderRequest.Line pl = new PurchaseOrderRequest.Line();
        pl.setProductId(helmet.getId());
        pl.setQuantity(BigDecimal.TEN);
        pl.setUnitCost(new BigDecimal("5.00"));
        po.setLines(List.of(pl));
        return purchaseOrderService.create(po);
    }

    private Customer customer(String name, String email) {
        Customer c = new Customer();
        c.setName(name);
        if (email != null) {
            CustomerContact cc = new CustomerContact();
            cc.setCustomer(c);
            cc.setName("Accounts");
            cc.setEmail(email);
            cc.setDefaultContact(true);
            c.getContacts().add(cc);
        }
        return customerRepository.save(c);
    }

    private Supplier supplier(String name, String email) {
        Supplier s = new Supplier();
        s.setName(name);
        s.setEmail(email);
        return supplierRepository.save(s);
    }

    private void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
