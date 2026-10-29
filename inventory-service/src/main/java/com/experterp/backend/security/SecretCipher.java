package com.experterp.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets stored in the database (integration client secrets and access tokens) with AES-256-GCM.
 * The key comes from APP_ENCRYPTION_KEY and is separate from the JWT signing key, so either can be rotated alone.
 * Stored format: "v1:" + base64(12-byte IV || ciphertext+tag).
 */
@Component
public class SecretCipher {

    static final int KEY_BYTES = 32;
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "app.encryption.key is not set. Provide APP_ENCRYPTION_KEY (base64, exactly " + KEY_BYTES
                            + " bytes), e.g. generate one with: openssl rand -base64 32");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.encryption.key must be base64-encoded", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("app.encryption.key must be exactly " + KEY_BYTES
                    + " bytes, got " + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Fails if the value was changed or encrypted with another key. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not an encrypted value");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a stored secret: wrong APP_ENCRYPTION_KEY or corrupted value", e);
        }
    }
}
