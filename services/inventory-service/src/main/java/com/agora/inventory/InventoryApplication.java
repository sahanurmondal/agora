package com.agora.inventory;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }

    @Bean
    ApplicationRunner schema(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS inventory (
                      product_id BIGINT PRIMARY KEY,
                      stock      INT NOT NULL CHECK (stock >= 0),
                      version    INT NOT NULL DEFAULT 0
                    )""");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS reservations (
                      order_id   TEXT PRIMARY KEY,
                      product_id BIGINT NOT NULL,
                      qty        INT NOT NULL,
                      state      TEXT NOT NULL DEFAULT 'RESERVED',
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
        };
    }
}
