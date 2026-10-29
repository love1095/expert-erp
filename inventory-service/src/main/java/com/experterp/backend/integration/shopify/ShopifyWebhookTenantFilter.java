package com.experterp.backend.integration.shopify;

import com.experterp.backend.multitenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Webhooks have no login token, so the tenant comes from the X-Shopify-Shop-Domain header. It must be set here,
 * before the request's database session opens (open-in-view), like JwtAuthFilter does for logged-in requests;
 * a session opened without a tenant would not see the tenant's data. The signature is checked afterwards by
 * {@link ShopifyWebhookService} with that tenant's secret, so a wrong header only leads to a rejected request.
 */
@Component
@RequiredArgsConstructor
public class ShopifyWebhookTenantFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ShopifyOrderSyncService.WEBHOOK_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String shop = request.getHeader("X-Shopify-Shop-Domain");
        if (shop != null) {
            // Plain SQL: not tenant-filtered, and no JPA session is opened yet
            List<Long> tenant = jdbc.queryForList("SELECT tenant_id FROM shopify_connections WHERE shop_domain = ?",
                    Long.class, shop.trim().toLowerCase(Locale.ROOT));
            if (!tenant.isEmpty()) TenantContext.set(tenant.get(0));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
