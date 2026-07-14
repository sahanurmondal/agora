package com.agora.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;

/**
 * Consumes the Debezium topic for catalog's outbox table and indexes products.
 *
 * Idempotent consumer BY CONSTRUCTION: indexing is a PUT keyed by product id,
 * so redelivery (rebalance, offset replay — chaos experiment 05) converges to
 * the same document instead of duplicating. This is the cheapest exactly-once:
 * at-least-once delivery + naturally idempotent writes.
 *
 * Autocomplete: every name prefix (≤10 chars) gets a Redis sorted-set entry —
 * the "sharded trie in a sorted set" trick. ZRANGE ac:{prefix} answers
 * suggestions in O(log N + k) with zero search-engine involvement.
 */
@Component
public class OutboxIndexer {

    private static final Logger log = LoggerFactory.getLogger(OutboxIndexer.class);
    private static final int MAX_PREFIX = 10;

    private final RestClient openSearch;
    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();

    public OutboxIndexer(RestClient openSearch, StringRedisTemplate redis) {
        this.openSearch = openSearch;
        this.redis = redis;
    }

    @KafkaListener(topics = "${search.outbox-topic}", groupId = "search-indexer")
    public void onOutboxEvent(String message) throws Exception {
        JsonNode envelope = json.readTree(message);
        JsonNode after = envelope.path("payload").path("after");
        if (after.isMissingNode() || after.isNull()) {
            return; // deletes/tombstones — not our concern
        }
        String eventType = after.path("event_type").asText();
        if (!"ProductCreated".equals(eventType)) {
            return;
        }

        JsonNode product = json.readTree(after.path("payload").asText());
        String id = after.path("aggregate_id").asText();

        openSearch.put()
                .uri("/products/_doc/{id}", id)
                .header("Content-Type", "application/json")
                .body(json.writeValueAsString(product))
                .retrieve()
                .toBodilessEntity();

        String name = product.path("name").asText().toLowerCase(Locale.ROOT).trim();
        for (int i = 1; i <= Math.min(name.length(), MAX_PREFIX); i++) {
            redis.opsForZSet().incrementScore("ac:" + name.substring(0, i), name, 1);
        }
        log.info("indexed product id={} name='{}'", id, name);
    }
}
