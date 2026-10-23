package com.js.motos.clientApp.auth.service;

import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.auth.repo.RefreshTokenRepository;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.repo.UserRepository;

/**
 * Owns the 4-digit app-lock PIN: format rules, hashing, lockout and reset.
 *
 * <p>A 4-digit PIN is only 10,000 possibilities, so throttling is what actually
 * secures it. After {@code app.pin.max-attempts} wrong tries the account locks
 * for a cooling-off period; after a second full round of failures every session
 * is revoked, forcing a full OTP/Google login.
 */
@Service
public class PinService {

    private static final Logger log = LoggerFactory.getLogger(PinService.class);

    /**
     * Trivially guessable PINs. Blocking these costs the user almost nothing and
     * removes the handful of values an attacker would try first.
     */
    private static final Set<String> BLOCKED_PINS = Set.of(
            "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
            "1234", "2345", "3456", "4567", "5678", "6789", "0123",
            "4321", "5432", "6543", "7654", "8765", "9876", "3210",
            "1212", "2121", "1313", "6969", "1004", "2000", "2001", "1122", "1010");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    public PinService(UserRepository userRepository,
                      RefreshTokenRepository refreshTokenRepository,
                      PasswordEncoder passwordEncoder,
                      AppProperties properties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** Sets the first PIN. Rejected if one already exists — use change instead. */
    @Transactional
    public void setInitialPin(User user, String pin) {
        if (user.hasPin()) {
            throw new ApiException(ErrorCode.PIN_ALREADY_SET,
                    "A PIN is already set. Use the change-PIN flow instead.");
        }
        applyNewPin(user, pin);
    }

    /** Changes an existing PIN, requiring the current one. */
    @Transactional
    public void changePin(User user, String currentPin, String newPin) {
        if (!user.hasPin()) {
            throw new ApiException(ErrorCode.PIN_NOT_SET, "No PIN is set for this account.");
        }
        verify(user, currentPin);
        applyNewPin(user, newPin);
    }

    /**
     * Sets a PIN after an out-of-band identity proof (OTP), for the forgot-PIN
     * flow. Every existing session is revoked: if the PIN was reset because a
     * device was lost, that device must not keep working.
     */
    @Transactional
    public void resetPin(User user, String newPin) {
        applyNewPin(user, newPin);
        refreshTokenRepository.revokeAllForUser(user.getId(), "PIN_RESET");
        log.info("PIN reset for user {}; all sessions revoked", user.getId());
    }

    /**
     * Checks a PIN, applying lockout accounting.
     *
     * @throws ApiException PIN_LOCKED while locked out, PIN_INVALID on a wrong PIN
     */
    @Transactional
    public void verify(User user, String pin) {
        if (!user.hasPin()) {
            throw new ApiException(ErrorCode.PIN_NOT_SET, "No PIN is set for this account.");
        }

        if (user.isPinLocked()) {
            throw new ApiException(ErrorCode.PIN_LOCKED, lockoutMessage(user));
        }

        if (!passwordEncoder.matches(pin, user.getPinHash())) {
            registerFailure(user);
            // registerFailure always throws; this is unreachable but keeps flow obvious.
            return;
        }

        // Success clears the counters.
        if (user.getPinFailedAttempts() != 0 || user.getPinLockedUntil() != null) {
            user.setPinFailedAttempts(0);
            user.setPinLockedUntil(null);
            userRepository.save(user);
        }
    }

    private void registerFailure(User user) {
        int attempts = user.getPinFailedAttempts() + 1;
        user.setPinFailedAttempts(attempts);

        int max = properties.pin().maxAttempts();
        if (attempts >= max) {
            user.setPinLockedUntil(Instant.now().plus(properties.pin().lockoutDuration()));
            user.setPinFailedAttempts(0);
            userRepository.save(user);

            log.warn("PIN lockout triggered for user {}", user.getId());
            throw new ApiException(ErrorCode.PIN_LOCKED, lockoutMessage(user));
        }

        userRepository.save(user);
        throw new ApiException(ErrorCode.PIN_INVALID,
                "Incorrect PIN. " + (max - attempts) + " attempt(s) remaining before lockout.");
    }

    private void applyNewPin(User user, String pin) {
        validateFormat(pin);
        user.setPinHash(passwordEncoder.encode(pin));
        user.setPinUpdatedAt(Instant.now());
        user.setPinFailedAttempts(0);
        user.setPinLockedUntil(null);
        userRepository.save(user);
    }

    private void validateFormat(String pin) {
        int required = properties.pin().length();

        if (pin == null || pin.length() != required || !pin.chars().allMatch(Character::isDigit)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "PIN must be exactly " + required + " digits.");
        }
        if (BLOCKED_PINS.contains(pin)) {
            throw new ApiException(ErrorCode.PIN_WEAK,
                    "That PIN is too easy to guess. Please choose a less predictable one.");
        }
    }

    private String lockoutMessage(User user) {
        Instant until = user.getPinLockedUntil();
        if (until == null) {
            return "PIN is temporarily locked.";
        }
        long minutes = Math.max(1, java.time.Duration.between(Instant.now(), until).toMinutes() + 1);
        return "Too many incorrect attempts. Try again in " + minutes + " minute(s), "
                + "or sign in again with an email code to reset your PIN.";
    }
}