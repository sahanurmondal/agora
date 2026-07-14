package com.agora.catalog.config;

import com.agora.catalog.product.Product;
import com.agora.catalog.product.ProductRepository;
import com.agora.platform.idempotency.JdbcIdempotencyStore;
import com.agora.platform.outbox.OutboxWriter;
import com.agora.platform.snowflake.Snowflake;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

@Configuration
public class CatalogConfig {

    @Bean
    public Snowflake snowflake(@Value("${catalog.machine-id:2}") long machineId) {
        return new Snowflake(machineId);
    }

    @Bean
    public OutboxWriter outboxWriter(JdbcTemplate jdbc) {
        return new OutboxWriter(jdbc);
    }

    /** L1: tiny TTL — hot-key shock absorber, not a source of truth. */
    @Bean
    public Cache<Long, Product> l1Cache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(1))
                .recordStats()
                .build();
    }

    @Bean
    ApplicationRunner initSchema(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute(ProductRepository.DDL);
            jdbc.execute(OutboxWriter.OUTBOX_TABLE_DDL);
            jdbc.execute(JdbcIdempotencyStore.TABLE_DDL);
        };
    }
}
