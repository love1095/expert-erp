package com.experterp.backend.service;

import com.experterp.backend.entity.Plan;
import com.experterp.backend.entity.Subscription;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.PlanRepository;
import com.experterp.backend.repository.SubscriptionRepository;
import com.experterp.backend.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The platform owner's view (SUPER_ADMIN): every organisation, its subscription and how much it uses. The super
 * admin belongs to no organisation and has no subscription; it is the only one who changes an organisation's plan
 * (there is no online payment yet, so an organisation's own admin cannot give itself a bigger plan).
 */
@Service
@RequiredArgsConstructor
public class PlatformService {

    private final TenantRepository tenantRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public record Organisation(Long id, String name, String slug, boolean active, LocalDateTime createdAt,
                               String planTier, String planName, BigDecimal monthlyPrice, String status,
                               LocalDateTime endDate, long users, long products, long warehouses) {}

    /** All organisations, newest first, read across organisations with plain SQL (not tenant-filtered). */
    @Transactional(readOnly = true)
    public List<Organisation> organisations() {
        return organisations(null);
    }

    // One organisation (tenantId) or all; counts by grouping once, not three sub-queries per organisation
    private List<Organisation> organisations(Long tenantId) {
        return jdbc.query("""
                SELECT t.id, t.name, t.slug, t.active, t.created_at,
                       p.tier, p.display_name, p.monthly_price, s.status, s.end_date,
                       COALESCE(u.n, 0) AS users, COALESCE(x.n, 0) AS products, COALESCE(w.n, 0) AS warehouses
                FROM tenants t
                LEFT JOIN subscriptions s ON s.tenant_id = t.id
                LEFT JOIN plans p ON p.id = s.plan_id
                LEFT JOIN (SELECT tenant_id, count(*) AS n FROM users WHERE ?::bigint IS NULL OR tenant_id = ? GROUP BY tenant_id) u ON u.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, count(*) AS n FROM products WHERE ?::bigint IS NULL OR tenant_id = ? GROUP BY tenant_id) x ON x.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, count(*) AS n FROM warehouses WHERE ?::bigint IS NULL OR tenant_id = ? GROUP BY tenant_id) w ON w.tenant_id = t.id
                WHERE ?::bigint IS NULL OR t.id = ?
                ORDER BY t.created_at DESC, t.id DESC""", (rs, i) -> new Organisation(
                rs.getLong("id"), rs.getString("name"), rs.getString("slug"), rs.getBoolean("active"),
                toLocal(rs.getTimestamp("created_at")),
                Optional.ofNullable(rs.getString("tier")).orElse("FREE"),
                Optional.ofNullable(rs.getString("display_name")).orElse("Free"),
                Optional.ofNullable(rs.getBigDecimal("monthly_price")).orElse(BigDecimal.ZERO),
                Optional.ofNullable(rs.getString("status")).orElse("ACTIVE"),
                toLocal(rs.getTimestamp("end_date")),
                rs.getLong("users"), rs.getLong("products"), rs.getLong("warehouses")),
                tenantId, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    /** Gives an organisation a plan (and status / end date); written to that organisation's audit log. */
    @Transactional
    public Organisation setSubscription(Long tenantId, String planTier, String status, LocalDateTime endDate) {
        Tenant tenant = tenant(tenantId);
        Plan.PlanTier tier;
        Subscription.SubscriptionStatus st;
        try {
            tier = Plan.PlanTier.valueOf(Objects.requireNonNull(planTier, "planTier").trim().toUpperCase(Locale.ROOT));
            st = status == null || status.isBlank() ? Subscription.SubscriptionStatus.ACTIVE
                    : Subscription.SubscriptionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Plan is FREE, PRO or ENTERPRISE; status is ACTIVE, CANCELLED or EXPIRED");
        }
        Plan plan = planRepository.findByTier(tier).orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + tier));
        Subscription sub = subscriptionRepository.findByTenantId(tenantId).orElseGet(() -> {
            Subscription s = new Subscription();
            s.setTenantId(tenantId);
            return s;
        });
        Map<String, Object> changes = new LinkedHashMap<>();
        if (sub.getPlan() == null || sub.getPlan().getTier() != tier) {
            changes.put("plan", Map.of("from", sub.getPlan() == null ? "none" : sub.getPlan().getTier().name(), "to", tier.name()));
        }
        if (sub.getStatus() != st) changes.put("status", Map.of("from", String.valueOf(sub.getStatus()), "to", st.name()));
        if (!Objects.equals(sub.getEndDate(), endDate)) changes.put("endDate", Map.of("from", String.valueOf(sub.getEndDate()), "to", String.valueOf(endDate)));
        sub.setPlan(plan);
        sub.setStatus(st);
        sub.setEndDate(endDate);
        subscriptionRepository.saveAndFlush(sub);
        if (!changes.isEmpty()) inOrganisation(tenant.getId(), () -> auditService.log("Subscription", sub.getId(), "UPDATE", changes));
        // Read back with plain SQL: the change is flushed above
        return organisation(tenantId);
    }

    /** Switches an organisation on or off; while off, its people cannot log in and its API keys stop. */
    @Transactional
    public Organisation setActive(Long tenantId, boolean active) {
        Tenant tenant = tenant(tenantId);
        if (tenant.isActive() != active) {
            tenant.setActive(active);
            tenantRepository.saveAndFlush(tenant);
            inOrganisation(tenantId, () -> auditService.log("Tenant", tenantId, active ? "ACTIVATE" : "DEACTIVATE", null));
        }
        // Read back with plain SQL: the change is flushed above
        return organisation(tenantId);
    }

    private Organisation organisation(Long tenantId) {
        return organisations(tenantId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Organisation not found: " + tenantId));
    }

    private Tenant tenant(Long id) {
        return tenantRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organisation not found: " + id));
    }

    // The audit entry belongs to the organisation that was changed, so its own admins see who changed their plan.
    // A session keeps the organisation it was opened with, so the entry is written in a new one (like the Shopify sync)
    private void inOrganisation(Long tenantId, Runnable work) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            TransactionTemplate txNew = new TransactionTemplate(transactionTemplate.getTransactionManager());
            txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txNew.executeWithoutResult(s -> work.run());
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }

    private static LocalDateTime toLocal(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
