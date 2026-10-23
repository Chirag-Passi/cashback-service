package com.js.motos.clientApp.qr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.js.motos.clientApp.common.ApiException;

/**
 * The Android scanner may hand us the deep link the QR encodes, or a bare code
 * typed in by hand. Both must resolve to the same stored value.
 */
class QrCodeExtractionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "PXWFW9PQ4TJY5TT3",
            "https://api.example.com/r/PXWFW9PQ4TJY5TT3",
            "http://localhost:8080/r/PXWFW9PQ4TJY5TT3",
            "https://api.example.com/r/PXWFW9PQ4TJY5TT3?utm_source=sticker",
            "  PXWFW9PQ4TJY5TT3  "
    })
    @DisplayName("every scan form resolves to the same code")
    void extractsCode(String scanned) {
        assertThat(QrCodeService.extractCode(scanned)).isEqualTo("PXWFW9PQ4TJY5TT3");
    }

    @Test
    @DisplayName("lowercase input is normalised, since the stored alphabet is uppercase")
    void normalisesCase() {
        assertThat(QrCodeService.extractCode("pxwfw9pq4tjy5tt3")).isEqualTo("PXWFW9PQ4TJY5TT3");
    }

    @Test
    @DisplayName("blank scans are rejected rather than looked up")
    void rejectsBlank() {
        assertThatThrownBy(() -> QrCodeService.extractCode("  "))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QrCodeService.extractCode(null))
                .isInstanceOf(ApiException.class);
    }
}