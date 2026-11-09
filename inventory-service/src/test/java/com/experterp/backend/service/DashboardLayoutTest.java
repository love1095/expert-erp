package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.service.DashboardLayoutService.Layout;
import com.experterp.backend.service.DashboardLayoutService.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Manage dashboard (like Cin7): each user's layout, the organisation's default, reset, and other organisations kept apart. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DashboardLayoutTest {

    @Autowired DashboardLayoutService layouts;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;

    Tenant tenant;
    Long anna;
    Long tom;

    @BeforeEach
    void setUp() {
        tenant = tenant();
        TenantContext.set(tenant.getId());
        anna = user("anna");
        tom = user("tom");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void eachUserKeepsTheirOwnLayoutAndResetGoesBackToTheOrganisationDefault() {
        assertThat(layouts.forUser(anna).source()).isEqualTo("STANDARD");
        assertThat(layouts.forUser(anna).layout()).isNull();

        Layout mine = new Layout(List.of(new Part("reorder", true, false, true), new Part("kpis", false, false, true)));
        assertThat(layouts.save(anna, mine).source()).isEqualTo("USER");
        assertThat(layouts.forUser(anna).layout()).isEqualTo(mine);
        assertThat(layouts.forUser(tom).source()).isEqualTo("STANDARD");        // Tom is not affected

        // The admin's default applies to Tom (no own layout), not to Anna
        Layout warehouse = new Layout(List.of(new Part("reorder", true, false, false)));
        layouts.saveDefault(warehouse);
        assertThat(layouts.forUser(tom).layout()).isEqualTo(warehouse);
        assertThat(layouts.forUser(tom).source()).isEqualTo("ORGANISATION");
        assertThat(layouts.forUser(anna).layout()).isEqualTo(mine);

        // Anna resets: she gets the organisation's default
        assertThat(layouts.reset(anna).layout()).isEqualTo(warehouse);
        layouts.saveDefault(null);
        assertThat(layouts.forUser(anna).source()).isEqualTo("STANDARD");
    }

    @Test
    void aBadLayoutIsRefusedAndAnotherOrganisationSeesNothing() {
        assertThatThrownBy(() -> layouts.save(anna, new Layout(List.of()))).hasMessageContaining("no parts");
        assertThatThrownBy(() -> layouts.save(anna, new Layout(List.of(new Part("kpis", true, false, true), new Part("kpis", true, false, true)))))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> layouts.save(anna, new Layout(List.of(new Part("<script>", true, false, true)))))
                .hasMessageContaining("Unknown");

        layouts.saveDefault(new Layout(List.of(new Part("kpis", true, false, true))));
        TenantContext.set(tenant().getId());
        assertThat(layouts.forUser(anna).source()).isEqualTo("STANDARD");
    }

    private Tenant tenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Dashboard " + id);
        t.setSlug("dashboard-" + id);
        return tenantRepository.save(t);
    }

    private Long user(String name) {
        User u = new User();
        u.setUsername(name + "-" + UUID.randomUUID());
        u.setEmail(u.getUsername() + "@example.test");
        u.setPasswordHash("x");
        u.setRole(User.Role.STAFF);
        u.setTenantId(tenant.getId());
        return userRepository.save(u).getId();
    }
}
