package com.experterp.backend.repository;

import com.experterp.backend.entity.ShopifyWebhookEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShopifyWebhookEventRepository extends JpaRepository<ShopifyWebhookEvent, Long> {

    Optional<ShopifyWebhookEvent> findByEventId(String eventId);

    Page<ShopifyWebhookEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
