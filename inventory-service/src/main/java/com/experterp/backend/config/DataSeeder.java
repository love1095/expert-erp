package com.experterp.backend.config;

import com.experterp.backend.entity.Plan;
import com.experterp.backend.entity.Subscription;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.repository.PlanRepository;
import com.experterp.backend.repository.SubscriptionRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Order(1)
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    // The platform owner's password (APP_SUPERADMIN_PASSWORD in .env); a random one is made and logged once when unset
    @Value("${app.super-admin.password:}")
    private String superAdminPassword;

    @Override
    public void run(ApplicationArguments args) {
        // 0. Seed plans
        seedPlans();

        // 1. Create or find default tenant
        Tenant mainTenant;
        if (!tenantRepository.existsBySlug("expert-safety")) {
            mainTenant = new Tenant();
            mainTenant.setName("Expert Safety");
            mainTenant.setSlug("expert-safety");
            mainTenant = tenantRepository.save(mainTenant);
            System.out.println("==> Created default tenant: Expert Safety (id=" + mainTenant.getId() + ")");
        } else {
            mainTenant = tenantRepository.findBySlug("expert-safety").orElseThrow();
        }
        Long tid = mainTenant.getId();

        // 1b. Assign FREE plan to default tenant if not already subscribed
        if (subscriptionRepository.findByTenantId(tid).isEmpty()) {
            Plan free = planRepository.findByTier(Plan.PlanTier.FREE).orElseThrow();
            Subscription sub = new Subscription();
            sub.setTenantId(tid);
            sub.setPlan(free);
            subscriptionRepository.save(sub);
            System.out.println("==> Assigned FREE plan to tenant id=" + tid);
        }

        // 2. Backfill existing data that has no tenant
        backfill("products",        tid);
        backfill("warehouses",      tid);
        backfill("categories",      tid);
        backfill("suppliers",       tid);
        backfill("stock_movements", tid);
        backfill("purchase_orders", tid);
        backfill("sale_orders",     tid);
        backfill("audit_log",       tid);

        // No demo stock or minimums: they would mix invented figures into real (imported) data.
        // Stock comes from purchase orders, adjustments or a Cin7 stock import.

        // 2b. The platform's super admin: no organisation, no subscription, every permission
        seedSuperAdmin();

        // 3. Create or update Expert Safety's own admin
        if (!userRepository.existsByUsername("expertsafety")) {
            User admin = new User();
            admin.setUsername("expertsafety");
            admin.setEmail("expertsafety@experterp.com");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setRole(User.Role.ADMIN);
            admin.setTenantId(tid);
            userRepository.save(admin);
            System.out.println("==> Created Expert Safety admin: expertsafety / admin123");
        } else {
            userRepository.findByUsername("expertsafety").ifPresent(u -> {
                if (u.getTenantId() == null) {
                    u.setTenantId(tid);
                    userRepository.save(u);
                    System.out.println("==> Updated admin user with tenantId=" + tid);
                }
            });
        }
    }

    private void seedSuperAdmin() {
        if (userRepository.existsByRole(User.Role.SUPER_ADMIN)) return;
        // "admin" when free (Expert Safety's own admin is "expertsafety")
        String username = userRepository.existsByUsername("admin") ? "superadmin-" + System.currentTimeMillis() % 10000 : "admin";
        boolean given = superAdminPassword != null && !superAdminPassword.isBlank();
        String password = given ? superAdminPassword : java.util.UUID.randomUUID().toString();
        User sa = new User();
        sa.setUsername(username);
        sa.setEmail("admin@experterp.com");
        sa.setPasswordHash(passwordEncoder.encode(password));
        sa.setRole(User.Role.SUPER_ADMIN);
        sa.setTenantId(null);
        userRepository.save(sa);
        System.out.println("==> Created super admin: " + username
                + (given ? " (password from APP_SUPERADMIN_PASSWORD)" : " / " + password + "  <- set APP_SUPERADMIN_PASSWORD or change it"));
    }

    private void seedPlans() {
        upsertPlan(Plan.PlanTier.FREE,       "Free",       50,   3,   2,  new BigDecimal("0.00"));
        upsertPlan(Plan.PlanTier.PRO,        "Pro",       500,  25,  10,  new BigDecimal("49.00"));
        upsertPlan(Plan.PlanTier.ENTERPRISE, "Enterprise", -1,  -1,  -1,  new BigDecimal("199.00"));
    }

    private void upsertPlan(Plan.PlanTier tier, String displayName,
                            int maxProducts, int maxUsers, int maxWarehouses,
                            BigDecimal price) {
        planRepository.findByTier(tier).ifPresentOrElse(
            p -> { /* already exists */ },
            () -> {
                Plan p = new Plan();
                p.setTier(tier);
                p.setDisplayName(displayName);
                p.setMaxProducts(maxProducts);
                p.setMaxUsers(maxUsers);
                p.setMaxWarehouses(maxWarehouses);
                p.setMonthlyPrice(price);
                planRepository.save(p);
                System.out.println("==> Seeded plan: " + displayName);
            }
        );
    }

    private void backfill(String table, Long tenantId) {
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE " + table + " SET tenant_id = ? WHERE tenant_id IS NULL", tenantId);
            if (rows > 0) System.out.println("==> Backfilled " + rows + " rows in " + table);
        } catch (Exception e) {
            System.out.println("==> Skipped backfill for " + table + ": " + e.getMessage());
        }
    }
}
