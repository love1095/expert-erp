package com.experterp.backend.security;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.ProductResponse;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.service.AccessRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.experterp.backend.security.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase I1: roles with ticked permissions like Cin7's Users &amp; Roles; cost hidden from those without the tick. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PermissionTest {

    @Autowired AccessRoleService roles;
    @Autowired UserDetailsServiceImpl userDetailsService;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ObjectMapper objectMapper;

    Long tenantId;
    String id;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        id = UUID.randomUUID().toString().substring(0, 8);
        t.setName("Roles " + id);
        t.setSlug("roles-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        signIn(user("admin-" + id, User.Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void rulesNameThePermissionEachRequestNeeds() {
        assertThat(PermissionFilter.required("GET", "/api/sale-orders/12")).containsExactly(SALE_VIEW);
        assertThat(PermissionFilter.required("POST", "/api/sale-orders/12/confirm")).containsExactly(SALE_EDIT);
        assertThat(PermissionFilter.required("POST", "/api/sale-orders/12/cancel")).containsExactly(SALE_VOID);
        assertThat(PermissionFilter.required("GET", "/api/customers/5/credit")).contains(CUSTOMERS);
        assertThat(PermissionFilter.required("PUT", "/api/customers/5/credit/hold")).containsExactly(CUSTOMER_CREDIT);
        assertThat(PermissionFilter.required("GET", "/api/invoices/3")).containsExactlyInAnyOrder(SALE_VIEW, PURCHASE_VIEW);
        assertThat(PermissionFilter.required("POST", "/api/purchase-orders/3/receive")).containsExactly(PURCHASE_EDIT);
        assertThat(PermissionFilter.required("POST", "/api/stock/import")).containsExactly(DATA_IMPORT);
        assertThat(PermissionFilter.required("GET", "/api/reports/vat-return")).containsExactly(REPORTS_FINANCIAL);
        assertThat(PermissionFilter.required("POST", "/api/accounting/export")).containsExactly(REPORTS_FINANCIAL);
        assertThat(PermissionFilter.required("GET", "/api/settings/organisation")).isNull();    // admin checks of its own
    }

    @Test
    void staffSeeNoCostAndAManagerDoesUnlessTheirRoleSaysOtherwise() throws Exception {
        assertThat(Permission.defaultsFor(User.Role.STAFF)).contains(SALE_EDIT, PURCHASE_PRICES)
                .doesNotContain(PRODUCT_COST, STOCK_VALUE, SALE_PROFIT, REPORTS_FINANCIAL);
        // Managers have every tick but Adviser (the accountant's, K5, as Xero's Adviser role) and Settings (Q5: admins' until given)
        assertThat(Permission.defaultsFor(User.Role.MANAGER)).containsAll(java.util.Arrays.stream(Permission.values())
                .filter(p -> p != Permission.ADVISER && p != Permission.SETTINGS).toList()).doesNotContain(Permission.ADVISER, Permission.SETTINGS);

        ProductResponse product = new ProductResponse();
        product.setCostPrice(new BigDecimal("6.00"));
        SaleOrderResponse.Line line = new SaleOrderResponse.Line();
        line.setMarginPercent(new BigDecimal("40.00"));

        User staff = user("staff-" + id, User.Role.STAFF);
        signIn(staff);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(product)).get("costPrice").isNull()).isTrue();
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(line)).get("marginPercent").isNull()).isTrue();

        User manager = user("manager-" + id, User.Role.MANAGER);
        signIn(manager);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(product)).get("costPrice").decimalValue()).isEqualByComparingTo("6.00");

        // An admin makes a role "Sales lead": sales with margins, no product cost; the manager is given it
        signIn(user("admin2-" + id, User.Role.ADMIN));
        assertThat(roles.roles()).extracting(AccessRoleService.Role::name).containsExactly("Accountant", "Manager", "Staff");
        AccessRoleService.Role lead = roles.create(new AccessRoleService.RoleRequest("Sales lead",
                List.of("SALE_VIEW", "SALE_EDIT", "SALE_PROFIT", "CUSTOMERS", "PRODUCT_VIEW")));
        roles.assign(manager.getId(), lead.id());
        assertThatThrownBy(() -> roles.create(new AccessRoleService.RoleRequest("sales lead ", List.of())))
                .hasMessageContaining("There is a role called sales lead already");
        assertThatThrownBy(() -> roles.create(new AccessRoleService.RoleRequest("X", List.of("FLY")))).hasMessageContaining("Unknown permission");
        assertThatThrownBy(() -> roles.delete(lead.id())).hasMessageContaining("given to 1 user");

        UserDetailsImpl reloaded = (UserDetailsImpl) userDetailsService.loadUserByUsername(manager.getUsername());
        assertThat(reloaded.getPermissions()).containsExactlyInAnyOrder(SALE_VIEW, SALE_EDIT, SALE_PROFIT, CUSTOMERS, PRODUCT_VIEW);
        signIn(manager);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(product)).get("costPrice").isNull()).isTrue();
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(line)).get("marginPercent").decimalValue()).isEqualByComparingTo("40");
        assertThat(Permission.current(SALE_IGNORE_CREDIT_LIMIT)).isFalse();

        // An admin keeps everything whatever role is set
        User admin = user("admin3-" + id, User.Role.ADMIN);
        admin.setAccessRoleId(lead.id());
        userRepository.save(admin);
        assertThat(((UserDetailsImpl) userDetailsService.loadUserByUsername(admin.getUsername())).getPermissions())
                .containsAll(List.of(Permission.values()));
    }

    @Test
    void severalRolesAddUpAndSettingsCanBeGivenToARole() {
        signIn(user("admin4-" + id, User.Role.ADMIN));
        AccessRoleService.Role office = roles.create(new AccessRoleService.RoleRequest("Office", List.of("SETTINGS", "CUSTOMERS")));
        AccessRoleService.Role warehouse = roles.create(new AccessRoleService.RoleRequest("Warehouse", List.of("PRODUCT_VIEW", "STOCK_ADJUST")));
        User clerk = user("clerk-" + id, User.Role.STAFF);
        roles.assignAll(clerk.getId(), List.of(office.id(), warehouse.id()));
        assertThat(roles.rolesOf(clerk.getId())).containsExactlyInAnyOrder(office.id(), warehouse.id());
        assertThat(roles.roles()).filteredOn(r -> r.name().equals("Office")).singleElement().satisfies(r -> assertThat(r.users()).isEqualTo(1));
        UserDetailsImpl d = (UserDetailsImpl) userDetailsService.loadUserByUsername(clerk.getUsername());
        assertThat(d.getPermissions()).containsExactlyInAnyOrder(Permission.SETTINGS, CUSTOMERS, PRODUCT_VIEW, Permission.STOCK_ADJUST);
        assertThat(d.getAuthorities()).extracting(Object::toString).contains("PERM_SETTINGS");
        // Back to one role, then none (the old role's defaults)
        roles.assignAll(clerk.getId(), List.of(warehouse.id()));
        assertThat(((UserDetailsImpl) userDetailsService.loadUserByUsername(clerk.getUsername())).getPermissions()).doesNotContain(Permission.SETTINGS);
        roles.assignAll(clerk.getId(), List.of());
        assertThat(((UserDetailsImpl) userDetailsService.loadUserByUsername(clerk.getUsername())).getPermissions())
                .containsExactlyInAnyOrderElementsOf(Permission.defaultsFor(User.Role.STAFF));
    }

    private User user(String name, User.Role role) {
        User u = new User();
        u.setUsername(name);
        u.setPasswordHash("x");
        u.setRole(role);
        u.setTenantId(tenantId);
        return userRepository.save(u);
    }

    private void signIn(User u) {
        UserDetailsImpl d = (UserDetailsImpl) userDetailsService.loadUserByUsername(u.getUsername());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(d, null, d.getAuthorities()));
    }
}
