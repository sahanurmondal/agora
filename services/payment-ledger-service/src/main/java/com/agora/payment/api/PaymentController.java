package com.agora.payment.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Event-sourced double-entry ledger.
 *
 * - Every payment appends TWO ledger events (debit buyer, credit escrow) that
 *   sum to zero — money moves, never appears or vanishes.
 * - balances is a CQRS projection updated in the SAME transaction (a
 *   synchronous projection: simplest consistency; async projections arrive
 *   with Kafka consumers later).
 * - Exactly-once effect: INSERT into payments (PK order_id) is the dedup
 *   gate. Retries and duplicate deliveries replay the stored outcome.
 * - GET /rebuild proves event sourcing: recompute balances from the event log
 *   and diff against the projection.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private static final String ESCROW = "merchant_escrow";

    private final JdbcTemplate jdbc;
    private final long delayMs;

    public PaymentController(JdbcTemplate jdbc, @Value("${payment.delay-ms:0}") long delayMs) {
        this.jdbc = jdbc;
        this.delayMs = delayMs;
    }

    public record PayReq(String orderId, String buyerAccount, long amountCents) {
    }

    @PostMapping("/payments")
    @Transactional
    public ResponseEntity<Map<String, Object>> pay(@RequestBody PayReq req) throws InterruptedException {
        if (delayMs > 0) Thread.sleep(delayMs); // chaos knob for circuit-breaker demos

        try {
            jdbc.update("INSERT INTO payments (order_id, amount_cents, state) VALUES (?,?,'CAPTURED')",
                    req.orderId(), req.amountCents());
        } catch (DuplicateKeyException e) {
            var prior = jdbc.queryForList("SELECT state FROM payments WHERE order_id = ?", req.orderId());
            return ResponseEntity.ok(Map.of("orderId", req.orderId(),
                    "state", prior.get(0).get("state"), "replay", true));
        }

        append(req.orderId(), req.buyerAccount(), -req.amountCents(), "PAYMENT");
        append(req.orderId(), ESCROW, req.amountCents(), "PAYMENT");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("orderId", req.orderId(), "state", "CAPTURED"));
    }

    /** Compensating entry — the saga's refund path. Idempotent via state gate. */
    @PostMapping("/payments/{orderId}/refund")
    @Transactional
    public ResponseEntity<Map<String, Object>> refund(@PathVariable String orderId) {
        int flipped = jdbc.update(
                "UPDATE payments SET state = 'REFUNDED' WHERE order_id = ? AND state = 'CAPTURED'", orderId);
        if (flipped == 0) {
            return ResponseEntity.ok(Map.of("orderId", orderId, "state", "NOOP"));
        }
        Long amount = jdbc.queryForObject(
                "SELECT amount_cents FROM payments WHERE order_id = ?", Long.class, orderId);
        String buyer = jdbc.queryForObject("""
                SELECT account FROM ledger_events
                WHERE order_id = ? AND kind = 'PAYMENT' AND delta_cents < 0 LIMIT 1
                """, String.class, orderId);
        append(orderId, buyer, amount, "REFUND");
        append(orderId, ESCROW, -amount, "REFUND");
        return ResponseEntity.ok(Map.of("orderId", orderId, "state", "REFUNDED"));
    }

    @GetMapping("/accounts/{account}/balance")
    public Map<String, Object> balance(@PathVariable String account) {
        var rows = jdbc.queryForList("SELECT balance_cents FROM balances WHERE account = ?", account);
        return Map.of("account", account,
                "balanceCents", rows.isEmpty() ? 0 : rows.get(0).get("balance_cents"));
    }

    /** The event-sourcing proof: fold the log, diff the projection. */
    @GetMapping("/ledger/rebuild")
    public Map<String, Object> rebuild() {
        List<Map<String, Object>> mismatches = jdbc.queryForList("""
                SELECT COALESCE(e.account, b.account) AS account,
                       COALESCE(e.total, 0)  AS from_events,
                       COALESCE(b.balance_cents, 0) AS projection
                FROM (SELECT account, SUM(delta_cents) AS total FROM ledger_events GROUP BY account) e
                FULL OUTER JOIN balances b ON b.account = e.account
                WHERE COALESCE(e.total, 0) <> COALESCE(b.balance_cents, 0)
                """);
        Long zeroSum = jdbc.queryForObject("SELECT COALESCE(SUM(delta_cents),0) FROM ledger_events", Long.class);
        return Map.of("projectionMatchesEvents", mismatches.isEmpty(),
                "ledgerZeroSum", zeroSum == 0, "mismatches", mismatches);
    }

    private void append(String orderId, String account, long deltaCents, String kind) {
        jdbc.update("INSERT INTO ledger_events (order_id, account, delta_cents, kind) VALUES (?,?,?,?)",
                orderId, account, deltaCents, kind);
        jdbc.update("""
                INSERT INTO balances (account, balance_cents) VALUES (?, ?)
                ON CONFLICT (account) DO UPDATE SET balance_cents = balances.balance_cents + EXCLUDED.balance_cents
                """, account, deltaCents);
    }
}
