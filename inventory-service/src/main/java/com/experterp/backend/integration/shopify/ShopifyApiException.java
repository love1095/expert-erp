package com.experterp.backend.integration.shopify;

/** A call to Shopify failed; the message is written for the person managing the connection. */
public class ShopifyApiException extends RuntimeException {
    public ShopifyApiException(String message) {
        super(message);
    }
}
