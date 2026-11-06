package com.experterp.backend.integration.xero;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.integration.xero.XeroApi.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase L1: the Xero client layer — the fake Xero, the connection (OAuth, encrypted tokens, refresh), the live client's handling. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class XeroClientTest {

    @Autowired XeroConnectionService connection;
    @Autowired XeroClient xero;
    @Autowired FakeXeroClient fake;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenant();
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void connectWithTheFakeTokensEncryptedAndAnExpiredTokenRefreshed() {
        assertThat(xero.mode()).isEqualTo("fake");
        assertThat(connection.status().status()).isEqualTo("NOT_CONNECTED");
        String url = connection.connect();
        assertThat(url).startsWith("/finance/xero/callback?code=fake-code-");
        String code = param(url, "code"), state = param(url, "state");
        assertThatThrownBy(() -> connection.callback(code, "not-ours")).hasMessageContaining("not for this organisation");
        XeroConnectionService.Status st = connection.callback(code, state);
        assertThat(st.status()).isEqualTo("CONNECTED");
        assertThat(st.xeroTenantName()).isEqualTo("Demo Company (UK) — fake");
        String stored = jdbc.queryForObject("SELECT access_token_encrypted FROM xero_connections WHERE tenant_id = ?", String.class, tenantId);
        assertThat(stored).doesNotStartWith("fake-access-");                                // encrypted

        // Xero says the token has expired: a refreshed session works (the refresh token is used once)
        Session s = connection.session(false);
        fake.failNext(s.xeroTenantId(), FakeXeroClient.Failure.EXPIRED_TOKEN);
        assertThatThrownBy(() -> xero.saveContact(s, contact("Abbey"))).isInstanceOfSatisfying(XeroException.class,
                e -> assertThat(e.kind()).isEqualTo(XeroException.Kind.UNAUTHORIZED));
        Session fresh = connection.session(true);
        assertThat(fresh.accessToken()).isNotEqualTo(s.accessToken());
        assertThat(xero.saveContact(fresh, contact("Abbey")).contactID()).isNotBlank();

        // Another organisation: not connected, and cannot reach this one's Xero with its own token
        Long other = tenant();
        TenantContext.set(other);
        assertThat(connection.status().status()).isEqualTo("NOT_CONNECTED");
        assertThatThrownBy(() -> connection.session(false)).hasMessageContaining("not connected");
        String url2 = connection.connect();
        connection.callback(param(url2, "code"), param(url2, "state"));
        Session theirs = connection.session(false);
        assertThat(theirs.xeroTenantId()).isNotEqualTo(fresh.xeroTenantId());
        assertThatThrownBy(() -> xero.saveContact(new Session(theirs.accessToken(), fresh.xeroTenantId()), contact("Snoop")))
                .isInstanceOf(XeroException.class);
        assertThat(fake.org(other).contacts).isEmpty();
    }

    @Test
    void everyDocumentTypeThroughTheFakeWithXerosRules() {
        Session s = connected();
        Contact abbey = xero.saveContact(s, contact("Abbey"));
        Contact portwest = xero.saveContact(s, new Contact(null, "Portwest", null, null, true, null, null));
        assertThatThrownBy(() -> xero.saveContact(s, contact("abbey"))).hasMessageContaining("already assigned");

        // Sales invoice: 2 × £10 + 20 % VAT, authorised; part paid; an overpayment refused
        Invoice inv = xero.saveInvoice(s, invoice("ACCREC", abbey, "INV-1", "200", "OUTPUT2"));
        assertThat(inv.total()).isEqualByComparingTo("24.00");
        assertThatThrownBy(() -> xero.saveInvoice(s, invoice("ACCREC", abbey, "INV-1", "200", "OUTPUT2"))).hasMessageContaining("must be unique");
        Payment p = xero.createPayment(s, payment(inv.invoiceID(), null, "10.00"));
        assertThat(p.paymentID()).isNotBlank();
        assertThatThrownBy(() -> xero.createPayment(s, payment(inv.invoiceID(), null, "20.00"))).hasMessageContaining("exceeds the amount outstanding");

        // Customer credit note for £6, applied to the invoice; the rest paid: PAID
        CreditNote cn = xero.saveCreditNote(s, new CreditNote(null, "ACCRECCREDIT", Contact.ref(abbey.contactID()), "2026-11-02", "CR-1", null, "Exclusive",
                List.of(new LineItem("Helmet", BigDecimal.ONE, new BigDecimal("5.00"), "200", "OUTPUT2", new BigDecimal("1.00"), null, null, null)),
                "AUTHORISED", null, null, null, null, null));
        xero.allocate(s, cn.creditNoteID(), new Allocation(new DocRef(inv.invoiceID(), null, null, null), new BigDecimal("6.00"), "2026-11-02"));
        xero.createPayment(s, payment(inv.invoiceID(), null, "8.00"));
        assertThat(fake.org(tenantId).invoices.get(inv.invoiceID()).status()).isEqualTo("PAID");

        // Supplier bill, supplier credit note refunded, a balanced stock journal; an unbalanced one refused
        Invoice bill = xero.saveInvoice(s, invoice("ACCPAY", portwest, "006387333", "631", "INPUT2"));
        assertThat(bill.type()).isEqualTo("ACCPAY");
        CreditNote sc = xero.saveCreditNote(s, new CreditNote(null, "ACCPAYCREDIT", Contact.ref(portwest.contactID()), "2026-11-03", "SC-1", null, "Exclusive",
                List.of(new LineItem("Return", BigDecimal.ONE, new BigDecimal("10.00"), "631", "INPUT2", new BigDecimal("2.00"), null, null, null)),
                "AUTHORISED", null, null, null, null, null));
        xero.createPayment(s, payment(null, sc.creditNoteID(), "12.00"));
        assertThat(fake.org(tenantId).creditNotes.get(sc.creditNoteID()).remainingCredit()).isEqualByComparingTo("0");
        assertThat(xero.saveManualJournal(s, new ManualJournal(null, "COGS: SO-1 shipped", "2026-11-01", "POSTED", List.of(
                new JournalLine(new BigDecimal("12.00"), "310", null, null), new JournalLine(new BigDecimal("-12.00"), "631", null, null)))).manualJournalID()).isNotBlank();
        assertThatThrownBy(() -> xero.saveManualJournal(s, new ManualJournal(null, "Bad", "2026-11-01", "POSTED", List.of(
                new JournalLine(new BigDecimal("12.00"), "310", null, null), new JournalLine(new BigDecimal("-11.00"), "631", null, null)))))
                .hasMessageContaining("debits must equal");

        // Void and delete: an invoice with payments cannot be voided until they are deleted
        assertThat(xero.voidInvoice(s, bill.invoiceID()).status()).isEqualTo("VOIDED");
        assertThatThrownBy(() -> xero.voidInvoice(s, inv.invoiceID())).hasMessageContaining("Payments must be removed");
        assertThat(xero.payments(s, null)).hasSize(3);
        xero.deletePayment(s, p.paymentID());
        assertThat(xero.payments(s, null)).filteredOn(x -> "DELETED".equals(x.status())).hasSize(1);

        // Xero failing the way it does
        fake.failNext(s.xeroTenantId(), FakeXeroClient.Failure.RATE_LIMIT, FakeXeroClient.Failure.DOWN, FakeXeroClient.Failure.VALIDATION);
        assertThatThrownBy(() -> xero.saveContact(s, contact("X"))).isInstanceOfSatisfying(XeroException.class, e -> {
            assertThat(e.kind()).isEqualTo(XeroException.Kind.RATE_LIMIT);
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.retryAfterSeconds()).isPositive();
            assertThat(e.retryable()).isTrue();
        });
        assertThatThrownBy(() -> xero.saveContact(s, contact("X"))).isInstanceOfSatisfying(XeroException.class,
                e -> assertThat(e.kind()).isEqualTo(XeroException.Kind.UNAVAILABLE));
        assertThatThrownBy(() -> xero.saveContact(s, contact("X"))).isInstanceOfSatisfying(XeroException.class,
                e -> assertThat(e.retryable()).isFalse());
        assertThat(xero.saveContact(s, contact("X")).contactID()).isNotBlank();
    }

    /** The live client against a stand-in for Xero on this machine: sign-in address, headers, Xero's errors. */
    @Test
    void theLiveClientSpeaksXerosProtocol() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] seen = new String[2];
        server.createContext("/api.xro/2.0/Contacts", ex -> {
            seen[0] = ex.getRequestHeaders().getFirst("xero-tenant-id");
            seen[1] = ex.getRequestHeaders().getFirst("Authorization");
            byte[] b = "{\"Contacts\":[{\"ContactID\":\"c-1\",\"Name\":\"Abbey\",\"Unknown\":1}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api.xro/2.0/Invoices", ex -> {
            byte[] b = "{\"Type\":\"ValidationException\",\"Elements\":[{\"ValidationErrors\":[{\"Message\":\"Invoice # must be unique.\"}]}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(400, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api.xro/2.0/Payments", ex -> {
            ex.getResponseHeaders().add("Retry-After", "7");
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpXeroClient live = new HttpXeroClient(new ObjectMapper(), "client-1", "secret-1", "https://erp.example/finance/xero/callback",
                    "https://login.xero.com", base, base);
            assertThat(live.configured()).isTrue();
            assertThat(live.authorizeUrl("st-1")).startsWith("https://login.xero.com/identity/connect/authorize?response_type=code&client_id=client-1")
                    .contains("offline_access").contains("accounting.transactions").endsWith("&state=st-1");
            Session s = new Session("tok-1", "org-1");
            assertThat(live.saveContact(s, contact("Abbey")).contactID()).isEqualTo("c-1");
            assertThat(seen).containsExactly("org-1", "Bearer tok-1");
            assertThatThrownBy(() -> live.saveInvoice(s, invoice("ACCREC", Contact.ref("c-1"), "INV-1", "200", "OUTPUT2")))
                    .isInstanceOfSatisfying(XeroException.class, e -> {
                        assertThat(e.kind()).isEqualTo(XeroException.Kind.VALIDATION);
                        assertThat(e.getMessage()).isEqualTo("Invoice # must be unique.");
                    });
            assertThatThrownBy(() -> live.payments(s, null)).isInstanceOfSatisfying(XeroException.class,
                    e -> assertThat(e.retryAfterSeconds()).isEqualTo(7));
            assertThat(new HttpXeroClient(new ObjectMapper(), "", "", "", "a", "b", "c").configured()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    // ---- helpers ----

    private Session connected() {
        String url = connection.connect();
        connection.callback(param(url, "code"), param(url, "state"));
        return connection.session(false);
    }

    private Long tenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString().substring(0, 6);
        t.setName("Xero " + id);
        t.setSlug("xero-" + id);
        return tenantRepository.save(t).getId();
    }

    private static Contact contact(String name) {
        return new Contact(null, name, null, true, null, null, null);
    }

    private static Invoice invoice(String type, Contact c, String number, String account, String tax) {
        return new Invoice(null, type, Contact.ref(c.contactID()), "2026-11-01", "2026-12-01", number, null, "Exclusive",
                List.of(new LineItem("Helmet", new BigDecimal("2"), new BigDecimal("10.00"), account, tax, new BigDecimal("4.00"), null, "HELMET", null)),
                "AUTHORISED", null, null, null, null, null, null);
    }

    private static Payment payment(String invoiceId, String creditNoteId, String amount) {
        return new Payment(null, invoiceId == null ? null : new DocRef(invoiceId, null, null, null), creditNoteId == null ? null : new DocRef(null, creditNoteId, null, null),
                new AccountRef("650", null), "2026-11-05", new BigDecimal(amount), null, null, null);
    }

    private static String param(String url, String name) {
        for (String p : url.substring(url.indexOf('?') + 1).split("&")) if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }
}
