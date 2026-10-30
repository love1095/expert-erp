package com.experterp.backend.repository;

import com.experterp.backend.entity.ShopifyProductLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopifyProductLinkRepository extends JpaRepository<ShopifyProductLink, Long> {

    Optional<ShopifyProductLink> findByProductId(Long productId);

    List<ShopifyProductLink> findByShopifyProductId(String shopifyProductId);
}
