package com.experterp.backend.security;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.integration.xero.XeroAppSettings;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.experterp.backend.security.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Expert ERP app at Xero belongs to the platform: only the super admin sets it (Platform → Integration apps), the
 * secret is kept encrypted and never sent back, and an organisation only connects its own Xero with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class XeroAppSettingsTest {

    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired XeroAppSettings settings;
    @Autowired JdbcTemplate jdbc;

    User orgAdmin, superAdmin;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Tenant org = new Tenant();
        org.setName("Xero app " + id);
        org.setSlug("xero-app-" + id);
        org = tenantRepository.save(org);
        subscriptionService.assignFreePlan(org.getId());
        orgAdmin = user("xa_admin_" + id, User.Role.ADMIN, org.getId());
        superAdmin = user("xa_sa_" + id, User.Role.SUPER_ADMIN, null);
        jdbc.update("DELETE FROM platform_integration_apps");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM platform_integration_apps");
        TenantContext.clear();
    }

    @Test
    void onlyTheSuperAdminSetsTheXeroAppAndTheSecretNeverComesBack() throws Exception {
        String app = "{\"clientId\":\"CLIENT-1\",\"clientSecret\":\"SECRET-1\",\"redirectUri\":\"https://erp.example/settings/integrations/xero/callback\",\"webhookKey\":\"HOOK-1\"}";
        perform(get("/api/platform/integration-apps/xero"), orgAdmin).andExpect(status().isForbidden());
        perform(put("/api/platform/integration-apps/xero").contentType(MediaType.APPLICATION_JSON).content(app), orgAdmin).andExpect(status().isForbidden());

        perform(get("/api/platform/integration-apps/xero"), superAdmin).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.clientSecretSet").value(false));
        String answer = perform(put("/api/platform/integration-apps/xero").contentType(MediaType.APPLICATION_JSON).content(app), superAdmin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.clientId").value("CLIENT-1"))
                .andExpect(jsonPath("$.clientSecretSet").value(true)).andExpect(jsonPath("$.webhookKeySet").value(true))
                .andExpect(jsonPath("$.updatedBy").value(superAdmin.getUsername()))
                .andReturn().getResponse().getContentAsString();
        assertThat(answer).doesNotContain("SECRET-1").doesNotContain("HOOK-1");

        // Kept encrypted; the live client gets the real values
        String stored = jdbc.queryForObject("SELECT client_secret_encrypted FROM platform_integration_apps WHERE app = 'XERO'", String.class);
        assertThat(stored).isNotBlank().doesNotContain("SECRET-1");
        assertThat(settings.app()).isEqualTo(new XeroAppSettings.App("CLIENT-1", "SECRET-1", "https://erp.example/settings/integrations/xero/callback", "HOOK-1"));

        // An empty secret keeps the saved one; a new client ID replaces the old
        perform(put("/api/platform/integration-apps/xero").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"CLIENT-2\",\"clientSecret\":\"\",\"redirectUri\":\"http://localhost:3000/settings/integrations/xero/callback\",\"webhookKey\":\"\"}"), superAdmin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientId").value("CLIENT-2"));
        assertThat(settings.app().clientSecret()).isEqualTo("SECRET-1");
        assertThat(settings.app().webhookKey()).isEqualTo("HOOK-1");

        // What cannot work is refused
        perform(put("/api/platform/integration-apps/xero").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"\",\"clientSecret\":\"x\",\"redirectUri\":\"https://erp.example/cb\"}"), superAdmin).andExpect(status().isBadRequest());
        perform(put("/api/platform/integration-apps/xero").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"C\",\"clientSecret\":\"x\",\"redirectUri\":\"erp.example/cb\"}"), superAdmin).andExpect(status().isBadRequest());

        perform(delete("/api/platform/integration-apps/xero"), superAdmin).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        assertThat(settings.app().clientId()).isEmpty();
    }

    @Test
    void theOrganisationNeverSeesTheAppAndSettingUpIsASettingsJob() throws Exception {
        String seen = perform(get("/api/xero/connection"), orgAdmin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(seen).doesNotContain("clientId").doesNotContain("clientSecret");

        // The rules: the connection and the CSV / API choice open to Settings or financial reports, the rest to financial reports
        assertThat(PermissionFilter.required("POST", "/api/xero/connection/connect")).containsExactlyInAnyOrder(SETTINGS, REPORTS_FINANCIAL);
        assertThat(PermissionFilter.required("DELETE", "/api/xero/connection")).containsExactlyInAnyOrder(SETTINGS, REPORTS_FINANCIAL);
        assertThat(PermissionFilter.required("PUT", "/api/xero/link")).containsExactlyInAnyOrder(SETTINGS, REPORTS_FINANCIAL);
        assertThat(PermissionFilter.required("GET", "/api/xero/queue")).containsExactly(REPORTS_FINANCIAL);
        assertThat(PermissionFilter.required("POST", "/api/xero/payments/pull")).containsExactly(REPORTS_FINANCIAL);
    }

    private User user(String username, User.Role role, Long tenantId) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.test");
        u.setPasswordHash(passwordEncoder.encode("Pw-12345"));
        u.setRole(role);
        u.setTenantId(tenantId);
        return userRepository.save(u);
    }

    private ResultActions perform(MockHttpServletRequestBuilder req, User as) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + jwtUtil.generateToken(new UserDetailsImpl(as))));
    }
}
