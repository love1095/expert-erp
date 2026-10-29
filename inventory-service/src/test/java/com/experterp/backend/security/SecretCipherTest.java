package com.experterp.backend.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static String key(int fill) {
        byte[] b = new byte[SecretCipher.KEY_BYTES];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void roundTripsAndNeverStoresThePlaintext() {
        SecretCipher cipher = new SecretCipher(key(1));
        String stored = cipher.encrypt("shpss_secret-value");

        assertThat(stored).startsWith("v1:").doesNotContain("shpss_secret-value");
        assertThat(cipher.decrypt(stored)).isEqualTo("shpss_secret-value");
        // Random IV: the same secret encrypts differently each time
        assertThat(cipher.encrypt("shpss_secret-value")).isNotEqualTo(stored);
    }

    @Test
    void anotherKeyOrATamperedValueCannotBeDecrypted() {
        String stored = new SecretCipher(key(1)).encrypt("secret");

        assertThatThrownBy(() -> new SecretCipher(key(2)).decrypt(stored))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_ENCRYPTION_KEY");

        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> new SecretCipher(key(1)).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupFailsWithoutAProperKey() {
        assertThatThrownBy(() -> new SecretCipher("")).hasMessageContaining("APP_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new SecretCipher("not base64!")).hasMessageContaining("base64");
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("exactly 32 bytes");
    }
}
