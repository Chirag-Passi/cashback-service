package com.js.motos.clientApp.security;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.js.motos.clientApp.config.AppProperties;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.domain.UserRole;

/**
 * Mints and reads the short-lived HS256 access tokens.
 *
 * <p>Access tokens are deliberately stateless and short-lived; all revocation
 * happens against the refresh-token row instead.
 */
@Service
public class JwtService {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_SCOPE = "scp";
    public static final String CLAIM_SESSION = "sid";

    private final JwtEncoder encoder;
    private final AppProperties properties;

    public JwtService(JwtEncoder encoder, AppProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * @param sessionId the refresh-token row this token belongs to, or null for
     *                  tokens minted outside a device session
     */
    public IssuedToken issue(User user, TokenScope scope, UUID sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_SCOPE, scope.name());

        if (sessionId != null) {
            claims.claim(CLAIM_SESSION, sessionId.toString());
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();

        return new IssuedToken(value, expiresAt, scope);
    }

    /** Rebuilds the caller identity from an already-validated token. */
    public static AuthPrincipal toPrincipal(Jwt jwt) {
        String sessionId = jwt.getClaimAsString(CLAIM_SESSION);
        return new AuthPrincipal(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(CLAIM_EMAIL),
                UserRole.valueOf(jwt.getClaimAsString(CLAIM_ROLE)),
                TokenScope.valueOf(jwt.getClaimAsString(CLAIM_SCOPE)),
                sessionId == null ? null : UUID.fromString(sessionId));
    }

    public long accessTokenTtlSeconds() {
        return properties.jwt().accessTokenTtl().toSeconds();
    }

    public record IssuedToken(String value, Instant expiresAt, TokenScope scope) {
    }
}