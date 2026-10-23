package com.js.motos.clientApp.auth.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.js.motos.clientApp.auth.service.AuthService;
import com.js.motos.clientApp.auth.service.DeviceInfo;
import com.js.motos.clientApp.auth.web.dto.AuthDtos;
import com.js.motos.clientApp.common.RequestUtils;
import com.js.motos.clientApp.config.AppProperties;
import com.js.motos.clientApp.security.AuthPrincipal;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Email OTP login, Google Sign-In, PIN app-lock and sessions")
public class AuthController {

    private final AuthService authService;
    private final AppProperties properties;

    public AuthController(AuthService authService, AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    // --- email OTP login --------------------------------------------------

    @PostMapping("/otp/request")
    @Operation(summary = "Send a login code by email",
            description = "Always returns 200 for a well-formed address so the endpoint "
                    + "cannot be used to discover which emails are registered.")
    public AuthDtos.OtpRequested requestOtp(@Valid @RequestBody AuthDtos.RequestOtp request,
                                            HttpServletRequest httpRequest) {
        String devCode = authService
                .requestLoginOtp(request.email(), RequestUtils.clientIp(httpRequest))
                .orElse(null);

        return new AuthDtos.OtpRequested(
                "If that email is valid, a code is on its way.",
                properties.otp().ttl().toSeconds(),
                devCode);
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Exchange an email code for a session",
            description = "Creates the account on first use. If the response scope is "
                    + "PIN_SETUP, send the user to the create-PIN screen next.")
    public AuthDtos.Session verifyOtp(@Valid @RequestBody AuthDtos.VerifyOtp request,
                                      HttpServletRequest httpRequest) {
        DeviceInfo device = DeviceInfo.from(httpRequest, request.deviceId(), request.deviceName());
        return authService.loginWithOtp(request.email(), request.code(), device);
    }

    // --- Google Sign-In ---------------------------------------------------

    @PostMapping("/google")
    @Operation(summary = "Sign in with a Google ID token",
            description = "Links to an existing account when the verified Google email "
                    + "already belongs to one.")
    public AuthDtos.Session googleLogin(@Valid @RequestBody AuthDtos.GoogleLogin request,
                                        HttpServletRequest httpRequest) {
        DeviceInfo device = DeviceInfo.from(httpRequest, request.deviceId(), request.deviceName());
        return authService.loginWithGoogle(request.idToken(), device);
    }

    // --- token lifecycle --------------------------------------------------

    @PostMapping("/token/refresh")
    @Operation(summary = "Renew the access token",
            description = "Returns a FULL token while the PIN grace window is open, "
                    + "otherwise a PIN_REQUIRED token — prompt for the PIN when you see it.")
    public AuthDtos.Session refresh(@Valid @RequestBody AuthDtos.Refresh request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/session/unlock")
    @Operation(summary = "Unlock the app with the 4-digit PIN",
            description = "Called on cold start or when a refresh returns PIN_REQUIRED.")
    public AuthDtos.Session unlock(@Valid @RequestBody AuthDtos.Unlock request) {
        return authService.unlock(request.refreshToken(), request.pin());
    }

    // --- PIN management ---------------------------------------------------

    @PostMapping("/pin/setup")
    @Operation(summary = "Set the initial PIN",
            description = "The only action a PIN_SETUP-scoped token may perform. "
                    + "Returns an upgraded FULL token.")
    public AuthDtos.Session setupPin(AuthPrincipal principal,
                                     @Valid @RequestBody AuthDtos.SetPin request) {
        return authService.setInitialPin(principal.userId(), principal.sessionId(), request.pin());
    }

    @PostMapping("/pin/change")
    @Operation(summary = "Change the PIN using the current one")
    public AuthDtos.MessageResponse changePin(AuthPrincipal principal,
                                              @Valid @RequestBody AuthDtos.ChangePin request) {
        authService.changePin(principal.userId(), principal.sessionId(),
                request.currentPin(), request.newPin());
        return new AuthDtos.MessageResponse("PIN updated.");
    }

    @PostMapping("/pin/reset/request")
    @Operation(summary = "Forgot PIN — send a reset code by email")
    public AuthDtos.OtpRequested requestPinReset(@Valid @RequestBody AuthDtos.ResetPinRequest request,
                                                 HttpServletRequest httpRequest) {
        String devCode = authService
                .requestPinReset(request.email(), RequestUtils.clientIp(httpRequest))
                .orElse(null);

        return new AuthDtos.OtpRequested(
                "If that email is valid, a reset code is on its way.",
                properties.otp().ttl().toSeconds(),
                devCode);
    }

    @PostMapping("/pin/reset/confirm")
    @Operation(summary = "Forgot PIN — set a new one with the emailed code",
            description = "Signs the account out of every device, then requires a fresh login.")
    public AuthDtos.MessageResponse confirmPinReset(@Valid @RequestBody AuthDtos.ResetPinConfirm request) {
        authService.confirmPinReset(request.email(), request.code(), request.newPin());
        return new AuthDtos.MessageResponse("PIN reset. Please sign in again.");
    }

    // --- sessions ---------------------------------------------------------

    @PostMapping("/logout")
    @Operation(summary = "Log out",
            description = "Revokes the session so the PIN alone can no longer restore access. "
                    + "Set allDevices to sign out everywhere.")
    public AuthDtos.MessageResponse logout(AuthPrincipal principal,
                                           @RequestBody(required = false) AuthDtos.Logout request) {
        boolean allDevices = request != null && request.allDevices();
        String refreshToken = request == null ? null : request.refreshToken();

        authService.logout(principal.userId(), principal.sessionId(), refreshToken, allDevices);
        return new AuthDtos.MessageResponse(allDevices ? "Signed out of all devices." : "Signed out.");
    }

    @GetMapping("/session")
    @Operation(summary = "List the devices currently signed in")
    public List<AuthDtos.DeviceSession> sessions(AuthPrincipal principal) {
        return authService.listSessions(principal.userId(), principal.sessionId());
    }

    @DeleteMapping("/session/{sessionId}")
    @Operation(summary = "Sign a specific device out")
    public ResponseEntity<Void> revokeSession(AuthPrincipal principal,
                                              @PathVariable UUID sessionId) {
        authService.revokeSession(principal.userId(), sessionId);
        return ResponseEntity.noContent().build();
    }
}