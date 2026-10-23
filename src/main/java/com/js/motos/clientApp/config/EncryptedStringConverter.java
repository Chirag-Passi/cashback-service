package com.js.motos.clientApp.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM column encryption for bank account numbers.
 *
 * <p>Ciphertext is stored as {@code base64(iv || ciphertext || tag)}. A fresh
 * random IV per write means the same account number encrypts differently each
 * time — which is why encrypted columns can't be queried by equality. Lookups
 * use the separate {@code bank_account_last4} column instead.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public EncryptedStringConverter(@Value("${app.security.encryption-key}") String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "app.security.encryption-key must decode to exactly 32 bytes (AES-256), got "
                            + decoded.length + ". Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt column value", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt column value", e);
        }
    }
}