package com.js.motos.clientApp.security;

import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.js.motos.clientApp.common.ApiError;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/otp/request",
            "/api/v1/auth/otp/verify",
            "/api/v1/auth/google",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/session/unlock",
            "/api/v1/auth/pin/reset/request",
            "/api/v1/auth/pin/reset/confirm",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // Dev-only local receipt serving; unused once Cloudinary is enabled.
            "/files/**"
    };

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless token API consumed by a mobile client: no cookies, no CSRF.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()

                        // Logging out and inspecting the session work at any trust level,
                        // so a locked client can always get itself unstuck.
                        .requestMatchers("/api/v1/auth/logout", "/api/v1/auth/session").authenticated()

                        // Setting the very first PIN is the one action a PIN_SETUP token allows.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/pin/setup")
                        .hasAnyAuthority(TokenScope.PIN_SETUP.authority(), TokenScope.FULL.authority())

                        // Everything else demands a PIN-verified token.
                        .requestMatchers("/api/v1/admin/**")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                authentication.get() != null
                                        && authentication.get().getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                        && authentication.get().getAuthorities().contains(new SimpleGrantedAuthority(TokenScope.FULL.authority()))))

                        .anyRequest().hasAuthority(TokenScope.FULL.authority()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(this::writeUnauthenticated)
                        .accessDeniedHandler(this::writeForbidden))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthenticated)
                        .accessDeniedHandler(this::writeForbidden));

        return http.build();
    }

    /**
     * Maps {@code role} and {@code scp} claims onto {@code ROLE_*} / {@code SCOPE_*}
     * authorities so both can be expressed in the matcher rules above.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("SCOPE_");
        scopes.setAuthoritiesClaimName(JwtService.CLAIM_SCOPE);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new java.util.ArrayList<>(scopes.convert(jwt));
            String role = jwt.getClaimAsString(JwtService.CLAIM_ROLE);
            if (role != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    public SecretKey jwtSecretKey() {
        String secret = properties.jwt().secret();
        byte[] keyBytes = decodeSecret(secret);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes for HS256. "
                            + "Generate one with: openssl rand -base64 48");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /** Accepts either a base64 secret or a long raw string. */
    private static byte[] decodeSecret(String secret) {
        try {
            return Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** BCrypt for PINs and OTPs — both are low-entropy and need a slow hash. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = properties.security().corsAllowedOrigins();
        if (origins.contains("*")) {
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // --- error rendering, so security failures use the same envelope ------

    private void writeUnauthenticated(jakarta.servlet.http.HttpServletRequest request,
                                      jakarta.servlet.http.HttpServletResponse response,
                                      RuntimeException ex) throws java.io.IOException {
        write(response, request, ErrorCode.UNAUTHENTICATED, "Authentication required or token invalid");
    }

    private void writeForbidden(jakarta.servlet.http.HttpServletRequest request,
                                jakarta.servlet.http.HttpServletResponse response,
                                RuntimeException ex) throws java.io.IOException {
        // A valid-but-under-scoped token almost always means "enter your PIN".
        boolean pinIssue = isAuthenticatedWithoutFullScope();
        ErrorCode code = pinIssue ? ErrorCode.PIN_REQUIRED : ErrorCode.FORBIDDEN;
        String message = pinIssue
                ? "PIN verification required to access this resource"
                : "You do not have access to this resource";
        write(response, request, code, message);
    }

    private boolean isAuthenticatedWithoutFullScope() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !authentication.getAuthorities()
                        .contains(new SimpleGrantedAuthority(TokenScope.FULL.authority()));
    }

    private void write(jakarta.servlet.http.HttpServletResponse response,
                       jakarta.servlet.http.HttpServletRequest request,
                       ErrorCode code,
                       String message) throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(code, message, request.getRequestURI(), java.util.Map.of()));
    }
}