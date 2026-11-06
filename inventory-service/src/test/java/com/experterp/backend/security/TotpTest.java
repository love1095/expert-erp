package com.experterp.backend.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 6238 appendix B test vectors (SHA-1, 8-digit codes there; their last 6 digits are the 6-digit codes). */
class TotpTest {

    private static final String SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfcVectors() {
        assertThat(Totp.code(SECRET, Totp.stepAt(59))).isEqualTo("287082");          // 94287082
        assertThat(Totp.code(SECRET, Totp.stepAt(1111111109))).isEqualTo("081804");  // 07081804
        assertThat(Totp.code(SECRET, Totp.stepAt(1234567890))).isEqualTo("005924");  // 89005924
        assertThat(Totp.code(SECRET, Totp.stepAt(20000000000L))).isEqualTo("353130"); // 65353130
    }

    @Test
    void acceptsOneStepOfDriftOnlyAndOnlySixDigits() {
        long now = 1_700_000_000L;
        String current = Totp.code(SECRET, Totp.stepAt(now));
        assertThat(Totp.matchingStep(SECRET, current, now)).isEqualTo(Totp.stepAt(now));
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, Totp.stepAt(now) - 1), now)).isEqualTo(Totp.stepAt(now) - 1);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, Totp.stepAt(now) - 2), now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(SECRET, "12345", now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(SECRET, current.substring(0, 3) + " " + current.substring(3), now)).isEqualTo(Totp.stepAt(now));
        assertThat(Totp.unbase32(Totp.base32(new byte[]{1, 2, 3, 4, 5}))).containsExactly(1, 2, 3, 4, 5);
        assertThat(Totp.uri("Expert ERP", "dwaine@expertsafety.co.uk", "ABC")).startsWith("otpauth://totp/Expert%20ERP:dwaine%40expertsafety.co.uk?secret=ABC&issuer=Expert%20ERP");
    }
}
