package com.js.motos.clientApp.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.js.motos.clientApp.auth.repo.RefreshTokenRepository;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.repo.UserRepository;

/**
 * A 4-digit PIN is only 10,000 possibilities, so the throttling and weak-PIN
 * rules are what actually secure it. These are the behaviours worth pinning down.
 */
class PinServiceTest {

    private static final int MAX_ATTEMPTS = 5;

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PinService pinService;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        refreshTokenRepository = Mockito.mock(RefreshTokenRepository.class);
        encoder = new BCryptPasswordEncoder(4); // low cost keeps the test fast

        AppProperties properties = new AppProperties(
                "http://localhost", "s@e.com", "Test",
                new AppProperties.Jwt("secret", "iss", Duration.ofMinutes(15)),
                new AppProperties.Refresh(Duration.ofDays(30), 5),
                new AppProperties.Otp(6, Duration.ofMinutes(10), 5, Duration.ofSeconds(60), 5, 20, true),
                new AppProperties.Pin(4, MAX_ATTEMPTS, Duration.ofMinutes(15), Duration.ofMinutes(30)),
                new AppProperties.Google(List.of()),
                new AppProperties.Cloudinary("", "", "", "f", false),
                new AppProperties.Storage("./uploads"),
                new AppProperties.Security("key", List.of("*")),
                new AppProperties.Admin(""));

        pinService = new PinService(userRepository, refreshTokenRepository, encoder, properties);
    }

    private User userWithPin(String pin) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("u@example.com");
        user.setPinHash(encoder.encode(pin));
        user.setPinUpdatedAt(Instant.now());
        return user;
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "0000", "1111", "4321", "6789", "1212", "9999"})
    @DisplayName("obvious PINs are rejected at setup")
    void rejectsWeakPins(String weak) {
        User user = new User();
        user.setId(UUID.randomUUID());

        assertThatThrownBy(() -> pinService.setInitialPin(user, weak))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PIN_WEAK);

        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "12345", "abcd", "12a4", ""})
    @DisplayName("a PIN that is not exactly 4 digits is rejected")
    void rejectsWrongFormat(String bad) {
        User user = new User();
        user.setId(UUID.randomUUID());

        assertThatThrownBy(() -> pinService.setInitialPin(user, bad))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a reasonable PIN is stored hashed, never in the clear")
    void storesPinHashed() {
        User user = new User();
        user.setId(UUID.randomUUID());

        pinService.setInitialPin(user, "4271");

        assertThat(user.getPinHash()).isNotNull().isNotEqualTo("4271");
        assertThat(encoder.matches("4271", user.getPinHash())).isTrue();
        assertThat(user.getPinUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("setting a PIN twice is refused; changing needs the current one")
    void cannotSetTwice() {
        User user = userWithPin("4271");

        assertThatThrownBy(() -> pinService.setInitialPin(user, "9182"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PIN_ALREADY_SET);
    }

    @Test
    @DisplayName("the correct PIN verifies and clears the failure counter")
    void verifiesCorrectPin() {
        User user = userWithPin("4271");
        user.setPinFailedAttempts(3);

        pinService.verify(user, "4271");

        assertThat(user.getPinFailedAttempts()).isZero();
        assertThat(user.getPinLockedUntil()).isNull();
    }

    @Test
    @DisplayName("wrong PINs count up and lock out on the Nth attempt")
    void locksOutAfterMaxAttempts() {
        User user = userWithPin("4271");

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            int expected = attempt;
            assertThatThrownBy(() -> pinService.verify(user, "0001"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ErrorCode.PIN_INVALID);
            assertThat(user.getPinFailedAttempts()).isEqualTo(expected);
        }

        // The final wrong attempt trips the lockout.
        assertThatThrownBy(() -> pinService.verify(user, "0001"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PIN_LOCKED);

        assertThat(user.isPinLocked()).isTrue();
    }

    @Test
    @DisplayName("while locked out, even the correct PIN is refused")
    void lockedOutRejectsCorrectPin() {
        User user = userWithPin("4271");
        user.setPinLockedUntil(Instant.now().plus(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> pinService.verify(user, "4271"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PIN_LOCKED);
    }

    @Test
    @DisplayName("an elapsed lockout lets the user back in")
    void expiredLockoutIsIgnored() {
        User user = userWithPin("4271");
        user.setPinLockedUntil(Instant.now().minus(Duration.ofMinutes(1)));

        pinService.verify(user, "4271");

        assertThat(user.getPinLockedUntil()).isNull();
    }

    @Test
    @DisplayName("resetting the PIN signs the account out everywhere")
    void resetRevokesAllSessions() {
        User user = userWithPin("4271");

        pinService.resetPin(user, "9182");

        assertThat(encoder.matches("9182", user.getPinHash())).isTrue();
        // A lost device must not stay logged in after a recovery reset.
        verify(refreshTokenRepository).revokeAllForUser(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("changing the PIN requires the current one")
    void changeRequiresCurrentPin() {
        User user = userWithPin("4271");

        assertThatThrownBy(() -> pinService.changePin(user, "0000", "9182"))
                .isInstanceOf(ApiException.class);

        pinService.changePin(user, "4271", "9182");
        assertThat(encoder.matches("9182", user.getPinHash())).isTrue();
    }
}