package com.experterp.backend.repository;

import com.experterp.backend.entity.ShopifyConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShopifyConnectionRepository extends JpaRepository<ShopifyConnection, Long> {

    // Tenant-filtered: the current tenant's connection (at most one)
    Optional<ShopifyConnection> findFirstByOrderByIdAsc();

    // Native, so NOT tenant-filtered: which tenant owns this shop (another tenant must not reuse it)
    @Query(value = "SELECT tenant_id FROM shopify_connections WHERE shop_domain = :shop", nativeQuery = true)
    Optional<Long> findTenantIdByShopDomain(@Param("shop") String shopDomain);
}
