package com.agora.catalog.product;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ProductRepository {

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS products (
              id          BIGINT PRIMARY KEY,
              name        TEXT NOT NULL,
              description TEXT NOT NULL DEFAULT '',
              price_cents BIGINT NOT NULL,
              seller_id   TEXT NOT NULL,
              image_key   TEXT,
              created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""";

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Product p) {
        jdbc.update("INSERT INTO products (id, name, description, price_cents, seller_id, image_key) VALUES (?,?,?,?,?,?)",
                p.id(), p.name(), p.description(), p.priceCents(), p.sellerId(), p.imageKey());
    }

    public Optional<Product> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, name, description, price_cents, seller_id, image_key FROM products WHERE id = ?",
                    (rs, i) -> new Product(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                            rs.getLong("price_cents"), rs.getString("seller_id"), rs.getString("image_key")),
                    id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
