package com.agora.platform.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Idempotency-Key filter for mutating endpoints: a retried POST with the same
 * key replays the stored first response instead of re-executing the handler.
 * This is what makes client retries safe on create/payment APIs — "what if the
 * request arrives twice?" answered at the edge of the service.
 *
 * Concurrency: {@link IdempotencyStore#tryBegin} must be atomic (INSERT with
 * PK conflict). A concurrent duplicate while the first request is still
 * in-flight gets 409 IN_PROGRESS and should retry after a beat.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        String scope = req.getRequestURI();

        Optional<IdempotencyStore.StoredResponse> replay = store.find(scope, key);
        if (replay.isPresent()) {
            IdempotencyStore.StoredResponse r = replay.get();
            if (r.inProgress()) {
                res.setStatus(409);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"request with this Idempotency-Key is in progress\"}");
                return;
            }
            res.setStatus(r.status());
            res.setContentType(r.contentType());
            res.setHeader("X-Idempotent-Replay", "true");
            res.getWriter().write(r.body());
            return;
        }

        if (!store.tryBegin(scope, key)) {
            // Lost the race to a concurrent duplicate.
            res.setStatus(409);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"request with this Idempotency-Key is in progress\"}");
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        try {
            chain.doFilter(req, wrapped);
            String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
            store.complete(scope, key, wrapped.getStatus(),
                    Optional.ofNullable(wrapped.getContentType()).orElse("application/json"), body);
        } catch (Exception e) {
            store.abandon(scope, key); // let a retry re-execute after a failure
            throw e;
        } finally {
            wrapped.copyBodyToResponse();
        }
    }
}
