package com.js.motos.clientApp.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi(AppProperties properties) {
        final String schemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title(properties.brandName() + " API")
                        .version("v1")
                        .description("""
                                Backend for the QR cashback Android app.

                                **Auth model**
                                1. `POST /auth/otp/request` then `/auth/otp/verify` (or `/auth/google`) \
                                returns an access token plus a long-lived refresh token.
                                2. If the response scope is `PIN_SETUP`, call `/auth/pin/setup` \
                                to choose a 4-digit PIN.
                                3. On cold start, call `/auth/session/unlock` with the refresh token \
                                and the PIN to get a `FULL` access token.
                                4. `/auth/token/refresh` renews silently while the PIN grace window \
                                is open, and returns a `PIN_REQUIRED` token once it lapses.
                                5. `/auth/logout` revokes the session — after that the PIN alone \
                                cannot restore access.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(schemeName))
                .components(new Components().addSecuritySchemes(schemeName,
                        new SecurityScheme()
                                .name(schemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}