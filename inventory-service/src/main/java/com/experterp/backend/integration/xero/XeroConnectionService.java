package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.Organisation;
import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.integration.xero.XeroApi.Tokens;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.security.SecretCipher;
import com.experterp.backend.service.AuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Phase L1: the organisation's Xero connection — connect (OAuth: Xero's sign-in, then our callback with the state we
 * gave), choose the Xero organisation when the user gave access to several, a session for API calls with the access
 * token refreshed before it expires (Xero's last 30 minutes; the refresh token is single use), disconnect. Tokens are
 * kept encrypted with the same key as the Shopify tokens.
 */
@Service
@RequiredArgsConstructor
public class XeroConnectionService {

    private final JdbcTemplate jdbc;
    private final XeroClient xero;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public record Status(String mode, boolean configured, String status, String xeroTenantName, List<Organisation> organisations,
                         LocalDateTime connectedAt, String connectedBy, String lastError) {}

    @Transactional(readOnly = true)
    public Status status() {
        Map<String, Object> c = row();
        if (c == null) return new Status(xero.mode(), xero.configured(), "NOT_CONNECTED", null, List.of(), null, null, null);
        return new Status(xero.mode(), xero.configured(), (String) c.get("status"), (String) c.get("xero_tenant_name"), organisations(c),
                c.get("connected_at") == null ? null : ((Timestamp) c.get("connected_at")).toLocalDateTime(), (String) c.get("connected_by"),
                (String) c.get("last_error"));
    }

    /** Where to send the user (Xero's sign-in; the fake comes straight back). */
    @Transactional
    public String connect() {
        if (!xero.configured()) throw new IllegalStateException("The Xero link needs the Xero developer app on this server (APP_XERO_CLIENT_ID, APP_XERO_CLIENT_SECRET, APP_XERO_REDIRECT_URI)");
        String state = HexFormat.of().formatHex(random.generateSeed(16));
        jdbc.update("INSERT INTO xero_connections (tenant_id, oauth_state) VALUES (?, ?) ON CONFLICT (tenant_id) DO UPDATE SET oauth_state = EXCLUDED.oauth_state",
                TenantContext.get(), state);
        return xero.authorizeUrl(state);
    }

    /** Xero sends the user back with a code: tokens kept; one organisation is chosen at once, several are offered. */
    @Transactional
    public Status callback(String code, String state) {
        Map<String, Object> c = row();
        if (c == null || state == null || !state.equals(c.get("oauth_state"))) throw new IllegalStateException("This Xero answer is not for this organisation: connect again");
        Tokens tk = xero.exchangeCode(code);
        List<Organisation> orgs = xero.connections(tk.accessToken());
        if (orgs.isEmpty()) throw new IllegalStateException("No Xero organisation was given access: connect again and tick one");
        saveTokens(tk);
        jdbc.update("UPDATE xero_connections SET oauth_state = NULL, organisations = ?, connected_at = now(), connected_by = ?, last_error = NULL, status = ? WHERE tenant_id = ?",
                write(orgs), auditService.currentActorName(), orgs.size() == 1 ? "CONNECTED" : "CHOOSE_ORGANISATION", TenantContext.get());
        if (orgs.size() == 1) choose(orgs.get(0).tenantId());
        auditService.log("XERO", null, "CONNECT", Map.of("organisations", orgs.size()));
        return status();
    }

    /** The Xero organisation the ERP sends to (from those the user gave access to). */
    @Transactional
    public Status choose(String xeroTenantId) {
        Map<String, Object> c = row();
        Organisation o = c == null ? null : organisations(c).stream().filter(x -> x.tenantId().equals(xeroTenantId)).findFirst().orElse(null);
        if (o == null) throw new IllegalArgumentException("That Xero organisation was not given access");
        jdbc.update("UPDATE xero_connections SET xero_tenant_id = ?, xero_tenant_name = ?, status = 'CONNECTED' WHERE tenant_id = ?",
                o.tenantId(), o.tenantName(), TenantContext.get());
        auditService.log("XERO", null, "ORGANISATION", Map.of("name", o.tenantName()));
        return status();
    }

    @Transactional
    public Status disconnect() {
        jdbc.update("""
                UPDATE xero_connections SET access_token_encrypted = NULL, refresh_token_encrypted = NULL, token_expires_at = NULL,
                       xero_tenant_id = NULL, xero_tenant_name = NULL, organisations = NULL, status = 'NOT_CONNECTED' WHERE tenant_id = ?""", TenantContext.get());
        auditService.log("XERO", null, "DISCONNECT", Map.of());
        return status();
    }

    /** A session for API calls, the token refreshed when it is about to expire (or when Xero said it had). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Session session(boolean forceRefresh) {
        Map<String, Object> c = row();
        if (c == null || !"CONNECTED".equals(c.get("status")) || c.get("xero_tenant_id") == null) throw new IllegalStateException("Xero is not connected");
        Timestamp exp = (Timestamp) c.get("token_expires_at");
        if (!forceRefresh && exp != null && exp.toLocalDateTime().isAfter(LocalDateTime.now().plusMinutes(2))) {
            return new Session(cipher.decrypt((String) c.get("access_token_encrypted")), (String) c.get("xero_tenant_id"));
        }
        try {
            Tokens tk = xero.refresh(cipher.decrypt((String) c.get("refresh_token_encrypted")));
            saveTokens(tk);
            return new Session(tk.accessToken(), (String) c.get("xero_tenant_id"));
        } catch (XeroException e) {
            jdbc.update("UPDATE xero_connections SET status = 'EXPIRED', last_error = ? WHERE tenant_id = ?",
                    "The Xero connection has expired: connect again (" + e.getMessage() + ")", TenantContext.get());
            throw new IllegalStateException("The Xero connection has expired: connect again");
        }
    }

    private void saveTokens(Tokens tk) {
        jdbc.update("UPDATE xero_connections SET access_token_encrypted = ?, refresh_token_encrypted = COALESCE(?, refresh_token_encrypted), token_expires_at = ? WHERE tenant_id = ?",
                cipher.encrypt(tk.accessToken()), tk.refreshToken() == null ? null : cipher.encrypt(tk.refreshToken()),
                Timestamp.valueOf(LocalDateTime.now().plusSeconds(tk.expiresInSeconds())), TenantContext.get());
    }

    private Map<String, Object> row() {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM xero_connections WHERE tenant_id = ?", TenantContext.get());
        return r.isEmpty() ? null : r.get(0);
    }

    private List<Organisation> organisations(Map<String, Object> c) {
        String s = (String) c.get("organisations");
        if (s == null) return List.of();
        try {
            return json.readValue(s, new TypeReference<List<Organisation>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
