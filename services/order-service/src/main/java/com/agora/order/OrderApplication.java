package com.agora.order;

import com.agora.platform.idempotency.IdempotencyFilter;
import com.agora.platform.idempotency.JdbcIdempotencyStore;
import com.agora.platform.outbox.OutboxWriter;
import com.agora.platform.snowflake.Snowflake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@SpringBootApplication
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

    @Bean
    ApplicationRunner schema(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                      id           BIGINT PRIMARY KEY,
                      buyer_id     TEXT NOT NULL,
                      product_id   BIGINT NOT NULL,
                      qty          INT NOT NULL,
                      amount_cents BIGINT NOT NULL,
                      state        TEXT NOT NULL,
                      created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            // Saga audit log: every step + compensation lands here — the DB-side
            // twin of what the Tempo trace shows.
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS saga_steps (
                      id       BIGSERIAL PRIMARY KEY,
                      order_id BIGINT NOT NULL,
                      step     TEXT NOT NULL,
                      status   TEXT NOT NULL,
                      at       TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            jdbc.execute(OutboxWriter.OUTBOX_TABLE_DDL);
            jdbc.execute(JdbcIdempotencyStore.TABLE_DDL);
        };
    }

    @Bean
    Snowflake snowflake(@Value("${order.machine-id:3}") long machineId) {
        return new Snowflake(machineId);
    }

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc) {
        return new OutboxWriter(jdbc);
    }

    @Bean
    IdempotencyFilter idempotencyFilter(JdbcTemplate jdbc) {
        return new IdempotencyFilter(new JdbcIdempotencyStore(jdbc));
    }

    /** Hard timeouts on every outbound hop — an unbounded call is a bulkhead leak. */
    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(1))
                                .withReadTimeout(Duration.ofSeconds(2))));
    }
}
