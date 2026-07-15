package com.agora.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * order outbox topic → notify buyer over SSE.
 *
 * - Idempotent consumer: dedup on outbox event id via Redis SETNX + TTL —
 *   offset replay (chaos 05) can't double-notify.
 * - SSE (not WebSocket): one-way server push is all a notification toast
 *   needs; WS lives in chat-service. Long-poll fallback: GET /poll.
 * - Poison pill: an event with buyerId "poison" throws → retries → DLT
 *   (deliberate hook for the DLQ demo).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationFlow {

    private static final Logger log = LoggerFactory.getLogger(NotificationFlow.class);

    private final StringRedisTemplate redis;
    private final KafkaTemplate<Object, Object> kafka;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong delivered = new AtomicLong();

    public NotificationFlow(StringRedisTemplate redis, KafkaTemplate<Object, Object> kafka) {
        this.redis = redis;
        this.kafka = kafka;
    }

    @KafkaListener(topics = "${notification.topic}", groupId = "notification-service")
    public void onOrderEvent(String message) throws Exception {
        JsonNode after = json.readTree(message).path("payload").path("after");
        if (after.isMissingNode() || after.isNull() || !"OrderConfirmed".equals(after.path("event_type").asText())) {
            return;
        }
        String eventId = after.path("id").asText();
        // Dedup claim, RELEASED ON FAILURE. The subtle bug this fixes (found
        // live): claiming before processing and not releasing turns error-
        // handler RETRIES into "duplicates" — they no-op, the poison message
        // "succeeds" and never reaches the DLQ. An idempotency marker must
        // only stick once processing actually succeeded.
        Boolean first = redis.opsForValue().setIfAbsent("notif:dedup:" + eventId, "1",
                java.time.Duration.ofHours(1));
        if (!Boolean.TRUE.equals(first)) {
            log.info("duplicate delivery suppressed: {}", eventId);
            return;
        }

        try {
            process(after, eventId);
        } catch (Exception e) {
            redis.delete("notif:dedup:" + eventId); // release claim → retry re-executes
            throw e;
        }
    }

    private void process(JsonNode after, String eventId) throws Exception {
        JsonNode payload = json.readTree(after.path("payload").asText());
        String buyerId = payload.path("buyerId").asText();
        if ("poison".equals(buyerId)) {
            throw new IllegalStateException("poison message (DLQ demo)"); // → retries → DLT
        }

        String toast = json.writeValueAsString(Map.of(
                "type", "ORDER_CONFIRMED", "orderId", payload.path("orderId").asText(),
                "amountCents", payload.path("amountCents").asLong()));
        redis.opsForList().leftPush("notif:inbox:" + buyerId, toast); // long-poll store
        redis.opsForList().trim("notif:inbox:" + buyerId, 0, 99);

        SseEmitter emitter = emitters.get(buyerId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(toast));
                delivered.incrementAndGet();
            } catch (IOException e) {
                emitters.remove(buyerId);
            }
        }
        log.info("notified buyer={} event={}", buyerId, eventId);
    }

    /** SSE stream — curl -N localhost:8091/api/v1/notifications/stream/{buyerId} */
    @GetMapping(value = "/stream/{buyerId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String buyerId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(buyerId, emitter);
        emitter.onCompletion(() -> emitters.remove(buyerId));
        emitter.onTimeout(() -> emitters.remove(buyerId));
        return emitter;
    }

    /** Long-poll fallback: drain the inbox. */
    @GetMapping("/poll/{buyerId}")
    public List<String> poll(@PathVariable String buyerId) {
        List<String> items = redis.opsForList().range("notif:inbox:" + buyerId, 0, -1);
        redis.delete("notif:inbox:" + buyerId);
        return items == null ? List.of() : items;
    }

    /** DLQ redrive: republish dead letters to the main topic. */
    @PostMapping("/dlq/redrive")
    public Map<String, Object> redrive(@RequestParam(defaultValue = "10") int max) {
        // Minimal redrive: consume DLT with a throwaway consumer and republish.
        var props = new java.util.Properties();
        props.putAll(Map.of(
                "bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:19092"),
                "group.id", "dlq-redrive-" + System.currentTimeMillis(),
                "auto.offset.reset", "earliest",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
        int redriven = 0;
        String dlt = System.getenv().getOrDefault("NOTIFICATION_TOPIC", "orders.public.outbox") + ".DLT";
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(dlt));
            var records = consumer.poll(java.time.Duration.ofSeconds(3));
            for (var rec : records) {
                if (redriven >= max) break;
                kafka.send(rec.topic().replace(".DLT", ""), rec.value());
                redriven++;
            }
        }
        return Map.of("redriven", redriven, "from", dlt);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("sseDelivered", delivered.get(), "openStreams", emitters.size());
    }
}
