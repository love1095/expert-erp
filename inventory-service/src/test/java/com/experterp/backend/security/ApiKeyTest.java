package com.experterp.backend.security;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.ApiKeyService;
import com.experterp.backend.service.AuditService;
import com.experterp.backend.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account ID + Application Key per organisation, like Cin7 Core's API: each key sees its own organisation only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ApiKeyTest {

    @Autowired MockMvc mvc;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired AuditService auditService;
    @Autowired AuditLogRepository auditLogRepository;

    Tenant expert;
    Tenant other;

    @BeforeEach
    void setUp() {
        expert = organisation("Expert", "Expert Warehouse");
        other = organisation("Other", "Other Warehouse");
        TenantContext.set(expert.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void eachOrganisationHasItsOwnAccountId() {
        assertThat(expert.getAccountId()).isNotNull().isNotEqualTo(other.getAccountId());
        assertThat(apiKeyService.access().accountId()).isEqualTo(expert.getAccountId());
    }

    @Test
    void aKeyReadsItsOwnOrganisationsDataOnly() throws Exception {
        ApiKeyService.Created key = apiKeyService.create("Warehouse scanner", "STAFF");
        assertThat(key.applicationKey()).startsWith("ek_").hasSizeGreaterThan(40);
        assertThat(key.key().keyPrefix()).isEqualTo(key.applicationKey().substring(0, 10));

        perform(withKey(get("/api/warehouses"), expert.getAccountId(), key.applicationKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("Expert Warehouse")))
                .andExpect(jsonPath("$.content[*].name", not(hasItem("Other Warehouse"))));
        // Only the hash is kept
        ApiKey stored = apiKeyRepository.findById(key.key().id()).orElseThrow();
        assertThat(stored.getKeyHash()).hasSize(64).doesNotContain(key.applicationKey());
        assertThat(stored.getLastUsedAt()).isNotNull();
    }

    @Test
    void aWrongPairIsRefused() throws Exception {
        ApiKeyService.Created key = apiKeyService.create("Xero sync", "MANAGER");
        // Another organisation's Account ID with this key
        perform(withKey(get("/api/warehouses"), other.getAccountId(), key.applicationKey())).andExpect(status().isUnauthorized());
        perform(withKey(get("/api/warehouses"), expert.getAccountId(), "ek_not-a-real-key")).andExpect(status().isUnauthorized());
        perform(withKey(get("/api/warehouses"), expert.getAccountId(), null)).andExpect(status().isUnauthorized());
        perform(get("/api/warehouses")).andExpect(status().isForbidden());
    }

    @Test
    void aRevokedKeyOrALapsedSubscriptionStopsAtOnce() throws Exception {
        ApiKeyService.Created key = apiKeyService.create("Old app", "STAFF");
        apiKeyService.revoke(key.key().id());
        perform(withKey(get("/api/warehouses"), expert.getAccountId(), key.applicationKey())).andExpect(status().isUnauthorized());
        assertThat(apiKeyService.access().keys()).extracting(ApiKeyService.Key::revokedAt).doesNotContainNull();

        ApiKeyService.Created second = apiKeyService.create("New app", "STAFF");
        perform(withKey(get("/api/warehouses"), expert.getAccountId(), second.applicationKey())).andExpect(status().isOk());
        Subscription sub = subscriptionRepository.findByTenantId(expert.getId()).orElseThrow();
        sub.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(sub);
        perform(withKey(get("/api/warehouses"), expert.getAccountId(), second.applicationKey())).andExpect(status().isUnauthorized());
    }

    @Test
    void aKeyWorksWithItsRoleAndNeverOnAccountAdministration() throws Exception {
        ApiKeyService.Created staff = apiKeyService.create("Scanner", "STAFF");
        Long wh = warehouseRepository.findAll().get(0).getId();
        // Deleting needs ADMIN
        perform(withKey(delete("/api/warehouses/" + wh), expert.getAccountId(), staff.applicationKey())).andExpect(status().isForbidden());
        ApiKeyService.Created admin = apiKeyService.create("Full access", "ADMIN");
        for (String path : List.of("/api/users", "/api/settings/api-keys", "/api/subscription", "/api/auth/me", "/api/settings/company")) {
            perform(withKey(get(path), expert.getAccountId(), admin.applicationKey()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error", containsString("needs a person to log in")));
        }
    }

    @Test
    void keysAreNamedGivenAPermittedRoleAndAudited() {
        assertThatThrownBy(() -> apiKeyService.create("  ", "STAFF")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> apiKeyService.create("Root", "SUPER_ADMIN")).isInstanceOf(IllegalArgumentException.class);
        assertThat(apiKeyService.create("Default", null).key().role()).isEqualTo("STAFF");

        // What a key changes is put down to the key
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new ApiKeyPrincipal(1L, expert.getId(), "Scanner", User.Role.STAFF), null, List.of()));
        auditService.log("Warehouse", 1L, "UPDATE", null);
        AuditLog last = auditLogRepository.findAll().stream().reduce((a, b) -> b).orElseThrow();
        assertThat(last.getActorType()).isEqualTo(AuditLog.ActorType.INTEGRATION);
        assertThat(last.getUsername()).isEqualTo("API: Scanner");
    }

    // ---- helpers ----

    private Tenant organisation(String name, String warehouse) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Tenant t = new Tenant();
        t.setName(name + " " + id);
        t.setSlug(name.toLowerCase() + "-" + id);
        t = tenantRepository.save(t);
        subscriptionService.assignFreePlan(t.getId());
        TenantContext.set(t.getId());
        Warehouse w = new Warehouse();
        w.setName(warehouse);
        warehouseRepository.save(w);
        TenantContext.clear();
        return tenantRepository.findById(t.getId()).orElseThrow();
    }

    // MockMvc runs the request on this thread, and the filters clear the organisation when it ends
    private ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        try {
            return mvc.perform(req);
        } finally {
            TenantContext.set(expert.getId());
        }
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder req, UUID account, String key) {
        req.header(ApiKeyService.ACCOUNT_HEADER, account.toString());
        if (key != null) req.header(ApiKeyService.KEY_HEADER, key);
        return req;
    }
}
