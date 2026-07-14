package com.agora.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Distributed rate-limiting knobs.
 *
 * @param enabled    kill-switch (env RL_ENABLED) — false bypasses the rate-limiter entirely
 * @param addr       rate-limiter gRPC target (env RATELIMIT_ADDR), plaintext
 * @param deadlineMs hard per-call deadline; past it we fail open
 * @param warnEvery  fail-open WARN log throttle: log the 1st failure, then every Nth
 */
@ConfigurationProperties("gateway.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("localhost:9095") String addr,
        @DefaultValue("50") long deadlineMs,
        @DefaultValue("100") long warnEvery) {
}
