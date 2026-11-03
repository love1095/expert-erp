package com.experterp.backend.security;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The platform's super admin: in no organisation, no subscription; the only one who gives organisations their plan. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PlatformTest {

    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PasswordEncoder passwordEncoder;

    Tenant org;
    User orgAdmin;
    User superAdmin;
    String password;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        org = new Tenant();
        org.setName("Platform " + id);
        org.setSlug("platform-" + id);
        org = tenantRepository.save(org);
        subscriptionService.assignFreePlan(org.getId());
        password = "Pw-" + id;
        orgAdmin = user("admin_" + id, User.Role.ADMIN, org.getId());
        superAdmin = user("sa_" + id, User.Role.SUPER_ADMIN, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aSuperAdminIsSeededInNoOrganisation() {
        assertThat(userRepository.findAll()).filteredOn(u -> u.getRole() == User.Role.SUPER_ADMIN)
                .isNotEmpty().allMatch(u -> u.getTenantId() == null);
    }

    @Test
    void theSuperAdminHasNoSubscriptionAndSeesEveryOrganisation() throws Exception {
        perform(get("/api/subscription"), superAdmin).andExpect(status().isNoContent());
        perform(get("/api/tenants"), superAdmin).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + org.getId() + ")].planTier").value("FREE"))
                .andExpect(jsonPath("$[?(@.id == " + org.getId() + ")].users").value(1));
    }

    @Test
    void onlyTheSuperAdminGivesAnOrganisationItsPlan() throws Exception {
        String enterprise = "{\"planTier\":\"ENTERPRISE\"}";
        perform(put("/api/tenants/" + org.getId() + "/subscription").contentType(MediaType.APPLICATION_JSON).content(enterprise), orgAdmin)
                .andExpect(status().isForbidden());
        perform(get("/api/tenants"), orgAdmin).andExpect(status().isForbidden());
        // The old self-service upgrade is gone
        perform(post("/api/subscription/upgrade").contentType(MediaType.APPLICATION_JSON).content(enterprise), orgAdmin)
                .andExpect(status().is4xxClientError());
        perform(get("/api/subscription"), orgAdmin).andExpect(jsonPath("$.planTier").value("FREE"));

        perform(put("/api/tenants/" + org.getId() + "/subscription").contentType(MediaType.APPLICATION_JSON).content(enterprise), superAdmin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.planTier").value("ENTERPRISE"));
        perform(get("/api/subscription"), orgAdmin).andExpect(jsonPath("$.planTier").value("ENTERPRISE"))
                .andExpect(jsonPath("$.maxUsers").value(-1));
        // The organisation's own audit log says who changed its plan
        TenantContext.set(org.getId());
        assertThat(auditLogRepository.findAll()).anyMatch(a -> "Subscription".equals(a.getEntityType())
                && superAdmin.getUsername().equals(a.getUsername()) && a.getChanges().contains("ENTERPRISE"));
        TenantContext.clear();

        perform(put("/api/tenants/" + org.getId() + "/subscription").contentType(MediaType.APPLICATION_JSON).content("{\"planTier\":\"GOLD\"}"), superAdmin)
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSwitchedOffOrganisationCannotLogIn() throws Exception {
        String login = "{\"username\":\"" + orgAdmin.getUsername() + "\",\"password\":\"" + password + "\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login)).andExpect(status().isOk());
        perform(put("/api/tenants/" + org.getId() + "/active").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), superAdmin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("This organisation is switched off. Contact Expert ERP."));
        // The super admin logs in all the same
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + superAdmin.getUsername() + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    // ---- helpers ----

    private User user(String username, User.Role role, Long tenantId) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.test");
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRole(role);
        u.setTenantId(tenantId);
        return userRepository.save(u);
    }

    private ResultActions perform(MockHttpServletRequestBuilder req, User as) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + jwtUtil.generateToken(new UserDetailsImpl(as))));
    }
}
