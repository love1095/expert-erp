package com.experterp.backend.integration.xero;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Phase L4: Xero's webhooks (live). Xero signs each delivery with the webhook key (x-xero-signature: base64
 * HMAC-SHA256 of the body); a bad signature gets 401, as Xero's "intent to receive" check expects. An event for an
 * invoice of a connected organisation (a payment added or deleted changes the invoice) asks for a payment pull at
 * the next run. Nothing else is done here: the pull reads Xero.
 */
@Slf4j
@RestController
public class XeroWebhookController {

    private final XeroPaymentPuller puller;
    private final ObjectMapper json;
    private final String key;

    public XeroWebhookController(XeroPaymentPuller puller, ObjectMapper json, @Value("${app.xero.webhook-key:}") String key) {
        this.puller = puller;
        this.json = json;
        this.key = key;
    }

    @PostMapping("/api/webhooks/xero")
    public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] body, @RequestHeader(value = "x-xero-signature", required = false) String signature) {
        byte[] b = body == null ? new byte[0] : body;
        if (key.isBlank() || signature == null || !valid(b, signature)) return ResponseEntity.status(401).build();
        try {
            JsonNode events = json.readTree(b).path("events");
            for (JsonNode e : events) {
                if ("INVOICE".equalsIgnoreCase(e.path("eventCategory").asText())) puller.requestPull(e.path("tenantId").asText());
            }
        } catch (Exception e) {
            log.warn("Xero webhook not read: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    boolean valid(byte[] body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = Base64.getEncoder().encode(mac.doFinal(body));
            return MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
