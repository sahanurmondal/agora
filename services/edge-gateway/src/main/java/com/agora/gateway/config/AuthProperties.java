package com.agora.gateway.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT edge-authentication knobs.
 *
 * @param secret            HS256 secret shared with identity-service (env JWT_SECRET)
 * @param protectedRouteIds gateway route ids that require a valid JWT
 * @param publicMethods     HTTP methods exempt from the JWT check on protected routes
 *                          (reads stay public; mutations need identity)
 */
@ConfigurationProperties("gateway.auth")
public record AuthProperties(
        String secret,
        @DefaultValue("links-api") Set<String> protectedRouteIds,
        @DefaultValue({"GET", "HEAD", "OPTIONS"}) Set<String> publicMethods) {
}
