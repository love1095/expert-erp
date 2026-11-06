package com.experterp.backend.integration.hmrc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calls HMRC: the sandbox (https://test-api.service.hmrc.gov.uk) until the live credentials are set
 * (APP_HMRC_BASE_URL=https://api.service.hmrc.gov.uk). Credentials come from HMRC's Developer Hub.
 */
@Component
public class HttpHmrcClient implements HmrcClient {

    private static final String ACCEPT = "application/vnd.hmrc.1.0+json";

    private final ObjectMapper json;
    private final String baseUrl, clientId, clientSecret, redirectUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpHmrcClient(ObjectMapper json,
                          @Value("${app.hmrc.base-url:https://test-api.service.hmrc.gov.uk}") String baseUrl,
                          @Value("${app.hmrc.client-id:}") String clientId,
                          @Value("${app.hmrc.client-secret:}") String clientSecret,
                          @Value("${app.hmrc.redirect-uri:}") String redirectUri) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    @Override
    public String authorizeUrl(String state) {
        return baseUrl + "/oauth/authorize?response_type=code&client_id=" + enc(clientId) + "&scope=" + enc("read:vat write:vat")
                + "&state=" + enc(state) + "&redirect_uri=" + enc(redirectUri);
    }

    @Override
    public Tokens exchangeCode(String code) {
        return tokens(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirectUri));
    }

    @Override
    public Tokens refresh(String refreshToken) {
        return tokens(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
    }

    private Tokens tokens(Map<String, String> form) {
        Map<String, String> all = new LinkedHashMap<>(form);
        all.put("client_id", clientId);
        all.put("client_secret", clientSecret);
        String body = all.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        JsonNode n = send(HttpRequest.newBuilder(URI.create(baseUrl + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)));
        return new Tokens(n.path("access_token").asText(), n.path("refresh_token").asText(null), n.path("expires_in").asLong(14400));
    }

    @Override
    public List<Obligation> obligations(String vrn, String accessToken, LocalDate from, LocalDate to, Map<String, String> fraudHeaders) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/organisations/vat/" + enc(vrn) + "/obligations?from=" + from + "&to=" + to)).GET();
        JsonNode n = send(withHeaders(b, accessToken, fraudHeaders));
        List<Obligation> out = new ArrayList<>();
        for (JsonNode o : n.path("obligations")) {
            out.add(new Obligation(o.path("periodKey").asText(), LocalDate.parse(o.path("start").asText()), LocalDate.parse(o.path("end").asText()),
                    LocalDate.parse(o.path("due").asText()), o.path("status").asText(),
                    o.hasNonNull("received") ? LocalDate.parse(o.path("received").asText()) : null));
        }
        return out;
    }

    @Override
    public Receipt submit(String vrn, String accessToken, Submission s, Map<String, String> fraudHeaders) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/organisations/vat/" + enc(vrn) + "/returns"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(s)));
            JsonNode n = send(withHeaders(b, accessToken, fraudHeaders));
            return new Receipt(n.path("processingDate").asText(null), n.path("formBundleNumber").asText(null),
                    n.path("paymentIndicator").asText(null), n.path("chargeRefNumber").asText(null));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpRequest.Builder withHeaders(HttpRequest.Builder b, String accessToken, Map<String, String> fraudHeaders) {
        b.header("Accept", ACCEPT).header("Authorization", "Bearer " + accessToken).timeout(Duration.ofSeconds(30));
        fraudHeaders.forEach((k, v) -> { if (v != null && !v.isBlank()) b.header(k, v); });
        return b;
    }

    private JsonNode send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            if (r.statusCode() >= 300) {
                throw new HmrcException(r.statusCode(), n.path("code").asText(n.path("error").asText("HMRC_ERROR")),
                        n.path("message").asText(n.path("error_description").asText("HMRC answered " + r.statusCode())));
            }
            return n;
        } catch (IOException e) {
            throw new HmrcException(503, "UNREACHABLE", "HMRC could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HmrcException(503, "INTERRUPTED", "Interrupted");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
