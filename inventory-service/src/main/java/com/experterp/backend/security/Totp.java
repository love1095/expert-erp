package com.experterp.backend.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Time-based one-time codes (RFC 6238: HMAC-SHA1, 30 seconds, 6 digits), what Google / Microsoft Authenticator and
 * the like show. Phase I5.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A new secret: 160 random bits, base32 (what authenticator apps take). */
    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32(b);
    }

    public static long stepAt(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** The 6-digit code for a step. */
    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The step a code belongs to, within one step either side of now (clocks drift), or -1. Compared in constant time
     * per candidate.
     */
    public static long matchingStep(String base32Secret, String code, long nowEpochSeconds) {
        if (code == null) return -1;
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{6}")) return -1;
        long now = stepAt(nowEpochSeconds);
        long found = -1;
        for (long s = now - 1; s <= now + 1; s++) {
            if (java.security.MessageDigest.isEqual(code(base32Secret, s).getBytes(), c.getBytes())) found = s;
        }
        return found;
    }

    /** The link authenticator apps open (otpauth://totp/Issuer:account?secret=…&issuer=…). */
    public static String uri(String issuer, String account, String base32Secret) {
        String i = enc(issuer), a = enc(account);
        return "otpauth://totp/" + i + ":" + a + "?secret=" + base32Secret + "&issuer=" + i + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char ch : clean.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("Not a base32 secret");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
