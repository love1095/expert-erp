package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.entity.ShopifyConnection.Status;
import com.experterp.backend.exception.DuplicateResourceException;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.security.SecretCipher;
import com.experterp.backend.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phase 7.1: saving a tenant's Shopify store + app credentials, and proving they work. */
@Service
@RequiredArgsConstructor
public class ShopifyConnectionService {

    /** Scopes the later phases need (order import, fulfilment, returns, stock sync). */
    public static final List<String> REQUIRED_SCOPES = List.of(
            "read_orders", "read_customers", "read_products", "read_inventory", "write_inventory", "read_locations",
            "read_merchant_managed_fulfillment_orders", "write_merchant_managed_fulfillment_orders", "read_returns");

    private static final Pattern MYSHOPIFY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}\\.myshopify\\.com$");
    private static final Pattern ADMIN_URL = Pattern.compile("admin\\.shopify\\.com/store/([a-z0-9][a-z0-9-]*)");
    // Renew a cached token this long before it expires
    private static final long TOKEN_RENEW_MARGIN_MINUTES = 10;

    private static final String SHOP_QUERY = "{ shop { name myshopifyDomain currencyCode } }";

    private final ShopifyConnectionRepository repository;
    private final ShopifyClient client;
    private final SecretCipher cipher;
    private final AuditService auditService;

    public record SaveRequest(String shopDomain, String clientId, String clientSecret) {}

    public record ConnectionView(
            String shopDomain, String clientId, boolean secretSaved, Status status, String shopName, String shopCurrency,
            List<String> grantedScopes, List<String> missingScopes, String lastError,
            LocalDateTime lastCheckedAt, LocalDateTime tokenExpiresAt, String apiVersion,
            // order import (7.2)
            Long defaultWarehouseId, String defaultWarehouseName, String publicUrl, String webhookUrl,
            LocalDateTime webhooksRegisteredAt, List<String> webhookTopics, LocalDateTime lastImportAt) {}

    @Transactional(readOnly = true)
    public Optional<ConnectionView> get() {
        return repository.findFirstByOrderByIdAsc().map(this::view);
    }

    /** Saves the store and credentials, then tests them. A blank secret on an update keeps the saved one. */
    @Transactional
    public ConnectionView save(SaveRequest req) {
        String shop = normalizeShopDomain(req.shopDomain());
        String clientId = req.clientId() == null ? "" : req.clientId().trim();
        if (clientId.isEmpty()) throw new IllegalArgumentException("Client ID is required");
        String secret = req.clientSecret() == null ? "" : req.clientSecret().trim();

        Long owner = repository.findTenantIdByShopDomain(shop).orElse(null);
        if (owner != null && !owner.equals(TenantContext.get())) {
            throw new DuplicateResourceException(shop + " is already connected to another organization");
        }

        ShopifyConnection c = repository.findFirstByOrderByIdAsc().orElse(null);
        boolean isNew = c == null;
        if (isNew) {
            if (secret.isEmpty()) throw new IllegalArgumentException("Client secret is required");
            c = new ShopifyConnection();
        }
        boolean credentialsChanged = isNew || !shop.equals(c.getShopDomain()) || !clientId.equals(c.getClientId()) || !secret.isEmpty();
        c.setShopDomain(shop);
        c.setClientId(clientId);
        if (!secret.isEmpty()) c.setClientSecretEncrypted(cipher.encrypt(secret));
        if (credentialsChanged) {
            // The cached token belongs to the old credentials
            c.setAccessTokenEncrypted(null);
            c.setTokenExpiresAt(null);
            c.setStatus(Status.NOT_TESTED);
            c.setShopName(null);
            c.setScopes(null);
        }
        c.setUpdatedAt(LocalDateTime.now());
        repository.save(c);
        auditService.log("SHOPIFY", c.getId(), isNew ? "CONNECT" : "UPDATE",
                Map.of("shop", shop, "secretChanged", !secret.isEmpty()));
        return test(c);
    }

    /** Fetches a fresh token and reads the shop; the outcome is stored and returned, never thrown. */
    @Transactional
    public ConnectionView test() {
        return test(current());
    }

    @Transactional
    public void disconnect() {
        ShopifyConnection c = current();
        repository.delete(c);
        auditService.log("SHOPIFY", c.getId(), "DISCONNECT", Map.of("shop", c.getShopDomain()));
    }

    /**
     * A valid access token for the current tenant's store, renewed when it is about to expire.
     * For the import / fulfilment / stock-sync phases.
     */
    @Transactional
    public String accessToken() {
        ShopifyConnection c = current();
        if (c.getAccessTokenEncrypted() != null && c.getTokenExpiresAt() != null
                && c.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(TOKEN_RENEW_MARGIN_MINUTES))) {
            return cipher.decrypt(c.getAccessTokenEncrypted());
        }
        return renewToken(c);
    }

    private ConnectionView test(ShopifyConnection c) {
        c.setLastCheckedAt(LocalDateTime.now());
        try {
            String token = renewToken(c);
            JsonNode shop = client.graphql(c.getShopDomain(), token, SHOP_QUERY, Map.of()).path("shop");
            c.setShopName(shop.path("name").asText(null));
            c.setShopCurrency(shop.path("currencyCode").asText(null));
            c.setStatus(Status.CONNECTED);
            c.setLastError(null);
        } catch (ShopifyApiException e) {
            c.setStatus(Status.ERROR);
            c.setLastError(e.getMessage());
        }
        auditService.log("SHOPIFY", c.getId(), "TEST", Map.of("status", c.getStatus()));
        return view(c);
    }

    private String renewToken(ShopifyConnection c) {
        ShopifyClient.AccessToken t = client.requestToken(c.getShopDomain(), c.getClientId(),
                cipher.decrypt(c.getClientSecretEncrypted()));
        c.setAccessTokenEncrypted(cipher.encrypt(t.token()));
        c.setTokenExpiresAt(LocalDateTime.now().plusSeconds(t.expiresInSeconds()));
        c.setScopes(t.scope());
        return t.token();
    }

    private ShopifyConnection current() {
        return repository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new ResourceNotFoundException("No Shopify store is connected"));
    }

    private ConnectionView view(ShopifyConnection c) {
        List<String> granted = c.getScopes() == null || c.getScopes().isBlank() ? List.of()
                : Arrays.stream(c.getScopes().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        // write_X implies read_X in Shopify
        List<String> missing = granted.isEmpty() ? List.of() : REQUIRED_SCOPES.stream()
                .filter(s -> !granted.contains(s) && !(s.startsWith("read_") && granted.contains("write_" + s.substring(5))))
                .toList();
        var wh = c.getDefaultWarehouse();
        return new ConnectionView(c.getShopDomain(), c.getClientId(), c.getClientSecretEncrypted() != null, c.getStatus(),
                c.getShopName(), c.getShopCurrency(), granted, missing, c.getLastError(), c.getLastCheckedAt(),
                c.getTokenExpiresAt(), client.apiVersion(),
                wh == null ? null : wh.getId(), wh == null ? null : wh.getName(), c.getPublicUrl(),
                c.getPublicUrl() == null ? null : c.getPublicUrl() + ShopifyOrderSyncService.WEBHOOK_PATH,
                c.getWebhooksRegisteredAt(),
                c.getWebhookTopics() == null ? List.of() : List.of(c.getWebhookTopics().split(",")),
                c.getLastImportAt());
    }

    /**
     * Accepts "expertsafety-dev", "expertsafety-dev.myshopify.com", "https://expertsafety-dev.myshopify.com/"
     * or the admin URL "https://admin.shopify.com/store/expertsafety-dev/...". Anything else is refused, so the
     * backend only ever calls *.myshopify.com.
     */
    static String normalizeShopDomain(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Store address is required");
        String s = input.trim().toLowerCase(Locale.ROOT);
        Matcher admin = ADMIN_URL.matcher(s);
        if (admin.find()) return admin.group(1) + ".myshopify.com";
        s = s.replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (!s.contains(".")) s = s + ".myshopify.com";
        if (!MYSHOPIFY.matcher(s).matches()) {
            throw new IllegalArgumentException("Store address must look like your-store.myshopify.com");
        }
        return s;
    }
}
