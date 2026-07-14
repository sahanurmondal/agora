package com.agora.platform.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Transactional-outbox writer. Call {@link #append} INSIDE the same
 * transaction as the business write — that is the entire pattern: one local
 * ACID transaction covers both the state change and the event row, and
 * Debezium tails the table to publish (transaction-log tailing), eliminating
 * the dual-write problem. Nothing here publishes to Kafka directly.
 *
 * Debezium connector config for this table lives in infra/debezium/.
 */
public class OutboxWriter {

    /** Ship this with each service's schema migration. */
    public static final String OUTBOX_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS outbox (
              id             UUID PRIMARY KEY,
              aggregate_type TEXT NOT NULL,
              aggregate_id   TEXT NOT NULL,
              event_type     TEXT NOT NULL,
              payload        TEXT NOT NULL,
              created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
            )""";

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the outbox event id (also usable as an idempotency/dedup key downstream) */
    public UUID append(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload) VALUES (?,?,?,?,?)",
                id, aggregateType, aggregateId, eventType, payloadJson);
        return id;
    }
}
