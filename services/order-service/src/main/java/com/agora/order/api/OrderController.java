package com.agora.order.api;

import com.agora.order.saga.CheckoutSaga;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CheckoutSaga saga;
    private final JdbcTemplate jdbc;

    public OrderController(CheckoutSaga saga, JdbcTemplate jdbc) {
        this.saga = saga;
        this.jdbc = jdbc;
    }

    public record CreateReq(long productId, int qty) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateReq req,
                                                      @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String buyerId) {
        if (req.qty() <= 0) return ResponseEntity.badRequest().body(Map.of("error", "qty must be > 0"));
        CheckoutSaga.Result r = saga.checkout(buyerId, req.productId(), req.qty());
        return ResponseEntity.status(r.httpStatus())
                .body(Map.of("orderId", String.valueOf(r.orderId()), "state", r.state()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        var rows = jdbc.queryForList("SELECT id, buyer_id, product_id, qty, amount_cents, state FROM orders WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT step, status, at FROM saga_steps WHERE order_id = ? ORDER BY id", id);
        var o = rows.get(0);
        return ResponseEntity.ok(Map.of("order", o, "sagaSteps", steps));
    }

    /** Flash-sale scoreboard: order counts by state for one product. */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam long productId) {
        List<Map<String, Object>> byState = jdbc.queryForList(
                "SELECT state, COUNT(*) AS n FROM orders WHERE product_id = ? GROUP BY state", productId);
        return Map.of("productId", productId, "byState", byState);
    }
}
