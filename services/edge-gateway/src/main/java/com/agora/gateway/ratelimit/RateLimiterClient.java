package com.agora.gateway.ratelimit;

import com.agora.ratelimit.v1.CheckRequest;
import com.agora.ratelimit.v1.CheckResponse;
import com.agora.ratelimit.v1.RateLimiterGrpc;
import io.grpc.Channel;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

/**
 * Thin reactive wrapper over the generated async stub. A fresh deadline is set
 * per call (deadlines are absolute; a stub-level deadline would expire once).
 */
public class RateLimiterClient {

    private final RateLimiterGrpc.RateLimiterFutureStub stub;
    private final long deadlineMs;

    public RateLimiterClient(Channel channel, long deadlineMs) {
        this.stub = RateLimiterGrpc.newFutureStub(channel);
        this.deadlineMs = deadlineMs;
    }

    public Mono<CheckResponse> check(String key, String rule) {
        return Mono.create(sink -> {
            var future = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .check(CheckRequest.newBuilder().setKey(key).setRule(rule).build());
            future.addListener(() -> {
                try {
                    sink.success(future.get());
                } catch (Exception e) {
                    sink.error(e.getCause() != null ? e.getCause() : e);
                }
            }, Runnable::run);
        });
    }
}
