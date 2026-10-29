package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.AuditLog.ActorType;
import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.entity.ShopifyWebhookEvent;
import com.experterp.backend.entity.ShopifyWebhookEvent.Status;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.ShopifyWebhookEventRepository;
import com.experterp.backend.security.SecretCipher;
import com.experterp.backend.service.AuditActorContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * Receives Shopify webhooks. No user is logged in: the shop domain header says which tenant the event is for,
 * and the HMAC signature (made with that tenant's client secret) proves it came from Shopify.
 * Each event is stored first, then processed. A repeated delivery of an already processed event is skipped;
 * a failed one is processed again, and failures answer 500 so Shopify retries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopifyWebhookService {

    static final Set<String> ORDER_TOPICS = Set.of("orders/create", "orders/updated", "orders/paid", "orders/cancelled");

    public enum Reply { OK, UNAUTHORIZED, BAD_REQUEST, FAILED }

    private final ShopifyConnectionRepository connectionRepository;
    private final ShopifyWebhookEventRepository eventRepository;
    private final ShopifyOrderImporter importer;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    /** The tenant was set from the shop domain by {@link ShopifyWebhookTenantFilter}. */
    public Reply receive(byte[] body, String shopDomain, String topic, String hmac, String eventId, String webhookId) {
        if (shopDomain == null || topic == null || hmac == null || body == null) return Reply.BAD_REQUEST;
        String shop = shopDomain.trim().toLowerCase();
        if (TenantContext.get() == null) {
            log.warn("Shopify webhook {} for unknown shop {}", topic, shop);
            return Reply.UNAUTHORIZED;
        }
        ShopifyConnection conn = connectionRepository.findFirstByOrderByIdAsc().orElse(null);
        if (conn == null || !shop.equals(conn.getShopDomain())
                || !validSignature(body, hmac, cipher.decrypt(conn.getClientSecretEncrypted()))) {
            log.warn("Shopify webhook {} for {} has an invalid signature", topic, shop);
            return Reply.UNAUTHORIZED;
        }
        String id = eventId != null ? eventId : webhookId;
        if (id == null) return Reply.BAD_REQUEST;

        Long eventPk = store(id, topic, shop, new String(body, StandardCharsets.UTF_8));
        if (eventPk == null) return Reply.OK;  // already handled
        return process(eventPk, conn.getId()) ? Reply.OK : Reply.FAILED;
    }

    /** Stores the event; returns its id, or null when this event was already processed or ignored. */
    private Long store(String eventId, String topic, String shop, String payload) {
        try {
            return tx.execute(s -> {
                Optional<ShopifyWebhookEvent> existing = eventRepository.findByEventId(eventId);
                if (existing.isPresent()) {
                    ShopifyWebhookEvent e = existing.get();
                    return e.getStatus() == Status.PROCESSED || e.getStatus() == Status.IGNORED ? null : e.getId();
                }
                ShopifyWebhookEvent e = new ShopifyWebhookEvent();
                e.setEventId(eventId);
                e.setTopic(topic);
                e.setShopDomain(shop);
                e.setPayload(payload);
                return eventRepository.save(e).getId();
            });
        } catch (DataIntegrityViolationException race) {
            // The same event arrived twice at the same moment; the other delivery handles it
            return null;
        }
    }

    private boolean process(Long eventPk, Long connectionId) {
        ShopifyWebhookEvent event = eventRepository.findById(eventPk).orElseThrow();
        Status outcome;
        String error = null;
        try {
            outcome = AuditActorContext.runAs(ActorType.INTEGRATION, "shopify", () -> handle(event, connectionId));
        } catch (RuntimeException e) {
            log.warn("Shopify webhook {} ({}) failed: {}", event.getTopic(), event.getEventId(), e.getMessage());
            outcome = Status.FAILED;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        Status finalOutcome = outcome;
        String finalError = error;
        tx.executeWithoutResult(s -> eventRepository.findById(eventPk).ifPresent(e -> {
            e.setStatus(finalOutcome);
            e.setError(finalError);
            e.setProcessedAt(LocalDateTime.now());
        }));
        return outcome != Status.FAILED;
    }

    private Status handle(ShopifyWebhookEvent event, Long connectionId) {
        if (ORDER_TOPICS.contains(event.getTopic())) {
            try {
                importer.importOrder(ShopifyOrder.fromWebhook(objectMapper.readTree(event.getPayload())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("Webhook body is not valid JSON");
            }
            return Status.PROCESSED;
        }
        if ("app/uninstalled".equals(event.getTopic())) {
            tx.executeWithoutResult(s -> connectionRepository.findById(connectionId).ifPresent(c -> {
                c.setStatus(ShopifyConnection.Status.ERROR);
                c.setLastError("The Expert ERP app was uninstalled from the Shopify store. Install it again, then test the connection.");
                c.setAccessTokenEncrypted(null);
                c.setTokenExpiresAt(null);
                c.setWebhooksRegisteredAt(null);
                c.setWebhookTopics(null);
            }));
            return Status.PROCESSED;
        }
        return Status.IGNORED;
    }

    /** Shopify signs the raw body with HMAC-SHA256 using the app's client secret (base64 in X-Shopify-Hmac-Sha256). */
    static boolean validSignature(byte[] body, String hmacHeader, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] given = Base64.getDecoder().decode(hmacHeader.trim());
            return MessageDigest.isEqual(expected, given);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }
}
