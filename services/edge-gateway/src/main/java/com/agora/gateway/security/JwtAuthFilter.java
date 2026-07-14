package com.agora.gateway.security;

import com.agora.gateway.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Edge JWT validation: verifies HS256 locally with the shared secret — no
 * network hop to identity-service per request. Trade-off (ADR-002): a revoked
 * user stays valid until token expiry (1h); the mitigation is short TTLs.
 *
 * Always strips inbound X-User-* headers (spoofing defense) and re-adds them
 * only from a verified token.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    public static final int ORDER = -100; // before rate limiting (user id becomes the RL key)
    public static final String USER_ID = "X-User-Id";
    public static final String USER_NAME = "X-User-Name";

    private final AuthProperties props;
    private final SecretKey key;

    public JwtAuthFilter(AuthProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(USER_ID);
                    h.remove(USER_NAME);
                })
                .build();
        exchange = exchange.mutate().request(request).build();

        String token = bearer(request.getHeaders());
        if (token != null) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(token).getPayload();
                String uid = String.valueOf(claims.getOrDefault("uid", claims.getSubject()));
                ServerHttpRequest authed = request.mutate()
                        .header(USER_ID, uid)
                        .header(USER_NAME, claims.getSubject())
                        .build();
                return chain.filter(exchange.mutate().request(authed).build());
            } catch (JwtException | IllegalArgumentException e) {
                return reject(exchange); // present-but-invalid is always a 401
            }
        }

        if (requiresAuth(exchange)) {
            return reject(exchange);
        }
        return chain.filter(exchange);
    }

    private boolean requiresAuth(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || !props.protectedRouteIds().contains(route.getId())) {
            return false;
        }
        return !props.publicMethods().contains(exchange.getRequest().getMethod().name());
    }

    private static String bearer(HttpHeaders headers) {
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
