package com.agora.platform.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton containers: started lazily on first access, shared by every
 * integration test in the JVM, reaped by Ryuk on exit. Never call stop().
 */
public final class Containers {

    private Containers() {
    }

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;
    private static KafkaContainer kafka;

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");
            postgres.start();
        }
        return postgres;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
            redis.start();
        }
        return redis;
    }

    @SuppressWarnings("deprecation") // stable API; successor class varies across 1.20.x
    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));
            kafka.start();
        }
        return kafka;
    }
}
