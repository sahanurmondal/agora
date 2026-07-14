package com.agora.gateway.ratelimit;

import com.agora.gateway.config.RateLimitProperties;
import com.agora.ratelimit.v1.CheckRequest;
import com.agora.ratelimit.v1.CheckResponse;
import com.agora.ratelimit.v1.RateLimiterGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private Server server;
    private ManagedChannel channel;

    private RateLimitFilter filterBackedBy(RateLimiterGrpc.RateLimiterImplBase impl) throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new RateLimitFilter(new RateLimiterClient(channel, 1000),
                new RateLimitProperties(true, "in-process", 1000, 100));
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void deniedRequestGets429WithRetryAfter() throws IOException {
        RateLimitFilter filter = filterBackedBy(new RateLimiterGrpc.RateLimiterImplBase() {
            @Override
            public void check(CheckRequest req, StreamObserver<CheckResponse> obs) {
                obs.onNext(CheckResponse.newBuilder()
                        .setAllowed(false).setRemaining(0).setRetryAfterMs(1500).build());
                obs.onCompleted();
            }
        });
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        AtomicBoolean chained = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> {
            chained.set(true);
            return Mono.empty();
        };

        filter.filter(ex, chain).block();

        assertFalse(chained.get());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getResponse().getStatusCode());
        assertEquals("2", ex.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals("0", ex.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    void limiterOutageFailsOpen() throws IOException {
        RateLimitFilter filter = filterBackedBy(new RateLimiterGrpc.RateLimiterImplBase() {
            @Override
            public void check(CheckRequest req, StreamObserver<CheckResponse> obs) {
                obs.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        });
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        AtomicBoolean chained = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> {
            chained.set(true);
            return Mono.empty();
        };

        filter.filter(ex, chain).block();

        assertTrue(chained.get(), "must fail OPEN when limiter is down");
        assertNull(ex.getResponse().getStatusCode());
    }
}
