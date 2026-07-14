package com.agora.gateway.ratelimit;

import com.agora.gateway.config.RateLimitProperties;
import com.agora.gateway.security.JwtAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distributed rate limiting at the edge: every routed request asks the
 * rate-limiter service (gRPC, hard deadline) and answers 429 with Retry-After
 * when denied.
 *
 * FAIL OPEN by design (ADR-001): if the limiter is slow or down we allow the
 * request, count it, and WARN (throttled). An unavailable rate limiter must
 * degrade the *protection*, not the *product* — the inverse choice (fail
 * closed) turns a limiter outage into a full outage.
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

    public static final int ORDER = JwtAuthFilter.ORDER + 50; // after JWT: user id keys the limit

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiterClient client;
    private final RateLimitProperties props;
    private final AtomicLong failOpenCount = new AtomicLong();

    public RateLimitFilter(RateLimiterClient client, RateLimitProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.enabled()) {
            return chain.filter(exchange);
        }
        String key = limitKey(exchange);
        String rule = Optional.<Route>ofNullable(
                        exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR))
                .map(Route::getId).orElse("default");

        return client.check(key, rule)
                .flatMap(resp -> {
                    if (resp.getAllowed()) {
                        return chain.filter(exchange);
                    }
                    var response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    long retryAfterSec = Math.max(1, (resp.getRetryAfterMs() + 999) / 1000);
                    response.getHeaders().add("Retry-After", String.valueOf(retryAfterSec));
                    response.getHeaders().add("X-RateLimit-Remaining", String.valueOf(resp.getRemaining()));
                    return response.setComplete();
                })
                .onErrorResume(e -> {
                    long n = failOpenCount.incrementAndGet();
                    if (n == 1 || n % props.warnEvery() == 0) {
                        log.warn("rate-limiter unavailable, failing OPEN (count={}): {}", n, e.toString());
                    }
                    return chain.filter(exchange);
                });
    }

    private static String limitKey(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_ID);
        if (userId != null) {
            return "u:" + userId;
        }
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return "ip:" + xff.split(",")[0].trim();
        }
        return "ip:" + Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getHostString).orElse("unknown");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
