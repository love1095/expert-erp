package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.AuditLog.ActorType;
import com.experterp.backend.entity.Fulfilment;
import com.experterp.backend.entity.Fulfilment.SyncStatus;
import com.experterp.backend.entity.FulfilmentLine;
import com.experterp.backend.event.FulfilmentCreatedEvent;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.FulfilmentRepository;
import com.experterp.backend.service.AuditActorContext;
import com.experterp.backend.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase 7.3: a shipment recorded in the ERP for a Shopify order becomes a Shopify fulfillment, so Shopify marks the
 * items shipped and emails the customer (with tracking when given).
 * <p>
 * Runs after the ERP transaction has committed: the shipment and stock movement stand even if Shopify is down.
 * The outcome is stored on the shipment (SYNCED / FAILED + message); a FAILED one can be retried.
 * Shopify fulfils by fulfillment order lines, so each shipped ERP line is matched by its Shopify line item id to
 * the open fulfillment-order lines that still have quantity remaining.
 */
@Slf4j
@Service
public class ShopifyFulfilmentSync {

    static final String FULFILLMENT_ORDERS_QUERY = """
            query($id: ID!) {
              order(id: $id) {
                fulfillmentOrders(first: 20) {
                  nodes {
                    id status
                    lineItems(first: 100) { nodes { id remainingQuantity lineItem { id } } }
                  }
                }
              }
            }""";

    static final String FULFILLMENT_CREATE = """
            mutation($fulfillment: FulfillmentInput!) {
              fulfillmentCreate(fulfillment: $fulfillment) {
                fulfillment { id status }
                userErrors { field message }
              }
            }""";

    // Fulfillment orders that can still be fulfilled
    private static final Set<String> FULFILLABLE = Set.of("OPEN", "IN_PROGRESS");

    private final FulfilmentRepository fulfilmentRepository;
    private final ShopifyConnectionService connectionService;
    private final ShopifyClient client;
    private final AuditService auditService;
    // Each step in its own transaction: after-commit listeners must not join the finished one
    private final TransactionTemplate txNew;

