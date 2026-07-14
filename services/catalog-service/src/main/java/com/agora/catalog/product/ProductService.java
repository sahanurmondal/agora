package com.agora.catalog.product;

import com.agora.platform.outbox.OutboxWriter;
import com.agora.platform.snowflake.Snowflake;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The caching masterclass service. Read path is a THREE-tier lookup:
 *
 *   L1 Caffeine (per-instance, ~1s TTL)  — absorbs hot keys so a celebrity
 *      product can't saturate a single Redis shard
 *   L2 Redis cache-aside (5m TTL + 10% jitter) — jitter de-synchronizes
 *      expiry so a batch of writes doesn't become a synchronized miss storm
 *   L3 Postgres — source of truth
 *
 * Thundering-herd defense: single-flight request coalescing. N concurrent
 * misses for the same key produce ONE database query; the rest await the same
 * CompletableFuture. Kill Redis under load and DB QPS stays bounded by the
 * number of distinct keys, not the number of requests (chaos experiment 04).
 *
 * Writes go through the transactional outbox in the SAME transaction as the
 * product row — Debezium publishes; we never dual-write to Kafka.
 */
@Service
public class ProductService {

    private static final Duration REDIS_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "product:";

    private final ProductRepository repo;
    private final StringRedisTemplate redis;
    private final Cache<Long, Product> l1;
    private final OutboxWriter outbox;
    private final Snowflake snowflake;
    private final ObjectMapper json;
    private final Counter l1Hits, l2Hits, misses;
    private final ConcurrentHashMap<Long, CompletableFuture<Optional<Product>>> inFlight = new ConcurrentHashMap<>();

    public ProductService(ProductRepository repo, StringRedisTemplate redis, Cache<Long, Product> l1,
                          OutboxWriter outbox, Snowflake snowflake, ObjectMapper json, MeterRegistry metrics) {
        this.repo = repo;
        this.redis = redis;
        this.l1 = l1;
        this.outbox = outbox;
        this.snowflake = snowflake;
        this.json = json;
        this.l1Hits = metrics.counter("catalog.cache.hits", "tier", "l1");
        this.l2Hits = metrics.counter("catalog.cache.hits", "tier", "l2-redis");
        this.misses = metrics.counter("catalog.cache.misses");
    }

    @Transactional
    public Product create(String name, String description, long priceCents, String sellerId, String imageKey) {
        Product p = new Product(snowflake.next(), name, description, priceCents, sellerId, imageKey);
        repo.insert(p);
        outbox.append("product", String.valueOf(p.id()), "ProductCreated", toJson(p));
        return p;
    }

    public Optional<Product> get(long id) {
        Product fromL1 = l1.getIfPresent(id);
        if (fromL1 != null) {
            l1Hits.increment();
            return Optional.of(fromL1);
        }

        String cached = redis.opsForValue().get(KEY_PREFIX + id);
        if (cached != null) {
            l2Hits.increment();
            Product p = fromJson(cached);
            l1.put(id, p);
            return Optional.of(p);
        }

        // Single-flight: first caller loads, concurrent misses piggyback.
        CompletableFuture<Optional<Product>> loader = new CompletableFuture<>();
        CompletableFuture<Optional<Product>> existing = inFlight.putIfAbsent(id, loader);
        if (existing != null) {
            return existing.join();
        }
        try {
            misses.increment();
            Optional<Product> loaded = repo.findById(id);
            loaded.ifPresent(p -> {
                long jitterSec = ThreadLocalRandom.current().nextLong(REDIS_TTL.toSeconds() / 10 + 1);
                redis.opsForValue().set(KEY_PREFIX + id, toJson(p), REDIS_TTL.plusSeconds(jitterSec));
                l1.put(id, p);
            });
            loader.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            loader.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(id);
        }
    }

    private String toJson(Product p) {
        try {
            return json.writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Product fromJson(String s) {
        try {
            return json.readValue(s, Product.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
