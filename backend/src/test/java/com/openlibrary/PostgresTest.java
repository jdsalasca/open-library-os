package com.openlibrary;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Boots the whole application against a throwaway Postgres so Flyway runs for real. */
@Testcontainers
public abstract class PostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");
}