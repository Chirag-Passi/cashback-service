package com.js.motos.clientApp.common;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Secure random code generation and one-way hashing helpers.
 */
public final class Codes {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Crockford-style alphabet: no I, L, O, U, 0 or 1. QR codes get printed and
     * occasionally typed in by hand, so visually ambiguous characters are out.
     */
    private static final char[] QR_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Codes() {
    }

    /** Numeric OTP of the given length, zero-padded, uniformly distributed. */
    public static String numericOtp(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * Opaque QR payload code. 16 chars over a 30-symbol alphabet is ~78 bits of
     * entropy — not guessable, so scanning a random code can't hit a live one.
     */
    public static String qrCode() {
        return randomFromAlphabet(16, QR_ALPHABET);
    }

    /** URL-safe, unpadded random token used as the opaque refresh token. */
    public static String refreshToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomFromAlphabet(int length, char[] alphabet) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    /**
     * SHA-256 hex digest. Used for refresh tokens, which are already high-entropy
     * random values — a slow KDF buys nothing there and would cost a lot per call.
     * Low-entropy secrets (PINs, OTPs) must use BCrypt instead.
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison, to keep string equality out of auth paths. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}