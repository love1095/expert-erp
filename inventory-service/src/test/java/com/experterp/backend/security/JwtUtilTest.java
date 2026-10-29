package com.experterp.backend.security;

import com.experterp.backend.entity.User;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = base64("a-secret-that-is-long-enough-for-hs256!!");
    private static final String OTHER_SECRET = base64("a-different-secret-also-long-enough-hs256");
    private static final long ONE_HOUR = 3_600_000;

    @Test
    void tokenStaysValidForAnotherInstanceWithTheSameSecret() {
        // Simulates a backend restart (or a second instance): same configured secret, new JwtUtil
        String token = new JwtUtil(SECRET, ONE_HOUR).generateToken(user());
        JwtUtil afterRestart = new JwtUtil(SECRET, ONE_HOUR);

        assertThat(afterRestart.isTokenValid(token, user())).isTrue();
        assertThat(afterRestart.extractUsername(token)).isEqualTo("alice");
        assertThat(afterRestart.extractTenantId(token)).isEqualTo(7L);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = new JwtUtil(OTHER_SECRET, ONE_HOUR).generateToken(user());

        assertThat(new JwtUtil(SECRET, ONE_HOUR).isTokenValid(token, user())).isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtUtil jwt = new JwtUtil(SECRET, -1_000);
        assertThat(jwt.isTokenValid(jwt.generateToken(user()), user())).isFalse();
    }

    @Test
    void missingSecretFailsStartup() {
        assertThatThrownBy(() -> new JwtUtil("", ONE_HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void shortSecretFailsStartup() {
        assertThatThrownBy(() -> new JwtUtil(base64("too-short"), ONE_HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void nonBase64SecretFailsStartup() {
        assertThatThrownBy(() -> new JwtUtil("not base64 !!!", ONE_HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes());
    }

    private static UserDetailsImpl user() {
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        u.setPasswordHash("x");
        u.setRole(User.Role.ADMIN);
        u.setTenantId(7L);
        return new UserDetailsImpl(u);
    }
}
