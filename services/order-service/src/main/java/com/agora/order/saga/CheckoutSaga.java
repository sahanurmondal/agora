package com.agora.order.saga;

import com.agora.platform.outbox.OutboxWriter;
import com.agora.platform.snowflake.Snowflake;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.core.IntervalFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Orchestration-style saga: this service owns the checkout state machine and
 * calls participants explicitly; every step and compensation is recorded in
 * saga_steps and visible as spans in one Tempo trace.
 *
 *   PENDING → reserve(inventory) → pay(ledger) → confirm(inventory) → CONFIRMED
 *      reserve 409                       → REJECTED  (nothing to compensate)
 *      pay fails/times out/CB open       → release(inventory) → CANCELLED
 *
 * The payment hop is the fragile one, so it gets the full resilience stack:
 * 2s read timeout (in RestClient), circuit breaker (fail fast once the ledger
 * is clearly down — no thread pile-up), and ONE retry with jittered backoff
 * (safe because payments dedup by order id — idempotency makes retries free).
 * Chaos experiments 01 and 09 target exactly this hop.
 */
@Service
public class CheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final Snowflake snowflake;
    private final RestClient catalog;
    private final RestClient inventory;
    private final RestClient payment;
    private final CircuitBreaker paymentBreaker;
    private final Retry paymentRetry;

    public CheckoutSaga(JdbcTemplate jdbc, OutboxWriter outbox, Snowflake snowflake,
                        RestClient.Builder http,
                        @Value("${order.catalog-uri}") String catalogUri,
                        @Value("${order.inventory-uri}") String inventoryUri,
                        @Value("${order.payment-uri}") String paymentUri) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.snowflake = snowflake;
        this.catalog = http.baseUrl(catalogUri).build();
        this.inventory = http.baseUrl(inventoryUri).build();
        this.payment = http.baseUrl(paymentUri).build();

        this.paymentBreaker = CircuitBreaker.of("payment", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        paymentBreaker.getEventPublisher().onStateTransition(e ->
                log.warn("payment circuit breaker: {}", e.getStateTransition()));

        this.paymentRetry = Retry.of("payment", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2.0, 0.5))
                .build());
    }

    public record Result(long orderId, String state, int httpStatus) {
    }

    public Result checkout(String buyerId, long productId, int qty) {
        Map<?, ?> product = catalog.get().uri("/api/v1/products/{id}", productId)
                .retrieve().body(Map.class);
        long amount = ((Number) product.get("priceCents")).longValue() * qty;

        long orderId = snowflake.next();
        jdbc.update("INSERT INTO orders (id, buyer_id, product_id, qty, amount_cents, state) VALUES (?,?,?,?,?,'PENDING')",
                orderId, buyerId, productId, qty, amount);

        // Step 1: reserve stock
        var reserve = inventory.post().uri("/api/v1/inventory/reserve")
                .body(Map.of("orderId", String.valueOf(orderId), "productId", productId, "qty", qty, "mode", "optimistic"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (rq, rs) -> { /* handled by status below */ })
                .toEntity(Map.class);
        if (reserve.getStatusCode().value() == 409) {
            step(orderId, "RESERVE", "INSUFFICIENT");
            transition(orderId, "REJECTED");
            return new Result(orderId, "REJECTED", 409);
        }
        step(orderId, "RESERVE", "OK");

        // Step 2: pay (the guarded hop)
        try {
            Supplier<Object> payCall = () -> payment.post().uri("/api/v1/payments")
                    .body(Map.of("orderId", String.valueOf(orderId), "buyerAccount", "buyer_" + buyerId,
                            "amountCents", amount))
                    .retrieve().body(Map.class);
            Retry.decorateSupplier(paymentRetry,
                    CircuitBreaker.decorateSupplier(paymentBreaker, payCall)).get();
            step(orderId, "PAY", "OK");
        } catch (Exception e) {
            step(orderId, "PAY", "FAILED:" + e.getClass().getSimpleName());
            compensate(orderId);
            transition(orderId, "CANCELLED");
            return new Result(orderId, "CANCELLED", 422);
        }

        // Step 3: confirm reservation
        inventory.post().uri("/api/v1/inventory/confirm")
                .body(Map.of("orderId", String.valueOf(orderId))).retrieve().body(Map.class);
        step(orderId, "CONFIRM", "OK");
        transition(orderId, "CONFIRMED");
        outbox.append("order", String.valueOf(orderId), "OrderConfirmed",
                "{\"orderId\":%d,\"buyerId\":\"%s\",\"amountCents\":%d}".formatted(orderId, buyerId, amount));
        return new Result(orderId, "CONFIRMED", 201);
    }

    private void compensate(long orderId) {
        try {
            inventory.post().uri("/api/v1/inventory/release")
                    .body(Map.of("orderId", String.valueOf(orderId))).retrieve().body(Map.class);
            step(orderId, "COMPENSATE_RELEASE", "OK");
        } catch (Exception e) {
            // Compensation failure = manual-intervention queue in real life.
            step(orderId, "COMPENSATE_RELEASE", "FAILED");
            log.error("compensation failed for order {} — needs redrive", orderId, e);
        }
    }

    private void step(long orderId, String step, String status) {
        jdbc.update("INSERT INTO saga_steps (order_id, step, status) VALUES (?,?,?)", orderId, step, status);
    }

    private void transition(long orderId, String state) {
        jdbc.update("UPDATE orders SET state = ? WHERE id = ?", state, orderId);
    }
}
