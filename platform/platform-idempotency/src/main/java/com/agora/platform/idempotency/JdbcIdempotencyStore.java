package com.agora.platform.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * Postgres-backed store. The PK on (scope, key) is what makes tryBegin atomic —
 * two racing duplicates cannot both insert.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    public static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS idempotency_keys (
              scope        TEXT NOT NULL,
              key          TEXT NOT NULL,
              status       INT,
              content_type TEXT,
              body         TEXT,
              completed    BOOLEAN NOT NULL DEFAULT false,
              created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
              PRIMARY KEY (scope, key)
            )""";

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryBegin(String scope, String key) {
        try {
            jdbc.update("INSERT INTO idempotency_keys (scope, key) VALUES (?, ?)", scope, key);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<StoredResponse> find(String scope, String key) {
        var rows = jdbc.query(
                "SELECT completed, status, content_type, body FROM idempotency_keys WHERE scope = ? AND key = ?",
                (rs, i) -> new StoredResponse(
                        !rs.getBoolean("completed"),
                        rs.getInt("status"),
                        rs.getString("content_type"),
                        rs.getString("body")),
                scope, key);
        return rows.stream().findFirst();
    }

    @Override
    public void complete(String scope, String key, int status, String contentType, String body) {
        jdbc.update(
                "UPDATE idempotency_keys SET completed = true, status = ?, content_type = ?, body = ? WHERE scope = ? AND key = ?",
                status, contentType, body, scope, key);
    }

    @Override
    public void abandon(String scope, String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE scope = ? AND key = ? AND completed = false", scope, key);
    }
}