    public ShopifyFulfilmentSync(FulfilmentRepository fulfilmentRepository, ShopifyConnectionService connectionService,
                                 ShopifyClient client, AuditService auditService, PlatformTransactionManager tm) {
        this.fulfilmentRepository = fulfilmentRepository;
        this.connectionService = connectionService;
        this.client = client;
        this.auditService = auditService;
        this.txNew = new TransactionTemplate(tm);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFulfilment(FulfilmentCreatedEvent event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            push(event.fulfilmentId());
        } catch (RuntimeException e) {
            // Never break the request that recorded the shipment; push() stores its own failures
            log.warn("Sending shipment {} to Shopify failed: {}", event.fulfilmentId(), e.getMessage());
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }

    /** Sends one shipment to Shopify (also used for retries) and stores the outcome. Current tenant. */
    public SyncStatus push(Long fulfilmentId) {
        SyncStatus current = txNew.execute(s -> fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found: " + fulfilmentId))
                .getExternalSyncStatus());
        if (current == null) throw new IllegalArgumentException("Shipment " + fulfilmentId + " is not for a Shopify order");
        if (current == SyncStatus.SYNCED) return SyncStatus.SYNCED;

        return AuditActorContext.runAs(ActorType.INTEGRATION, "shopify", () -> {
            try {
                return send(txNew.execute(s -> load(fulfilmentId)));
            } catch (RuntimeException e) {
                record(fulfilmentId, SyncStatus.FAILED, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return SyncStatus.FAILED;
            }
        });
    }

    private SyncStatus send(Shipment shipment) {
        String token = txNew.execute(s -> connectionService.accessToken());
        String shop = txNew.execute(s -> connectionService.get().orElseThrow(
                () -> new IllegalStateException("No Shopify store is connected")).shopDomain());

        JsonNode fulfillmentOrders = client.graphql(shop, token, FULFILLMENT_ORDERS_QUERY,
                Map.of("id", "gid://shopify/Order/" + shipment.orderExternalId()))
                .path("order").path("fulfillmentOrders").path("nodes");

        // Open fulfillment-order lines with quantity left, per Shopify line item id
        Map<String, List<OpenLine>> openByLineItem = new HashMap<>();
        for (JsonNode fo : fulfillmentOrders) {
            if (!FULFILLABLE.contains(fo.path("status").asText())) continue;
            for (JsonNode li : fo.path("lineItems").path("nodes")) {
                long remaining = li.path("remainingQuantity").asLong(0);
                String lineItemId = ShopifyOrder.numericId(li.path("lineItem").path("id").asText(null));
                if (remaining > 0 && lineItemId != null) {
                    openByLineItem.computeIfAbsent(lineItemId, k -> new ArrayList<>())
                            .add(new OpenLine(fo.path("id").asText(), li.path("id").asText(), remaining));
                }
            }
        }

        // Allocate each shipped quantity over those lines: fulfillment order id -> (its line id -> quantity)
        Map<String, Map<String, Long>> byFo = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (ShippedLine line : shipment.lines()) {
            if (line.externalLineId() == null) {
                notes.add(line.label() + " is not a Shopify line");
                continue;
            }
            long wanted = line.quantity();
            for (OpenLine open : openByLineItem.getOrDefault(line.externalLineId(), List.of())) {
                long take = Math.min(wanted, open.remaining);
                if (take == 0) continue;
                open.remaining -= take;
                wanted -= take;
                byFo.computeIfAbsent(open.fulfillmentOrderId, k -> new LinkedHashMap<>()).merge(open.lineId, take, Long::sum);
            }
            if (wanted > 0) notes.add(line.label() + ": " + wanted + " already fulfilled in Shopify");
        }

        if (byFo.isEmpty()) {
            // Nothing left to fulfil in Shopify (e.g. fulfilled there by hand): nothing to send
            record(shipment.fulfilmentId(), SyncStatus.SYNCED, null, notes.isEmpty() ? "Nothing to send" : String.join("; ", notes));
            return SyncStatus.SYNCED;
        }

        List<Map<String, Object>> lineItemsByFo = new ArrayList<>();
        byFo.forEach((foId, lines) -> lineItemsByFo.add(Map.of(
                "fulfillmentOrderId", foId,
                "fulfillmentOrderLineItems", lines.entrySet().stream()
                        .map(e -> Map.<String, Object>of("id", e.getKey(), "quantity", e.getValue())).toList())));
        Map<String, Object> fulfillment = new LinkedHashMap<>();
        fulfillment.put("lineItemsByFulfillmentOrder", lineItemsByFo);
        // Shopify emails the customer its shipping confirmation
        fulfillment.put("notifyCustomer", true);
        if (shipment.trackingNumber() != null || shipment.trackingCompany() != null) {
            Map<String, Object> tracking = new LinkedHashMap<>();
            if (shipment.trackingNumber() != null) tracking.put("number", shipment.trackingNumber());
            if (shipment.trackingCompany() != null) tracking.put("company", shipment.trackingCompany());
            fulfillment.put("trackingInfo", tracking);
        }

        JsonNode result = client.graphql(shop, token, FULFILLMENT_CREATE, Map.of("fulfillment", fulfillment))
                .path("fulfillmentCreate");
        JsonNode errors = result.path("userErrors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(e -> messages.add(e.path("message").asText()));
            throw new ShopifyApiException("Shopify refused the fulfillment: " + String.join("; ", messages));
        }
        String fulfillmentId = result.path("fulfillment").path("id").asText(null);
        record(shipment.fulfilmentId(), SyncStatus.SYNCED, fulfillmentId, notes.isEmpty() ? null : String.join("; ", notes));
        return SyncStatus.SYNCED;
    }

    private void record(Long fulfilmentId, SyncStatus status, String externalId, String message) {
        txNew.executeWithoutResult(s -> fulfilmentRepository.findById(fulfilmentId).ifPresent(f -> {
            f.setExternalSyncStatus(status);
            if (externalId != null) f.setExternalId(externalId);
            f.setExternalSyncMessage(message);
            if (status == SyncStatus.SYNCED) f.setExternalSyncedAt(LocalDateTime.now());
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("fulfilmentId", f.getId());
            changes.put("status", status);
            if (message != null) changes.put("message", message);
            auditService.log("SALE_ORDER", f.getSaleOrder().getId(), "SHOPIFY_FULFILLMENT", changes);
        }));
    }

    private static final class OpenLine {
        final String fulfillmentOrderId;
        final String lineId;
        long remaining;

        OpenLine(String fulfillmentOrderId, String lineId, long remaining) {
            this.fulfillmentOrderId = fulfillmentOrderId;
            this.lineId = lineId;
            this.remaining = remaining;
        }
    }

    // ---- snapshot of the shipment, read in its own transaction ----

    private record ShippedLine(String externalLineId, long quantity, String label) {}

    private record Shipment(Long fulfilmentId, String orderExternalId, String trackingCompany, String trackingNumber,
                            List<ShippedLine> lines) {}

    private Shipment load(Long fulfilmentId) {
        Fulfilment f = fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found: " + fulfilmentId));
        if (f.getSaleOrder().getExternalId() == null) {
            throw new IllegalStateException("The sale order has no Shopify order id");
        }
        List<ShippedLine> lines = new ArrayList<>();
        for (FulfilmentLine fl : f.getLines()) {
            BigDecimal qty = fl.getQuantity();
            String label = "'" + fl.getSaleOrderLine().getProduct().getSku() + "'";
            if (qty.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException(label + " was shipped in part units (" + qty.toPlainString()
                        + "); Shopify only takes whole units");
            }
            lines.add(new ShippedLine(fl.getSaleOrderLine().getExternalLineId(), qty.longValueExact(), label));
        }
        return new Shipment(f.getId(), f.getSaleOrder().getExternalId(), f.getTrackingCompany(), f.getTrackingNumber(), lines);
    }
}
