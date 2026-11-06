package com.experterp.backend.integration.xero;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.integration.xero.XeroApi.Contact;
import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.TenantRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase L2: the Xero sync queue — mapping to Xero IDs, retry and backoff, never twice, waiting for what an item needs. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class XeroSyncTest {

    @Autowired XeroSyncService sync;
    @Autowired XeroConnectionService connection;
    @Autowired XeroContactSender contactSender;
    @Autowired XeroClient xero;
    @Autowired FakeXeroClient fake;
    @Autowired CustomerRepository customerRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;
    String org;

    @BeforeEach
    void setUp() {
        tenantId = tenant();
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        org = connect();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aCustomerIsSentOnceAsAContactAndAnExistingXeroContactIsLinked() {
        Customer abbey = customer("Abbey " + tenantId, "accounts@abbey.example");
        String key = XeroContactSender.customerKey(abbey.getId());
        sync.enqueue(key, "CONTACT", "Customer " + abbey.getName(), null);
        sync.enqueue(key, "CONTACT", "Customer " + abbey.getName(), null);                // queued once
        assertThat(sync.counts().get("PENDING")).isEqualTo(1);
        assertThat(sync.process().sent()).isEqualTo(1);
        String xeroId = sync.xeroId(key);
        assertThat(fake.org(tenantId).contacts.get(xeroId).emailAddress()).isEqualTo("accounts@abbey.example");
        int calls = fake.org(tenantId).calls.size();
        sync.enqueue(key, "CONTACT", "again", null);
        assertThat(sync.process().sent()).isZero();
        assertThat(fake.org(tenantId).calls).hasSize(calls);                                // a SENT item is never sent again

        // Xero has "AE Yates" already (the organisation's Xero before the ERP): linked, not created twice
        Contact inXero = xero.saveContact(connection.session(false), new Contact(null, "AE Yates " + tenantId, null, true, null, null, null));
        Customer yates = customer("AE Yates " + tenantId, null);
        sync.enqueue(XeroContactSender.customerKey(yates.getId()), "CONTACT", "Customer AE Yates", null);
        sync.process();
        assertThat(sync.xeroId(XeroContactSender.customerKey(yates.getId()))).isEqualTo(inXero.contactID());
        assertThat(fake.org(tenantId).contacts).hasSize(2);
    }

    @Test
    void retriesBackoffFailuresAndRetry() {
        // Rate limit: waits Xero's Retry-After, then goes
        String a = queue("Rate " + tenantId);
        fake.failNext(org, FakeXeroClient.Failure.RATE_LIMIT);
        assertThat(sync.process().sent()).isZero();
        XeroSyncService.Item item = item(a);
        assertThat(item.status()).isEqualTo("PENDING");
        assertThat(item.attempts()).isEqualTo(1);
        assertThat(item.lastError()).contains("Rate limit");
        assertThat(item.nextAttemptAt()).isAfter(java.time.LocalDateTime.now().minusSeconds(1));
        dueNow();
        assertThat(sync.process().sent()).isEqualTo(1);

        // Xero down: backs off; after 8 tries the item fails and waits for a person; Retry sends it
        String b = queue("Down " + tenantId);
        fake.failNext(org, FakeXeroClient.Failure.DOWN);
        sync.process();
        assertThat(item(b).nextAttemptAt()).isAfter(java.time.LocalDateTime.now().plusSeconds(50));   // 1 minute
        jdbc.update("UPDATE xero_sync_items SET attempts = ? WHERE tenant_id = ? AND item_key = ?", XeroSyncService.MAX_ATTEMPTS - 1, tenantId, b);
        dueNow();
        fake.failNext(org, FakeXeroClient.Failure.DOWN);
        assertThat(sync.process().failed()).isEqualTo(1);
        assertThat(item(b).status()).isEqualTo("FAILED");
        sync.retry(item(b).id());
        assertThat(sync.process().sent()).isEqualTo(1);
        assertThatThrownBy(() -> sync.retry(item(b).id())).hasMessageContaining("Only a failed item");

        // Xero refused it: failed at once, no retrying on its own
        String c = queue("Refused " + tenantId);
        fake.failNext(org, FakeXeroClient.Failure.VALIDATION);
        sync.process();
        assertThat(item(c).status()).isEqualTo("FAILED");
        assertThat(item(c).attempts()).isEqualTo(1);
        assertThat(item(c).lastError()).contains("validation");

        // Expired token: refreshed and sent in the same run
        String d = queue("Token " + tenantId);
        fake.failNext(org, FakeXeroClient.Failure.EXPIRED_TOKEN);
        assertThat(sync.process().sent()).isEqualTo(1);
        assertThat(item(d).status()).isEqualTo("SENT");
    }

    @Test
    void aSendRepeatedAfterACrashIsNotDuplicatedAndItemsWaitForWhatTheyNeed() {
        // Xero took the contact but the ERP stopped before saving that: the queue's send has the same Idempotency-Key
        Customer abbey = customer("Abbey " + tenantId, null);
        String key = XeroContactSender.customerKey(abbey.getId());
        sync.enqueue(key, "CONTACT", "Customer Abbey", null);
        Session s = connection.session(false);
        String first = contactSender.send(s.withKey(idempotencyKey(key)), item(key));
        sync.process();
        assertThat(sync.xeroId(key)).isEqualTo(first);
        assertThat(fake.org(tenantId).contacts).hasSize(1);

        // An item waits for the one it needs, and goes in the same run once that is sent
        Customer b = customer("Bolton " + tenantId, null), c = customer("Crewe " + tenantId, null);
        sync.enqueue(XeroContactSender.customerKey(c.getId()), "CONTACT", "Needs Bolton first", XeroContactSender.customerKey(b.getId()));
        sync.enqueue(XeroContactSender.customerKey(b.getId()), "CONTACT", "Bolton", null);
        assertThat(sync.process().sent()).isEqualTo(2);

        // Nothing sends this kind yet (L3): failed, saying so
        sync.enqueue("DOC:999", "INVOICE", "Invoice INV-1", null);
        sync.process();
        assertThat(item("DOC:999").lastError()).contains("Nothing here sends INVOICE");

        // Another organisation sees none of it
        Long other = tenant();
        TenantContext.set(other);
        assertThat(sync.items(null, 100)).isEmpty();
        assertThat(sync.xeroId(key)).isNull();
        assertThat(sync.process().sent()).isZero();
    }

    // ---- helpers ----

    private String connect() {
        String url = connection.connect();
        connection.callback(param(url, "code"), param(url, "state"));
        return connection.session(false).xeroTenantId();
    }

    private String queue(String name) {
        Customer c = customer(name, null);
        String key = XeroContactSender.customerKey(c.getId());
        sync.enqueue(key, "CONTACT", "Customer " + name, null);
        return key;
    }

    private void dueNow() {
        jdbc.update("UPDATE xero_sync_items SET next_attempt_at = now() - interval '1 second' WHERE tenant_id = ?", tenantId);
    }

    private XeroSyncService.Item item(String key) {
        return sync.items(null, 1000).stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    private String idempotencyKey(String itemKey) {
        return UUID.nameUUIDFromBytes(("expert-erp:" + tenantId + ":" + itemKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private Customer customer(String name, String email) {
        Customer c = new Customer();
        c.setName(name);
        if (email != null) {
            CustomerContact cc = new CustomerContact();
            cc.setCustomer(c);
            cc.setEmail(email);
            cc.setDefaultContact(true);
            c.getContacts().add(cc);
        }
        return customerRepository.save(c);
    }

    private Long tenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString().substring(0, 6);
        t.setName("Sync " + id);
        t.setSlug("sync-" + id);
        return tenantRepository.save(t).getId();
    }

    private static String param(String url, String name) {
        for (String p : url.substring(url.indexOf('?') + 1).split("&")) if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }
}
