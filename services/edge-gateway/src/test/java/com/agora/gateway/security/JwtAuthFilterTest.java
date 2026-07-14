package com.agora.gateway.security;

import com.agora.gateway.config.AuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthFilterTest {

    private static final String SECRET = "agora-local-dev-secret-change-me-0123456789abcdef";

    private final JwtAuthFilter filter = new JwtAuthFilter(
            new AuthProperties(SECRET, Set.of("links-api"), Set.of("GET", "HEAD", "OPTIONS")));

    private static String token(String user, long uid) {
        return Jwts.builder()
                .subject(user)
                .claim("uid", uid)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest req, String routeId) {
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        Route route = Route.async().id(routeId).uri(URI.create("http://upstream"))
                .predicate(e -> true).build();
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return ex;
    }

    private AtomicReference<ServerWebExchange> runChain(MockServerWebExchange ex) {
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
        GatewayFilterChain chain = e -> {
            passed.set(e);
            return Mono.empty();
        };
        filter.filter(ex, chain).block();
        return passed;
    }

    @Test
    void postToProtectedRouteWithoutTokenIs401() {
        MockServerWebExchange ex = exchange(MockServerHttpRequest.post("/api/v1/links").build(), "links-api");
        assertNull(runChain(ex).get());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    @Test
    void validTokenPassesAndSetsUserHeaders() {
        MockServerWebExchange ex = exchange(
                MockServerHttpRequest.post("/api/v1/links")
                        .header("Authorization", "Bearer " + token("alice", 42))
                        .header(JwtAuthFilter.USER_ID, "666") // spoof attempt: must be stripped
                        .build(),
                "links-api");
        ServerWebExchange passed = runChain(ex).get();
        assertNotNull(passed);
        assertEquals("42", passed.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_ID));
        assertEquals("alice", passed.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_NAME));
    }

    @Test
    void garbageTokenIs401EvenOnPublicMethod() {
        MockServerWebExchange ex = exchange(
                MockServerHttpRequest.get("/api/v1/links/abc")
                        .header("Authorization", "Bearer not.a.jwt").build(),
                "links-api");
        assertNull(runChain(ex).get());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    @Test
    void getWithoutTokenPassesOnProtectedRoute() {
        MockServerWebExchange ex = exchange(MockServerHttpRequest.get("/api/v1/links/abc").build(), "links-api");
        assertNotNull(runChain(ex).get());
    }

    @Test
    void spoofedHeadersStrippedWhenAnonymous() {
        MockServerWebExchange ex = exchange(
                MockServerHttpRequest.get("/api/v1/links/abc").header(JwtAuthFilter.USER_ID, "666").build(),
                "links-api");
        ServerWebExchange passed = runChain(ex).get();
        assertNull(passed.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_ID));
    }
}
