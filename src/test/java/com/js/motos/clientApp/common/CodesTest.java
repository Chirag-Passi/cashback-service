package com.js.motos.clientApp.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CodesTest {

    @Test
    @DisplayName("OTP is numeric and exactly the requested length")
    void otpShape() {
        for (int i = 0; i < 200; i++) {
            String otp = Codes.numericOtp(6);
            assertThat(otp).hasSize(6).containsOnlyDigits();
        }
    }

    @Test
    @DisplayName("QR codes avoid visually ambiguous characters")
    void qrAlphabetIsUnambiguous() {
        // These get printed and sometimes typed by hand off a scuffed sticker.
        for (int i = 0; i < 500; i++) {
            assertThat(Codes.qrCode())
                    .hasSize(16)
                    .doesNotContainAnyWhitespaces()
                    .matches("[23456789ABCDEFGHJKMNPQRSTVWXYZ]{16}");
        }
    }

    @Test
    @DisplayName("QR codes do not collide across a realistic batch size")
    void qrCodesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            seen.add(Codes.qrCode());
        }
        assertThat(seen).hasSize(5000);
    }

    @Test
    @DisplayName("refresh tokens are URL-safe and high entropy")
    void refreshTokenShape() {
        String token = Codes.refreshToken();
        assertThat(token).matches("[A-Za-z0-9_-]{64}");
        assertThat(token).isNotEqualTo(Codes.refreshToken());
    }

    @Test
    @DisplayName("sha256 is stable and differs per input")
    void sha256() {
        assertThat(Codes.sha256Hex("abc")).isEqualTo(Codes.sha256Hex("abc")).hasSize(64);
        assertThat(Codes.sha256Hex("abc")).isNotEqualTo(Codes.sha256Hex("abd"));
    }

    @Test
    @DisplayName("constant-time comparison still compares correctly")
    void constantTimeEquals() {
        assertThat(Codes.constantTimeEquals("secret", "secret")).isTrue();
        assertThat(Codes.constantTimeEquals("secret", "secreT")).isFalse();
        assertThat(Codes.constantTimeEquals("secret", null)).isFalse();
        assertThat(Codes.constantTimeEquals(null, null)).isFalse();
    }
}