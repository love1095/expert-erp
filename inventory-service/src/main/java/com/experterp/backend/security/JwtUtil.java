package com.experterp.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.*;
import java.util.function.Function;

@Component
public class JwtUtil {

    // HS256 needs at least 256 bits of key material
    static final int MIN_SECRET_BYTES = 32;

    private final long expiration;
    private final SecretKey key;

    /**
     * The signing key comes from configuration (APP_JWT_SECRET, base64) so tokens survive restarts and
     * every backend instance accepts the same tokens. Startup fails rather than falling back to a random key.
     */
    public JwtUtil(@Value("${app.jwt.secret:}") String secret,
                   @Value("${app.jwt.expiration:86400000}") long expiration) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret is not set. Provide APP_JWT_SECRET (base64, at least " + MIN_SECRET_BYTES
                            + " bytes), e.g. generate one with: openssl rand -base64 48");
        }
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("app.jwt.secret must be base64-encoded", e);
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret is too short: " + bytes.length
                    + " bytes, need at least " + MIN_SECRET_BYTES);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expiration = expiration;
    }

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        if (userDetails instanceof UserDetailsImpl u) {
            claims.put("userId",   u.getId());
            claims.put("role",     u.getRole().name());
            claims.put("tenantId", u.getTenantId());
        }
        return Jwts.builder()
                .claims(claims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Long extractTenantId(String token) {
        Object raw = extractClaim(token, c -> c.get("tenantId"));
        if (raw == null) return null;
        return ((Number) raw).longValue();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return resolver.apply(claims);
    }
}
