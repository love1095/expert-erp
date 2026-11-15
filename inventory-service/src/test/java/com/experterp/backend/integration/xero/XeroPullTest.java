package com.experterp.backend.integration.xero;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.integration.xero.XeroApi.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase L4: payments entered in Xero pulled back into the ERP; webhooks checked by signature. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class XeroPullTest {

    @Autowired XeroPaymentPuller puller;
    @Autowired XeroSyncService sync;
    @Autowired XeroConnectionService connection;
    @Autowired XeroClient xero;
    @Autowired FakeXeroClient fake;
    @Autowired AccountingExportService export;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired com.experterp.backend.service.SalesLedgerService salesLedger;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PaymentAccountRepository paymentAccountRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;
    String id, org;
    PaymentAccount bank;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Pull " + id);
        t.setSlug("pull-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        bank = new PaymentAccount();
        bank.setCode("650");
        bank.setName("Current Account - RBS");
        bank.setDefaultAccount(true);
        bank = paymentAccountRepository.save(bank);
        export.updateSettings(new AccountingExportService.Settings(LocalDate.now().minusDays(30), "200", "631", "310", "310", "425", "802", "615"));
        String url = connection.connect();
        connection.callback(param(url, "code"), param(url, "state"));
        org = connection.session(false).xeroTenantId();
        export.setXeroLink("API");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aPaymentEnteredInXeroIsRecordedOnceNeverSentBackAndVoidedWhenDeletedInXero() {
        InvoiceResponse invoice = sale("Abbey " + id);                          // £24
        sync.process();
        String xeroInvoice = sync.xeroId("DOC:" + invoice.getId());
        assertThat(xeroInvoice).isNotNull();

        // £10 paid in Xero, into 650: recorded here against the invoice
        XeroApi.Payment inXero = fake.payInXero(org, xeroInvoice, new BigDecimal("10.00"), "650", LocalDate.now().minusDays(1).toString());
        XeroPaymentPuller.PullResult r = puller.pull();
        assertThat(r.recorded()).isEqualTo(1);
        InvoiceResponse after = invoiceService.findById(invoice.getId());
        assertThat(after.getAmountPaid()).isEqualByComparingTo("10.00");
        assertThat(after.getPayments()).singleElement().satisfies(p -> assertThat(p.getReference()).startsWith("Xero"));
        assertThat(puller.pulled(10)).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo("RECORDED");
            assertThat(p.number()).isEqualTo(invoice.getInvoiceNumber());
        });

        // Not sent back, not recorded twice
        int payments = fake.org(tenantId).payments.size();
        sync.process();
        assertThat(fake.org(tenantId).payments).hasSize(payments);
        assertThat(puller.pull().recorded()).isZero();
        assertThat(invoiceService.findById(invoice.getId()).getAmountPaid()).isEqualByComparingTo("10.00");

        // A payment entered here goes to Xero and is not pulled back as a new one
        PaymentRequest p = new PaymentRequest();
        p.setAmount(new BigDecimal("4.00"));
        p.setAccountId(bank.getId());
        invoiceService.addPayment(invoice.getId(), p);
        sync.process();
        assertThat(fake.org(tenantId).payments).hasSize(payments + 1);
        assertThat(puller.pull().recorded()).isZero();
        assertThat(invoiceService.findById(invoice.getId()).getAmountPaid()).isEqualByComparingTo("14.00");

        // Deleted in Xero: voided here
        xero.deletePayment(connection.session(false), inXero.paymentID());
        assertThat(puller.pull().voided()).isEqualTo(1);
        assertThat(invoiceService.findById(invoice.getId()).getAmountPaid()).isEqualByComparingTo("4.00");
        assertThat(puller.pulled(10).get(0).status()).isEqualTo("VOIDED");
    }

    @Test
    void aXeroDocumentWithTheNumberOfOneHereIsMatchedOnlyWhenTheErpExportedIt() {
        Session s = connection.session(false);
        Contact c = xero.saveContact(s, new Contact(null, "Typed in Xero " + id, null, true, null, null, null));
        // Issued here, never sent to Xero; somebody typed an invoice with the same number in Xero and it was paid there
        InvoiceResponse never = sale("Crewe " + id);
        fake.payInXero(org, twin(s, c, never.getInvoiceNumber()).invoiceID(), new BigDecimal("10.00"), "650", LocalDate.now().toString());
        XeroPaymentPuller.PullResult r = puller.pull();
        assertThat(r.recorded()).isZero();
        assertThat(r.unmatched()).isZero();
        assertThat(puller.pulled(10)).isEmpty();
        assertThat(invoiceService.findById(never.getId()).getAmountPaid()).isEqualByComparingTo("0.00");

        // Exported in a CSV file (imported into Xero by hand, so the link holds no Xero id for it): matched by its number
        InvoiceResponse byFile = sale("Derby " + id);
        jdbc.update("INSERT INTO accounting_exported (tenant_id, batch_id, doc_key, doc_date, amount, via) VALUES (?, NULL, ?, ?, 24, 'CSV')",
                tenantId, "DOC:" + byFile.getId(), java.sql.Date.valueOf(LocalDate.now()));
        fake.payInXero(org, twin(s, c, byFile.getInvoiceNumber()).invoiceID(), new BigDecimal("10.00"), "650", LocalDate.now().toString());
        assertThat(puller.pull().recorded()).isEqualTo(1);
        assertThat(invoiceService.findById(byFile.getId()).getAmountPaid()).isEqualByComparingTo("10.00");
        assertThat(invoiceService.findById(never.getId()).getAmountPaid()).isEqualByComparingTo("0.00");
    }

    private XeroApi.Invoice twin(Session s, Contact c, String number) {
        return xero.saveInvoice(s, new XeroApi.Invoice(null, "ACCREC", Contact.ref(c.contactID()), LocalDate.now().toString(), null,
                number, null, "Exclusive", List.of(new LineItem("Misc", BigDecimal.ONE, BigDecimal.TEN, "200", "NONE", BigDecimal.ZERO, null, null, null)),
                "AUTHORISED", "GBP", null, null, null, null, null));
    }

    @Test
    void paymentsOnXeroOnlyDocumentsAreIgnoredUnknownBankAccountsAreListedAndRetriedAndWebhooksAreSigned() throws Exception {
        // A Xero invoice the ERP never had
        Session s = connection.session(false);
        Contact c = xero.saveContact(s, new Contact(null, "Walk-in " + id, null, true, null, null, null));
        XeroApi.Invoice other = xero.saveInvoice(s, new XeroApi.Invoice(null, "ACCREC", Contact.ref(c.contactID()), LocalDate.now().toString(), null,
                "XERO-ONLY-1", null, "Exclusive", List.of(new LineItem("Misc", BigDecimal.ONE, BigDecimal.TEN, "200", "NONE", BigDecimal.ZERO, null, null, null)),
                "AUTHORISED", "GBP", null, null, null, null, null));
        fake.payInXero(org, other.invoiceID(), new BigDecimal("10.00"), "650", LocalDate.now().toString());
        // An ERP invoice paid in Xero into a bank account the ERP does not have
        InvoiceResponse invoice = sale("Bolton " + id);
        sync.process();
        fake.payInXero(org, sync.xeroId("DOC:" + invoice.getId()), new BigDecimal("5.00"), "699", LocalDate.now().toString());

        // The Xero-only invoice's payment is none of the ERP's business: not listed at all
        XeroPaymentPuller.PullResult r = puller.pull();
        assertThat(r.unmatched()).isZero();
        assertThat(r.errors()).isEqualTo(1);
        assertThat(puller.pulled(10)).noneMatch(p -> "UNMATCHED".equals(p.status())).anySatisfy(p -> {
            assertThat(p.status()).isEqualTo("ERROR");
            assertThat(p.message()).contains("699");
        });

        // Once the bank account exists here, the next pull tries the failed payment again and records it
        PaymentAccount extra = new PaymentAccount();
        extra.setCode("699");
        extra.setName("Second bank " + id);

        paymentAccountRepository.save(extra);
        XeroPaymentPuller.PullResult again = puller.pull();
        assertThat(again.recorded()).isEqualTo(1);
        assertThat(again.errors()).isZero();
        assertThat(puller.pulled(10)).noneMatch(p -> "ERROR".equals(p.status()));
        assertThat(puller.pull().recorded()).isZero();                                        // and only once

        // Webhooks: a good signature asks for a pull, a bad one gets 401
        XeroWebhookController hook = new XeroWebhookController(puller, objectMapper, "webhook-key-1");
        byte[] body = ("{\"events\":[{\"tenantId\":\"" + org + "\",\"eventCategory\":\"INVOICE\",\"eventType\":\"UPDATE\"}],\"firstEventSequence\":1}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(hook.receive(body, "bm90LXJpZ2h0").getStatusCode().value()).isEqualTo(401);
        assertThat(hook.receive(body, sign(body, "webhook-key-1")).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT pull_requested_at IS NOT NULL FROM xero_connections WHERE tenant_id = ?", Boolean.class, tenantId)).isTrue();
        assertThat(new XeroWebhookController(puller, objectMapper, "").receive(body, sign(body, "")).getStatusCode().value()).isEqualTo(401);

        // Another organisation sees none of it
        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-pull-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThat(puller.pulled(10)).isEmpty();
    }

    @Test
    void aPaymentVoidedHereIsDeletedInXeroAndACancelledInvoiceIsVoidedThere() {
        InvoiceResponse invoice = sale("Crewe " + id);                          // £24
        com.experterp.backend.dto.PaymentRequest pay = new com.experterp.backend.dto.PaymentRequest();
        pay.setAmount(new BigDecimal("24.00"));
        pay.setAccountId(bank.getId());
        Long payment = invoiceService.addPayment(invoice.getId(), pay).getPayments().get(0).getId();
        sync.process();
        String xeroInvoice = sync.xeroId("DOC:" + invoice.getId()), xeroPayment = sync.xeroId("PAY:" + payment);
        assertThat(fake.org(tenantId).payments.get(xeroPayment).status()).isNotEqualTo("DELETED");

        // Voided and cancelled here after they were sent: listed as changed until the link has put Xero right
        salesLedger.voidPayment(invoice.getId(), payment, "Entered twice");
        invoiceService.cancel(invoice.getId());
        assertThat(export.changedAfterExport()).hasSize(2);
        XeroSyncService.RunResult r = sync.process();
        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.failed()).isZero();
        assertThat(fake.org(tenantId).payments.get(xeroPayment).status()).isEqualTo("DELETED");
        assertThat(fake.org(tenantId).invoices.get(xeroInvoice).status()).isEqualTo("VOIDED");
        assertThat(export.changedAfterExport()).isEmpty();
        // Once only, and the payment Xero now shows as deleted changes nothing here
        assertThat(sync.process().sent()).isZero();
        XeroPaymentPuller.PullResult pulled = puller.pull();
        assertThat(pulled.voided()).isZero();
        assertThat(pulled.errors()).isZero();
    }

    private InvoiceResponse sale(String customer) {
        Warehouse w = new Warehouse();
        w.setName("WH " + customer);
        w = warehouseRepository.save(w);
        Product p = new Product();
        p.setSku("SKU-" + UUID.randomUUID().toString().substring(0, 6));
        p.setName("Helmet");
        p.setUnit("Each");
        p = productRepository.save(p);
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName(customer);
        so.setWarehouseId(w.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(2));
        l.setUnitPrice(BigDecimal.TEN);
        so.setLines(List.of(l));
        SaleOrderResponse order = saleOrderService.confirm(saleOrderService.create(so).getId());
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        return invoiceService.invoiceOrder(order.getId(), all);
    }

    private static String sign(byte[] body, String key) throws Exception {
        if (key.isEmpty()) return "x";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private static String param(String url, String name) {
        for (String p : url.substring(url.indexOf('?') + 1).split("&")) if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }
}
