package com.experterp.backend.event;

/**
 * A shipment was recorded for a sale order that came from an external system (e.g. Shopify).
 * Listeners act on it after the transaction commits, so the shipment exists when they run.
 */
public record FulfilmentCreatedEvent(Long tenantId, Long saleOrderId, Long fulfilmentId) {}
