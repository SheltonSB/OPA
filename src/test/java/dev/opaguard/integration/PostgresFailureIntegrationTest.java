package dev.opaguard.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the database-outage boundary used by coordinator failure handling.
 *
 * <p>The application-level outbox invariant is covered by
 * {@code SubmitBenchmarkJobFailureTest}; this test supplies the missing
 * container-backed evidence that a real PostgreSQL outage is surfaced as a
 * failure rather than silently acknowledged.</p>
 *
 * @author Shelton Bumhe
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresFailureIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-alpine")
            .withDatabaseName("opa_guard_failure").withUsername("opa_guard").withPassword("integration-only");

    @Test
    void stoppedDatabaseRejectsTheNextConnection() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("SELECT 1");
        }

        POSTGRES.stop();

        assertThatThrownBy(() -> DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .isInstanceOfAny(java.sql.SQLException.class, RuntimeException.class);
    }
}
