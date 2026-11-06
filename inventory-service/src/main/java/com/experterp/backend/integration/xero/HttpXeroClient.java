package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase L1: Xero's real API (APP_XERO_MODE=live) — OAuth 2.0 authorisation code flow (login.xero.com, identity.xero.com),
 * the connections list to choose the organisation, and the Accounting API (api.xro/2.0) with the xero-tenant-id header.
 * Needs the owner's Xero developer app: APP_XERO_CLIENT_ID, APP_XERO_CLIENT_SECRET, APP_XERO_REDIRECT_URI
 * (https://&lt;server&gt;/finance/xero/callback). Not run against Xero yet: live-tested with the Demo Company first (TODO).
 */
@Component
@ConditionalOnProperty(name = "app.xero.mode", havingValue = "live")
public class HttpXeroClient implements XeroClient {

    private static final String SCOPES = "openid profile email offline_access accounting.transactions accounting.contacts accounting.settings.read";

    private final ObjectMapper json;
    private final String clientId, clientSecret, redirectUri, identityUrl, loginUrl, apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpXeroClient(ObjectMapper objectMapper,
                          @Value("${app.xero.client-id:}") String clientId,
                          @Value("${app.xero.client-secret:}") String clientSecret,
                          @Value("${app.xero.redirect-uri:}") String redirectUri,
                          @Value("${app.xero.login-url:https://login.xero.com}") String loginUrl,
                          @Value("${app.xero.identity-url:https://identity.xero.com}") String identityUrl,
                          @Value("${app.xero.api-url:https://api.xero.com}") String apiUrl) {
        this.json = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.loginUrl = loginUrl;
        this.identityUrl = identityUrl;
        this.apiUrl = apiUrl;
    }

    @Override
    public String mode() { return "live"; }

    @Override
    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    @Override
    public String authorizeUrl(String state) {
        return loginUrl + "/identity/connect/authorize?response_type=code&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(SCOPES) + "&state=" + enc(state);
    }

    @Override
    public Tokens exchangeCode(String code) {
        return token("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri));
    }

    @Override
    public Tokens refresh(String refreshToken) {
        return token("grant_type=refresh_token&refresh_token=" + enc(refreshToken));
    }

    private Tokens token(String form) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        JsonNode n = send(HttpRequest.newBuilder(URI.create(identityUrl + "/connect/token")).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)));
        return new Tokens(n.path("access_token").asText(), n.path("refresh_token").asText(null), n.path("expires_in").asLong(1800));
    }

    @Override
    public List<Organisation> connections(String accessToken) {
        JsonNode n = send(HttpRequest.newBuilder(URI.create(apiUrl + "/connections")).header("Authorization", "Bearer " + accessToken).GET());
        return json.convertValue(n, new TypeReference<List<Organisation>>() {});
    }

    @Override
    public Contact saveContact(Session s, Contact c) {
        return one(post(s, "Contacts", Map.of("Contacts", List.of(c))), "Contacts", Contact.class);
    }

    @Override
    public Invoice saveInvoice(Session s, Invoice i) {
        return one(post(s, "Invoices", Map.of("Invoices", List.of(i))), "Invoices", Invoice.class);
    }

    @Override
    public Invoice voidInvoice(Session s, String invoiceId) {
        return one(post(s, "Invoices/" + enc(invoiceId), Map.of("Invoices", List.of(Map.of("InvoiceID", invoiceId, "Status", "VOIDED")))), "Invoices", Invoice.class);
    }

    @Override
    public CreditNote saveCreditNote(Session s, CreditNote c) {
        return one(post(s, "CreditNotes", Map.of("CreditNotes", List.of(c))), "CreditNotes", CreditNote.class);
    }

    @Override
    public Allocation allocate(Session s, String creditNoteId, Allocation a) {
        return one(put(s, "CreditNotes/" + enc(creditNoteId) + "/Allocations", Map.of("Allocations", List.of(a))), "Allocations", Allocation.class);
    }

    @Override
    public Payment createPayment(Session s, Payment p) {
        return one(put(s, "Payments", Map.of("Payments", List.of(p))), "Payments", Payment.class);
    }

    @Override
    public void deletePayment(Session s, String paymentId) {
        post(s, "Payments/" + enc(paymentId), Map.of("Status", "DELETED"));
    }

    @Override
    public ManualJournal saveManualJournal(Session s, ManualJournal j) {
        return one(post(s, "ManualJournals", Map.of("ManualJournals", List.of(j))), "ManualJournals", ManualJournal.class);
    }

    @Override
    public List<Payment> payments(Session s, OffsetDateTime modifiedSince) {
        HttpRequest.Builder b = api(s, "Payments").GET();
        if (modifiedSince != null) b.header("If-Modified-Since", modifiedSince.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        JsonNode n = send(b);
        return json.convertValue(n.path("Payments"), new TypeReference<List<Payment>>() {});
    }

    // ---- shared ----

    private JsonNode post(Session s, String endpoint, Object body) {
        return send(api(s, endpoint).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    private JsonNode put(Session s, String endpoint, Object body) {
        return send(api(s, endpoint).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    private HttpRequest.Builder api(Session s, String endpoint) {
        return HttpRequest.newBuilder(URI.create(apiUrl + "/api.xro/2.0/" + endpoint)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + s.accessToken()).header("xero-tenant-id", s.xeroTenantId()).header("Accept", "application/json");
    }

    private <T> T one(JsonNode n, String list, Class<T> type) {
        JsonNode first = n.path(list).path(0);
        if (first.isMissingNode()) throw new XeroException(XeroException.Kind.UNAVAILABLE, 502, "Xero sent back no " + list);
        return json.convertValue(first, type);
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // Xero's errors: 400 ValidationException (Elements[].ValidationErrors[].Message), 401 token, 404, 429 with Retry-After, 5xx
    private JsonNode send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            int st = r.statusCode();
            if (st < 300) return n;
            if (st == 429) {
                long wait = r.headers().firstValue("Retry-After").map(Long::parseLong).orElse(60L);
                throw new XeroException(XeroException.Kind.RATE_LIMIT, 429, "Xero's rate limit: wait " + wait + " s", wait);
            }
            if (st == 401 || st == 403) throw new XeroException(XeroException.Kind.UNAUTHORIZED, st, n.path("Detail").asText(n.path("error").asText("Not authorised")));
            if (st == 404) throw new XeroException(XeroException.Kind.NOT_FOUND, 404, "Not found in Xero");
            if (st == 400) {
                List<String> messages = new ArrayList<>();
                n.path("Elements").forEach(e -> e.path("ValidationErrors").forEach(v -> messages.add(v.path("Message").asText())));
                throw new XeroException(XeroException.Kind.VALIDATION, 400,
                        messages.isEmpty() ? n.path("Message").asText(n.path("error").asText("Xero refused it")) : String.join("; ", messages));
            }
            throw new XeroException(XeroException.Kind.UNAVAILABLE, st, "Xero answered " + st);
        } catch (IOException e) {
            throw new XeroException(XeroException.Kind.UNAVAILABLE, 503, "Xero could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XeroException(XeroException.Kind.UNAVAILABLE, 503, "Interrupted");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
