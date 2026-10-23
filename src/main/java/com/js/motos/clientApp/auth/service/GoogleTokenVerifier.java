package com.js.motos.clientApp.auth.service;

import java.util.List;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;

/**
 * Validates Google Sign-In ID tokens sent up by the Android client.
 *
 * <p>The library checks the RS256 signature against Google's rotating public
 * keys, the issuer, and expiry. We additionally require the audience to be one
 * of our own OAuth client IDs — without that check, an ID token minted for any
 * other Google app would be accepted here.
 */
@Service
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);

    private final GoogleIdTokenVerifier verifier;
    private final boolean configured;

    public GoogleTokenVerifier(AppProperties properties) {
        List<String> clientIds = properties.google().clientIds().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        this.configured = !clientIds.isEmpty();
        if (!configured) {
            log.warn("app.google.client-ids is empty — Google Sign-In will be rejected until it is set.");
            this.verifier = null;
        } else {
            this.verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(clientIds)
                    .build();
        }
    }

    /**
     * @return the verified identity carried by the token
     * @throws ApiException if the token is malformed, expired, wrongly audienced,
     *                      or carries an unverified email address
     */
    public GoogleIdentity verify(String idTokenString) {
        if (!configured) {
            throw new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID,
                    "Google Sign-In is not configured on this server.");
        }

        GoogleIdToken token;
        try {
            token = verifier.verify(idTokenString);
        } catch (Exception e) {
            log.debug("Google ID token verification threw", e);
            throw new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID, "Could not verify Google token.");
        }

        if (token == null) {
            throw new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID,
                    "Google token is invalid or expired.");
        }

        GoogleIdToken.Payload payload = token.getPayload();
        String email = payload.getEmail();

        if (email == null || !Boolean.TRUE.equals(payload.getEmailVerified())) {
            // We key accounts on email, so an unverified Google email would let
            // someone claim an address they do not own.
            throw new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID,
                    "Your Google account email is not verified.");
        }

        return new GoogleIdentity(
                payload.getSubject(),
                email.toLowerCase(),
                (String) payload.get("name"),
                (String) payload.get("picture"));
    }

    public record GoogleIdentity(String subject, String email, String name, String pictureUrl) {
    }
}