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

        // 3. Seed min_stock for products that have none
        seedMinStock(tid);

        // 4. Seed initial stock movements if none exist
        seedInitialStock(tid);

        // 5. Create or update admin user
        if (!userRepository.existsByUsername("admin")) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setEmail("admin@experterp.com");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setRole(User.Role.ADMIN);
            admin.setTenantId(tid);
            userRepository.save(admin);
            System.out.println("==> Created admin user: admin / admin123");
        } else {
            userRepository.findByUsername("admin").ifPresent(u -> {
                if (u.getTenantId() == null) {
                    u.setTenantId(tid);
                    userRepository.save(u);
                    System.out.println("==> Updated admin user with tenantId=" + tid);
                }
            });
        }
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

    private void seedMinStock(Long tenantId) {
        int rows = jdbcTemplate.update("""
            UPDATE products SET min_stock =
                CASE
                    WHEN LOWER(unit) IN ('pcs','pc','piece','pieces','ea','each','unit','units','cái','chiếc')
                        THEN FLOOR(RANDOM() * 46 + 5)
                    WHEN LOWER(unit) IN ('kg','kilogram','kilograms','kilo')
                        THEN FLOOR(RANDOM() * 91 + 10)
                    WHEN LOWER(unit) IN ('g','gram','grams','gam')
                        THEN FLOOR(RANDOM() * 901 + 100)
                    WHEN LOWER(unit) IN ('box','boxes','carton','ctn','hộp','thùng')
                        THEN FLOOR(RANDOM() * 19 + 2)
                    WHEN LOWER(unit) IN ('l','liter','liters','litre','litres','lit')
                        THEN FLOOR(RANDOM() * 41 + 10)
                    WHEN LOWER(unit) IN ('m','meter','meters','metre','metres')
                        THEN FLOOR(RANDOM() * 81 + 20)
                    WHEN LOWER(unit) IN ('roll','rolls','cuộn')
                        THEN FLOOR(RANDOM() * 26 + 5)
                    WHEN LOWER(unit) IN ('pair','pairs','đôi')
                        THEN FLOOR(RANDOM() * 16 + 5)
                    WHEN LOWER(unit) IN ('set','sets','bộ')
                        THEN FLOOR(RANDOM() * 13 + 3)
                    ELSE FLOOR(RANDOM() * 46 + 5)
                END
            WHERE tenant_id = ? AND min_stock IS NULL
            """, tenantId);
        if (rows > 0) System.out.println("==> Seeded min_stock for " + rows + " products");
    }

    private void seedInitialStock(Long tenantId) {
        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE tenant_id = ?", Long.class, tenantId);
        if (existing != null && existing > 0) return;

        Long warehouseId = jdbcTemplate.queryForObject(
                "SELECT id FROM warehouses WHERE tenant_id = ? ORDER BY id LIMIT 1", Long.class, tenantId);
        if (warehouseId == null) {
            System.out.println("==> No warehouse found, skipping initial stock seed");
            return;
        }

        int rows = jdbcTemplate.update("""
            INSERT INTO stock_movements (created_at, notes, quantity, reference, type, product_id, warehouse_id, reference_id, reference_type, tenant_id)
            SELECT
                NOW(),
                'Opening stock',
                CASE
                    WHEN LOWER(p.unit) IN ('pcs','pc','piece','pieces','ea','each','unit','units','cái','chiếc')
                        THEN GREATEST(COALESCE(p.min_stock,10), FLOOR(RANDOM() * 200 + 50))
                    WHEN LOWER(p.unit) IN ('kg','kilogram','kilograms','kilo')
                        THEN GREATEST(COALESCE(p.min_stock,20), FLOOR(RANDOM() * 500 + 100))
                    WHEN LOWER(p.unit) IN ('g','gram','grams','gam')
                        THEN GREATEST(COALESCE(p.min_stock,500), FLOOR(RANDOM() * 5000 + 1000))
                    WHEN LOWER(p.unit) IN ('box','boxes','carton','ctn','hộp','thùng')
                        THEN GREATEST(COALESCE(p.min_stock,5), FLOOR(RANDOM() * 80 + 20))
                    WHEN LOWER(p.unit) IN ('l','liter','liters','litre','litres','lit')
                        THEN GREATEST(COALESCE(p.min_stock,15), FLOOR(RANDOM() * 200 + 50))
                    WHEN LOWER(p.unit) IN ('m','meter','meters','metre','metres')
                        THEN GREATEST(COALESCE(p.min_stock,25), FLOOR(RANDOM() * 400 + 100))
                    WHEN LOWER(p.unit) IN ('roll','rolls','cuộn')
                        THEN GREATEST(COALESCE(p.min_stock,8), FLOOR(RANDOM() * 100 + 30))
                    WHEN LOWER(p.unit) IN ('pair','pairs','đôi')
                        THEN GREATEST(COALESCE(p.min_stock,8), FLOOR(RANDOM() * 80 + 20))
                    WHEN LOWER(p.unit) IN ('set','sets','bộ')
                        THEN GREATEST(COALESCE(p.min_stock,5), FLOOR(RANDOM() * 50 + 10))
                    ELSE GREATEST(COALESCE(p.min_stock,10), FLOOR(RANDOM() * 200 + 50))
                END,
                'OPENING',
                'PURCHASE_IN',
                p.id,
                ?,
                'SEED',
                'MANUAL',
                ?
            FROM products p
            WHERE p.tenant_id = ?
            """, warehouseId, tenantId, tenantId);
        System.out.println("==> Seeded initial stock movements for " + rows + " products in warehouse " + warehouseId);
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
