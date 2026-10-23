package com.js.motos.clientApp.security;

import java.util.UUID;

import com.js.motos.clientApp.user.domain.UserRole;

/**
 * The authenticated caller, decoded from the access token. Injected into
 * controller methods as a parameter — see {@link CurrentUserArgumentResolver}.
 *
 * @param userId    the acting user
 * @param email     their email at token-issue time
 * @param role      USER or ADMIN
 * @param scope     how far this particular token is trusted
 * @param sessionId the refresh-token row this access token descends from
 */
public record AuthPrincipal(
        UUID userId,
        String email,
        UserRole role,
        TokenScope scope,
        UUID sessionId) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean hasFullAccess() {
        return scope == TokenScope.FULL;
    }
}