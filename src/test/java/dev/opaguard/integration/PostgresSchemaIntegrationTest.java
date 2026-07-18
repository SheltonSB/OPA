package dev.opaguard.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PostgresSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-alpine")
            .withDatabaseName("opa_guard").withUsername("opa_guard").withPassword("integration-only");

    @Test
    void migratesPartitionedTenantIsolatedSchema() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcClient jdbc = JdbcClient.create(dataSource);

        Integer tableCount = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN
                  ('organizations','policy_versions','dataset_versions','benchmark_jobs',
                   'benchmark_results','benchmark_reports','outbox_events','processed_events')
                """).query(Integer.class).single();
        Boolean forcedRls = jdbc.sql("SELECT relforcerowsecurity FROM pg_class WHERE relname='benchmark_jobs'")
                .query(Boolean.class).single();

        assertThat(tableCount).isEqualTo(8);
        assertThat(forcedRls).isTrue();
    }
}
