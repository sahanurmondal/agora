package com.agora.inventory.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The no-oversell service. Two concurrency modes exposed side by side so the
 * flash-sale k6 run can compare them (docs/PLAN.md, chaos exp 06 pairs this
 * with distributed locks + fencing):
 *
 * - optimistic: single atomic conditional UPDATE ... WHERE stock >= qty.
 *   No lock held; losers just see 0 rows updated. Best under low contention.
 * - pessimistic: SELECT ... FOR UPDATE serializes on the row lock, then
 *   updates. Predictable under fierce contention, but every request queues.
 *
 * Idempotency: reservations PK is order_id — a retried reserve for the same
 * order can't double-decrement (DuplicateKeyException → return existing).
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final JdbcTemplate jdbc;

    public InventoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ReserveReq(String orderId, long productId, int qty, String mode) {
    }

    @PutMapping("/{productId}")
    public Map<String, Object> seed(@PathVariable long productId, @RequestBody Map<String, Integer> body) {
        int stock = body.getOrDefault("stock", 0);
        jdbc.update("""
                INSERT INTO inventory (product_id, stock) VALUES (?, ?)
                ON CONFLICT (product_id) DO UPDATE SET stock = EXCLUDED.stock, version = inventory.version + 1
                """, productId, stock);
        return Map.of("productId", productId, "stock", stock);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long productId) {
        var rows = jdbc.queryForList("SELECT stock, version FROM inventory WHERE product_id = ?", productId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("productId", productId,
                "stock", rows.get(0).get("stock"), "version", rows.get(0).get("version")));
    }

    @PostMapping("/reserve")
    @Transactional
    public ResponseEntity<Map<String, Object>> reserve(@RequestBody ReserveReq req) {
        try {
            jdbc.update("INSERT INTO reservations (order_id, product_id, qty) VALUES (?,?,?)",
                    req.orderId(), req.productId(), req.qty());
        } catch (DuplicateKeyException e) {
            return ResponseEntity.ok(Map.of("orderId", req.orderId(), "state", "RESERVED", "replay", true));
        }

        boolean ok;
        if ("pessimistic".equalsIgnoreCase(req.mode())) {
            Integer stock = jdbc.queryForObject(
                    "SELECT stock FROM inventory WHERE product_id = ? FOR UPDATE", Integer.class, req.productId());
            ok = stock != null && stock >= req.qty();
            if (ok) {
                jdbc.update("UPDATE inventory SET stock = stock - ?, version = version + 1 WHERE product_id = ?",
                        req.qty(), req.productId());
            }
        } else {
            ok = jdbc.update("""
                    UPDATE inventory SET stock = stock - ?, version = version + 1
                    WHERE product_id = ? AND stock >= ?
                    """, req.qty(), req.productId(), req.qty()) == 1;
        }

        if (!ok) {
            // Roll the reservation row back with the tx by throwing? No — we want a
            // clean 409 body. Delete inside the same tx instead.
            jdbc.update("DELETE FROM reservations WHERE order_id = ?", req.orderId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("orderId", req.orderId(), "state", "INSUFFICIENT_STOCK"));
        }
        return ResponseEntity.ok(Map.of("orderId", req.orderId(), "state", "RESERVED"));
    }

    /** Saga compensation: put the stock back, mark reservation released. Idempotent. */
    @PostMapping("/release")
    @Transactional
    public Map<String, Object> release(@RequestBody Map<String, String> body) {
        String orderId = body.get("orderId");
        int updated = jdbc.update("""
                UPDATE reservations SET state = 'RELEASED' WHERE order_id = ? AND state = 'RESERVED'
                """, orderId);
        if (updated == 1) {
            jdbc.update("""
                    UPDATE inventory SET stock = stock + r.qty, version = version + 1
                    FROM reservations r WHERE r.order_id = ? AND inventory.product_id = r.product_id
                    """, orderId);
        }
        return Map.of("orderId", orderId, "released", updated == 1);
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, String> body) {
        String orderId = body.get("orderId");
        int updated = jdbc.update(
                "UPDATE reservations SET state = 'CONFIRMED' WHERE order_id = ? AND state = 'RESERVED'", orderId);
        return Map.of("orderId", orderId, "confirmed", updated == 1);
    }
}
