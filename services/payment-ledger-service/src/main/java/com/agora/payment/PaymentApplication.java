package com.agora.payment;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    @Bean
    ApplicationRunner schema(JdbcTemplate jdbc) {
        return args -> {
            // The append-only source of truth. Balances are DERIVED (CQRS).
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS ledger_events (
                      seq         BIGSERIAL PRIMARY KEY,
                      order_id    TEXT NOT NULL,
                      account     TEXT NOT NULL,
                      delta_cents BIGINT NOT NULL,
                      kind        TEXT NOT NULL,
                      created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            // Dedup gate: one payment per order, enforced by the PK — a retried
            // or duplicate-delivered request cannot append twice.
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS payments (
                      order_id     TEXT PRIMARY KEY,
                      amount_cents BIGINT NOT NULL,
                      state        TEXT NOT NULL
                    )""");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS balances (
                      account       TEXT PRIMARY KEY,
                      balance_cents BIGINT NOT NULL DEFAULT 0
                    )""");
        };
    }
}
