package com.experterp.backend.integration.xero;

import com.experterp.backend.security.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The Expert ERP app at Xero (one for the whole platform, like Cin7's own): client ID, client secret, redirect address
 * and webhook key. The super admin sets them on Platform → Integration apps (kept in platform_integration_apps, the
 * secret and the key encrypted); what is not set there falls back to the server's settings (APP_XERO_CLIENT_ID …).
 * Each organisation only connects its own Xero with it (Settings → Integrations → Xero) and never sees these.
 */
@Slf4j
@Service
public class XeroAppSettings {

    static final String APP = "XERO";

    /** What the live client works with. */
    public record App(String clientId, String clientSecret, String redirectUri, String webhookKey) {
        public boolean configured() { return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank(); }
    }

    /** What the super admin's page shows: never the secret or the key themselves. */
    public record View(String mode, boolean configured, String clientId, boolean clientSecretSet, String redirectUri, boolean webhookKeySet,
                       boolean fromServerSettings, int connectedOrganisations, LocalDateTime updatedAt, String updatedBy) {}

    public record Change(String clientId, String clientSecret, String redirectUri, String webhookKey) {}

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final String mode, envClientId, envClientSecret, envRedirectUri, envWebhookKey;

    public XeroAppSettings(JdbcTemplate jdbc, SecretCipher cipher,
                           @Value("${app.xero.mode:fake}") String mode,
                           @Value("${app.xero.client-id:}") String clientId,
                           @Value("${app.xero.client-secret:}") String clientSecret,
                           @Value("${app.xero.redirect-uri:}") String redirectUri,
                           @Value("${app.xero.webhook-key:}") String webhookKey) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mode = mode;
        this.envClientId = clientId;
        this.envClientSecret = clientSecret;
        this.envRedirectUri = redirectUri;
        this.envWebhookKey = webhookKey;
    }

    /** The page's values first, the server's settings for what the page leaves empty. */
    public App app() {
        Map<String, Object> r = row();
        if (r == null) return new App(envClientId, envClientSecret, envRedirectUri, envWebhookKey);
        return new App(or((String) r.get("client_id"), envClientId), or(decrypt(r.get("client_secret_encrypted")), envClientSecret),
                or((String) r.get("redirect_uri"), envRedirectUri), or(decrypt(r.get("webhook_key_encrypted")), envWebhookKey));
    }

    @Transactional(readOnly = true)
    public View view() {
        Map<String, Object> r = row();
        App a = app();
        Integer connected = jdbc.queryForObject("SELECT count(*) FROM xero_connections WHERE status = 'CONNECTED'", Integer.class);
        return new View(mode, a.configured(), a.clientId(), !a.clientSecret().isBlank(), a.redirectUri(), !a.webhookKey().isBlank(),
                r == null && (!envClientId.isBlank() || !envClientSecret.isBlank() || !envRedirectUri.isBlank()),
                connected == null ? 0 : connected,
                r == null ? null : ((Timestamp) r.get("updated_at")).toLocalDateTime(), r == null ? null : (String) r.get("updated_by"));
    }

    /** Saves the app. An empty secret or webhook key keeps the one already saved (they are never shown again). */
    @Transactional
    public View save(Change c) {
        String clientId = trim(c.clientId()), redirect = trim(c.redirectUri()), secret = trim(c.clientSecret()), key = trim(c.webhookKey());
        if (clientId.isEmpty()) throw new IllegalArgumentException("Enter the Client ID of the Xero app");
        if (!redirect.matches("https?://\\S+")) throw new IllegalArgumentException("The redirect address must start with http:// or https://");
        Map<String, Object> r = row();
        String secretStored = !secret.isEmpty() ? cipher.encrypt(secret) : r == null ? null : (String) r.get("client_secret_encrypted");
        String keyStored = !key.isEmpty() ? cipher.encrypt(key) : r == null ? null : (String) r.get("webhook_key_encrypted");
        if (secretStored == null && envClientSecret.isBlank()) throw new IllegalArgumentException("Enter the Client secret of the Xero app");
        jdbc.update("""
                INSERT INTO platform_integration_apps (app, client_id, client_secret_encrypted, redirect_uri, webhook_key_encrypted, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, now(), ?)
                ON CONFLICT (app) DO UPDATE SET client_id = EXCLUDED.client_id, client_secret_encrypted = EXCLUDED.client_secret_encrypted,
                    redirect_uri = EXCLUDED.redirect_uri, webhook_key_encrypted = EXCLUDED.webhook_key_encrypted,
                    updated_at = now(), updated_by = EXCLUDED.updated_by""", APP, clientId, secretStored, redirect, keyStored, user());
        log.info("Xero app saved by {} (client secret {}, webhook key {})", user(), secret.isEmpty() ? "kept" : "changed", key.isEmpty() ? "kept" : "changed");
        return view();
    }

    /** Forgets what the page saved: the server's settings (if any) apply again. Connections already made stay. */
    @Transactional
    public View remove() {
        jdbc.update("DELETE FROM platform_integration_apps WHERE app = ?", APP);
        log.info("Xero app removed by {}", user());
        return view();
    }

    private Map<String, Object> row() {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM platform_integration_apps WHERE app = ?", APP);
        return r.isEmpty() ? null : r.get(0);
    }

    private String decrypt(Object stored) { return stored == null ? "" : cipher.decrypt((String) stored); }

    private static String or(String a, String b) { return a == null || a.isBlank() ? (b == null ? "" : b) : a; }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static String user() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
