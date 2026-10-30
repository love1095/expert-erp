package com.experterp.backend.integration.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal Shopify Admin API client: client-credentials tokens and GraphQL calls.
 * Errors become {@link ShopifyApiException} with a message meant for the person setting up the connection.
 */
@Component
public class ShopifyClient {

    public record AccessToken(String token, String scope, long expiresInSeconds) {}

    private static final int MAX_THROTTLE_RETRIES = 6;

    private final ObjectMapper objectMapper;
    private final String apiVersion;
    // Blank = https://{shop}; tests point it at a local stub server
    private final String baseUrlOverride;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ShopifyClient(ObjectMapper objectMapper,
                         @Value("${app.shopify.api-version:2026-10}") String apiVersion,
                         @Value("${app.shopify.base-url:}") String baseUrlOverride) {
        this.objectMapper = objectMapper;
        this.apiVersion = apiVersion;
        this.baseUrlOverride = baseUrlOverride;
    }

    public String apiVersion() {
        return apiVersion;
    }

    /** Client credentials grant: works for Dev Dashboard apps installed on a store in the same organization. */
    public AccessToken requestToken(String shop, String clientId, String clientSecret) {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(url(shop, "/admin/oauth/access_token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> res = send(shop, req);
        if (res.statusCode() != 200) throw tokenError(shop, res);
        JsonNode body = json(res.body());
        if (!body.hasNonNull("access_token")) {
            throw new ShopifyApiException("Shopify did not return an access token: " + abbreviate(res.body()));
        }
        return new AccessToken(body.get("access_token").asText(), body.path("scope").asText(""),
                body.path("expires_in").asLong(86399));
    }

    /** Runs a GraphQL query and returns its "data"; GraphQL errors throw. */
    public JsonNode graphql(String shop, String accessToken, String query, Map<String, Object> variables) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("query", query, "variables", variables));
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not serialise GraphQL request", e);
        }
        HttpRequest req = HttpRequest.newBuilder(url(shop, "/admin/api/" + apiVersion + "/graphql.json"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Shopify-Access-Token", accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> res = send(shop, req);
            if (res.statusCode() == 401) {
                throw new ShopifyApiException("Shopify rejected the access token (HTTP 401). Test the connection again.");
            }
            boolean throttled = res.statusCode() == 429;
            JsonNode body = null;
            if (!throttled) {
                if (res.statusCode() != 200) {
                    throw new ShopifyApiException("Shopify API returned HTTP " + res.statusCode() + ": " + abbreviate(res.body()));
                }
                body = json(res.body());
                throttled = body.has("errors") && body.get("errors").toString().contains("THROTTLED");
            }
            if (throttled) {
                // Shopify's query budget refills every second; long runs (e.g. product sync) hit it
                if (attempt >= MAX_THROTTLE_RETRIES) {
                    throw new ShopifyApiException("Shopify kept asking to slow down (rate limit). Try again in a minute.");
                }
                pause(Duration.ofSeconds(attempt));
                continue;
            }
            if (body.has("errors")) {
                throw new ShopifyApiException("Shopify API error: " + abbreviate(body.get("errors").toString()));
            }
            return body.path("data");
        }
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShopifyApiException("Interrupted while waiting for Shopify's rate limit");
        }
    }

    private URI url(String shop, String path) {
        String base = baseUrlOverride == null || baseUrlOverride.isBlank() ? "https://" + shop : baseUrlOverride;
        return URI.create(base + path);
    }

    private HttpResponse<String> send(String shop, HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ShopifyApiException("Shopify did not answer in time (" + shop + "). Try again.");
        } catch (ConnectException e) {
            throw new ShopifyApiException("Could not reach " + shop + ". Check the store address.");
        } catch (IOException e) {
            throw new ShopifyApiException("Could not reach " + shop + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShopifyApiException("Interrupted while calling Shopify");
        }
    }

    private ShopifyApiException tokenError(String shop, HttpResponse<String> res) {
        String body = res.body() == null ? "" : res.body();
        if (body.contains("shop_not_permitted")) {
            return new ShopifyApiException("Shopify refused: the app and " + shop + " are not in the same organization. "
                    + "Use a store listed under Dev Dashboard → Dev stores, with the app installed on it.");
        }
        if (body.contains("application_cannot_be_found")) {
            return new ShopifyApiException("Shopify has no app with this client ID. "
                    + "Copy it again from the app's Settings page in the Dev Dashboard.");
        }
        if (body.contains("invalid_client") || res.statusCode() == 401) {
            return new ShopifyApiException("Shopify rejected the client ID or client secret. "
                    + "Copy both again from the app's Settings page in the Dev Dashboard.");
        }
        if (res.statusCode() == 404) {
            return new ShopifyApiException("No Shopify store answered at " + shop + ". Check the store address.");
        }
        return new ShopifyApiException("Shopify returned HTTP " + res.statusCode() + " for the token request: " + abbreviate(body));
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ShopifyApiException("Shopify sent a response that is not JSON: " + abbreviate(body));
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
