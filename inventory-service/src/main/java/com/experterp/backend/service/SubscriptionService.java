package com.experterp.backend.service;

import com.experterp.backend.dto.SubscriptionResponse;
import com.experterp.backend.entity.Plan;
import com.experterp.backend.entity.Subscription;
import com.experterp.backend.exception.PlanLimitExceededException;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.PlanRepository;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.SubscriptionRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;

    public Plan getCurrentPlan(Long tenantId) {
        return subscriptionRepository.findByTenantId(tenantId)
                .map(Subscription::getPlan)
                .orElseGet(() -> planRepository.findByTier(Plan.PlanTier.FREE)
                        .orElseThrow(() -> new ResourceNotFoundException("FREE plan not seeded")));
    }

    public SubscriptionResponse getSubscriptionInfo(Long tenantId) {
        Subscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    Plan free = planRepository.findByTier(Plan.PlanTier.FREE)
                            .orElseThrow(() -> new ResourceNotFoundException("FREE plan not seeded"));
                    Subscription s = new Subscription();
                    s.setTenantId(tenantId);
                    s.setPlan(free);
                    return subscriptionRepository.save(s);
                });

        Plan plan = sub.getPlan();
        long products   = productRepository.count();       // discriminator filters by current tenant
        long warehouses = warehouseRepository.count();
        long users      = userRepository.countByTenantId(tenantId);

        SubscriptionResponse r = new SubscriptionResponse();
        r.setSubscriptionId(sub.getId());
        r.setPlanTier(plan.getTier().name());
        r.setPlanDisplayName(plan.getDisplayName());
        r.setMaxProducts(plan.getMaxProducts());
        r.setMaxUsers(plan.getMaxUsers());
        r.setMaxWarehouses(plan.getMaxWarehouses());
        r.setMonthlyPrice(plan.getMonthlyPrice());
        r.setStatus(sub.getStatus().name());
        r.setStartDate(sub.getStartDate());
        r.setEndDate(sub.getEndDate());
        r.setProductCount(products);
        r.setUserCount(users);
        r.setWarehouseCount(warehouses);
        return r;
    }

    public List<Plan> getAllPlans() {
        return planRepository.findAll();
    }

    @Transactional
    public SubscriptionResponse upgradePlan(Long tenantId, String tierName) {
        Plan.PlanTier tier = Plan.PlanTier.valueOf(tierName.toUpperCase());
        Plan plan = planRepository.findByTier(tier)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + tierName));

        Subscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> { Subscription s = new Subscription(); s.setTenantId(tenantId); return s; });
        sub.setPlan(plan);
        sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(sub);

        return getSubscriptionInfo(tenantId);
    }

    /** Called when a new organisation registers — assigns FREE plan automatically. */
    @Transactional
    public void assignFreePlan(Long tenantId) {
        if (subscriptionRepository.findByTenantId(tenantId).isPresent()) return;
        Plan free = planRepository.findByTier(Plan.PlanTier.FREE)
                .orElseThrow(() -> new ResourceNotFoundException("FREE plan not seeded"));
        Subscription s = new Subscription();
        s.setTenantId(tenantId);
        s.setPlan(free);
        subscriptionRepository.save(s);
    }

    public void checkProductLimit(Long tenantId) {
        Plan plan = getCurrentPlan(tenantId);
        if (plan.getMaxProducts() < 0) return;
        if (productRepository.count() >= plan.getMaxProducts()) {
            throw new PlanLimitExceededException("products", plan.getMaxProducts(), plan.getTier().name());
        }
    }

    public void checkWarehouseLimit(Long tenantId) {
        Plan plan = getCurrentPlan(tenantId);
        if (plan.getMaxWarehouses() < 0) return;
        if (warehouseRepository.count() >= plan.getMaxWarehouses()) {
            throw new PlanLimitExceededException("warehouses", plan.getMaxWarehouses(), plan.getTier().name());
        }
    }

    public void checkUserLimit(Long tenantId) {
        Plan plan = getCurrentPlan(tenantId);
        if (plan.getMaxUsers() < 0) return;
        if (userRepository.countByTenantId(tenantId) >= plan.getMaxUsers()) {
            throw new PlanLimitExceededException("users", plan.getMaxUsers(), plan.getTier().name());
        }
    }
}
