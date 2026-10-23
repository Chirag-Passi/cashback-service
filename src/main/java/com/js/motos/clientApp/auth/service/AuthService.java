package com.js.motos.clientApp.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.audit.AuditService;
import com.js.motos.clientApp.auth.domain.OtpPurpose;
import com.js.motos.clientApp.auth.domain.RefreshToken;
import com.js.motos.clientApp.auth.repo.RefreshTokenRepository;
import com.js.motos.clientApp.auth.web.dto.AuthDtos;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.Codes;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;
import com.js.motos.clientApp.security.JwtService;
import com.js.motos.clientApp.security.TokenScope;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.domain.UserStatus;
import com.js.motos.clientApp.user.repo.UserRepository;

/**
 * Ties together login, the PIN gate, and device sessions.
 *
 * <h2>Session model</h2>
 * <ul>
 *   <li>Interactive login (OTP or Google) creates a refresh-token row and counts
 *       as PIN-verified — the user just proved identity by a stronger means.</li>
 *   <li>While the app is in use, {@code /token/refresh} silently mints access
 *       tokens. Once the PIN grace window lapses those tokens drop to
 *       {@code PIN_REQUIRED}, and the client must call {@code /session/unlock}.</li>
 *   <li>Logout revokes the row, after which the PIN can mint nothing at all.</li>
 * </ul>
 *
 * <p>Refresh tokens are intentionally not rotated: without the PIN a stolen one
 * yields only {@code PIN_REQUIRED} tokens, and rotation would risk stranding
 * clients that lose the response to a refresh call.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OtpService otpService;
    private final PinService pinService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final AppProperties properties;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       OtpService otpService,
                       PinService pinService,
                       GoogleTokenVerifier googleTokenVerifier,
                       JwtService jwtService,
                       AuditService auditService,
                       AppProperties properties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.otpService = otpService;
        this.pinService = pinService;
        this.googleTokenVerifier = googleTokenVerifier;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.properties = properties;
    }

    // =====================================================================
    // Login
    // =====================================================================

    /** Step 1 of email login: send a code. Never reveals whether the email exists. */
    public Optional<String> requestLoginOtp(String email, String ip) {
        return otpService.request(email, OtpPurpose.LOGIN, ip);
    }

    /** Step 2 of email login: exchange a valid code for a session. */
    @Transactional
    public AuthDtos.Session loginWithOtp(String email, String code, DeviceInfo device) {
        otpService.verify(email, OtpPurpose.LOGIN, code);

        String normalized = OtpService.normalize(email);
        User user = userRepository.findByEmailIgnoreCase(normalized)
                .orElseGet(() -> registerUser(normalized, null, null, null));

        // Completing an emailed code is itself proof the address is real.
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
        }

        return startSession(user, device, "OTP");
    }

    /** Google Sign-In: verify the ID token, then create or link the account. */
    @Transactional
    public AuthDtos.Session loginWithGoogle(String idToken, DeviceInfo device) {
        GoogleTokenVerifier.GoogleIdentity identity = googleTokenVerifier.verify(idToken);

        User user = userRepository.findByGoogleSubject(identity.subject())
                .or(() -> userRepository.findByEmailIgnoreCase(identity.email()))
                .orElseGet(() -> registerUser(identity.email(), identity.subject(),
                        identity.name(), identity.pictureUrl()));

        // Link Google to an account that started life as an OTP-only login.
        if (user.getGoogleSubject() == null) {
            user.setGoogleSubject(identity.subject());
        }
        // Google already proved the address; fill gaps without clobbering edits.
        user.setEmailVerified(true);
        if (user.getFullName() == null && identity.name() != null) {
            user.setFullName(identity.name());
        }
        if (user.getAvatarUrl() == null && identity.pictureUrl() != null) {
            user.setAvatarUrl(identity.pictureUrl());
        }

        return startSession(user, device, "GOOGLE");
    }

    private User registerUser(String email, String googleSubject, String name, String avatarUrl) {
        User user = new User();
        user.setEmail(email);
        user.setGoogleSubject(googleSubject);
        user.setFullName(name);
        user.setAvatarUrl(avatarUrl);
        user.setEmailVerified(true);
        User saved = userRepository.save(user);
        log.info("Registered new user {}", saved.getId());
        auditService.record(saved.getId(), "USER_REGISTERED", "User", saved.getId(),
                Map.of("via", googleSubject != null ? "GOOGLE" : "OTP"));
        return saved;
    }

    // =====================================================================
    // Session lifecycle
    // =====================================================================

    private AuthDtos.Session startSession(User user, DeviceInfo device, String method) {
        requireActive(user);

        String rawRefreshToken = Codes.refreshToken();
        RefreshToken session = new RefreshToken();
        session.setUser(user);
        session.setTokenHash(Codes.sha256Hex(rawRefreshToken));
        session.setDeviceId(device.deviceId());
        session.setDeviceName(device.deviceName());
        session.setUserAgent(device.userAgent());
        session.setIp(device.ip());
        session.setExpiresAt(Instant.now().plus(properties.refresh().ttl()));
        // A fresh interactive login is at least as strong as the PIN.
        session.setPinVerifiedAt(Instant.now());
        session.setLastUsedAt(Instant.now());

        enforceSessionLimit(user);
        refreshTokenRepository.save(session);

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        auditService.record(user.getId(), "LOGIN", "User", user.getId(),
                Map.of("method", method, "sessionId", session.getId().toString()), device.ip());

        TokenScope scope = user.hasPin() ? TokenScope.FULL : TokenScope.PIN_SETUP;
        return buildSession(user, session, scope, rawRefreshToken);
    }

    /** Keeps the newest N devices; the oldest sessions are revoked beyond that. */
    private void enforceSessionLimit(User user) {
        List<RefreshToken> active = refreshTokenRepository.findActiveForUser(user.getId());
        int limit = properties.refresh().maxSessionsPerUser();
        int excess = active.size() - (limit - 1);
        for (int i = 0; i < excess && i < active.size(); i++) {
            active.get(i).revoke("SESSION_LIMIT");
        }
    }

    /**
     * Silent token renewal. Returns a downgraded {@code PIN_REQUIRED} token once
     * the PIN grace window has lapsed, which is what forces the app-lock screen.
     */
    @Transactional
    public AuthDtos.Session refresh(String rawRefreshToken) {
        RefreshToken session = loadActiveSession(rawRefreshToken);
        User user = session.getUser();
        requireActive(user);

        session.setLastUsedAt(Instant.now());

        TokenScope scope;
        if (!user.hasPin()) {
            scope = TokenScope.PIN_SETUP;
        } else if (session.isPinFresh(properties.pin().sessionTtl())) {
            scope = TokenScope.FULL;
        } else {
            scope = TokenScope.PIN_REQUIRED;
        }

        // Refresh token is not rotated — the client keeps the one it already holds.
        return buildSession(user, session, scope, null);
    }

    /** The app-lock screen: PIN in, full-access token out. */
    @Transactional
    public AuthDtos.Session unlock(String rawRefreshToken, String pin) {
        RefreshToken session = loadActiveSession(rawRefreshToken);
        User user = session.getUser();
        requireActive(user);

        if (!user.hasPin()) {
            throw new ApiException(ErrorCode.PIN_NOT_SET,
                    "No PIN is set for this account. Complete PIN setup first.");
        }

        pinService.verify(user, pin);

        session.setPinVerifiedAt(Instant.now());
        session.setLastUsedAt(Instant.now());

        return buildSession(user, session, TokenScope.FULL, null);
    }

    /** Sets the first PIN and upgrades the calling session to full access. */
    @Transactional
    public AuthDtos.Session setInitialPin(UUID userId, UUID sessionId, String pin) {
        User user = loadUser(userId);
        pinService.setInitialPin(user, pin);

        RefreshToken session = touchPinVerified(sessionId);
        auditService.record(userId, "PIN_SET", "User", userId, Map.of());

        return buildSession(user, session, TokenScope.FULL, null);
    }

    @Transactional
    public void changePin(UUID userId, UUID sessionId, String currentPin, String newPin) {
        User user = loadUser(userId);
        pinService.changePin(user, currentPin, newPin);
        touchPinVerified(sessionId);
        auditService.record(userId, "PIN_CHANGED", "User", userId, Map.of());
    }

    /** Forgot-PIN step 1 — reuses the email OTP channel. */
    public Optional<String> requestPinReset(String email, String ip) {
        return otpService.request(email, OtpPurpose.PIN_RESET, ip);
    }

    /**
     * Forgot-PIN step 2. Succeeds only with a valid code, and deliberately revokes
     * every session so a lost device cannot keep its logged-in state.
     */
    @Transactional
    public void confirmPinReset(String email, String code, String newPin) {
        otpService.verify(email, OtpPurpose.PIN_RESET, code);

        User user = userRepository.findByEmailIgnoreCase(OtpService.normalize(email))
                .orElseThrow(() -> new ApiException(ErrorCode.OTP_INVALID, "Invalid code."));
        requireActive(user);

        pinService.resetPin(user, newPin);
        auditService.record(user.getId(), "PIN_RESET", "User", user.getId(), Map.of());
    }

    @Transactional
    public void logout(UUID userId, UUID sessionId, String rawRefreshToken, boolean allDevices) {
        if (allDevices) {
            int revoked = refreshTokenRepository.revokeAllForUser(userId, "LOGOUT_ALL");
            auditService.record(userId, "LOGOUT_ALL", "User", userId, Map.of("sessions", revoked));
            return;
        }

        // Prefer the token in the body; fall back to the session in the JWT.
        Optional<RefreshToken> target = Optional.empty();
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            target = refreshTokenRepository.findByTokenHash(Codes.sha256Hex(rawRefreshToken));
        } else if (sessionId != null) {
            target = refreshTokenRepository.findById(sessionId);
        }

        target.filter(session -> session.getUser().getId().equals(userId))
                .ifPresent(session -> {
                    session.revoke("LOGOUT");
                    auditService.record(userId, "LOGOUT", "User", userId,
                            Map.of("sessionId", session.getId().toString()));
                });
    }

    @Transactional(readOnly = true)
    public List<AuthDtos.DeviceSession> listSessions(UUID userId, UUID currentSessionId) {
        return refreshTokenRepository.findActiveForUser(userId).stream()
                .map(session -> new AuthDtos.DeviceSession(
                        session.getId(),
                        session.getDeviceName(),
                        session.getIp(),
                        session.getIssuedAt(),
                        session.getLastUsedAt(),
                        session.getExpiresAt(),
                        session.getId().equals(currentSessionId)))
                .toList();
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        RefreshToken session = refreshTokenRepository.findById(sessionId)
                .filter(s -> s.getUser().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Session"));
        session.revoke("REVOKED_BY_USER");
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private RefreshToken loadActiveSession(String rawRefreshToken) {
        RefreshToken session = refreshTokenRepository
                .findByTokenHash(Codes.sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_INVALID,
                        "Session not found. Please sign in again."));

        if (session.isRevoked()) {
            throw new ApiException(ErrorCode.SESSION_INVALID,
                    "You have been signed out. Please sign in again.");
        }
        if (session.isExpired()) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED,
                    "Your session has expired. Please sign in again.");
        }
        return session;
    }

    private RefreshToken touchPinVerified(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        return refreshTokenRepository.findById(sessionId)
                .map(session -> {
                    session.setPinVerifiedAt(Instant.now());
                    return session;
                })
                .orElse(null);
    }

    private User loadUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User"));
        requireActive(user);
        return user;
    }

    private void requireActive(User user) {
        if (user.getStatus() == UserStatus.BLOCKED) {
            throw new ApiException(ErrorCode.ACCOUNT_BLOCKED,
                    "This account has been blocked. Contact support.");
        }
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.ACCOUNT_BLOCKED, "This account no longer exists.");
        }
    }

    private AuthDtos.Session buildSession(User user, RefreshToken session,
                                          TokenScope scope, String rawRefreshToken) {
        UUID sessionId = session == null ? null : session.getId();
        JwtService.IssuedToken token = jwtService.issue(user, scope, sessionId);

        return new AuthDtos.Session(
                token.value(),
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                token.expiresAt(),
                scope,
                rawRefreshToken,
                user.hasPin(),
                toSummary(user));
    }

    public static AuthDtos.UserSummary toSummary(User user) {
        return new AuthDtos.UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getAvatarUrl(),
                user.getRole(),
                user.isEmailVerified());
    }
}