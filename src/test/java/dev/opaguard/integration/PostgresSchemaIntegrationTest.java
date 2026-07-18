package dev.opaguard.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.platform.domain.BenchmarkJob;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.domain.ExecutionClaim;
import dev.opaguard.platform.domain.JobStatus;
import dev.opaguard.platform.persistence.JdbcBenchmarkJobRepository;
import dev.opaguard.platform.persistence.JdbcOutboxRepository;
import dev.opaguard.platform.port.BenchmarkJobRepository.CreationResult;
import dev.opaguard.platform.port.OutboxRepository.OutboxMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSchemaIntegrationTest {
    private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BASELINE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID DATASET_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-alpine")
            .withDatabaseName("opa_guard").withUsername("opa_guard").withPassword("integration-only");

    private JdbcClient jdbc;
    private TransactionTemplate transaction;
    private JdbcBenchmarkJobRepository jobs;
    private JdbcOutboxRepository outbox;

    @BeforeAll
    void migrateAndSeed() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jobs = new JdbcBenchmarkJobRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        outbox = new JdbcOutboxRepository(jdbc);

        jdbc.sql("INSERT INTO organizations (id, slug, display_name, home_region) VALUES (:id, 'test-org', 'Test', 'local')")
                .param("id", ORGANIZATION_ID).update();
        transaction.executeWithoutResult(ignored -> {
            setTenant();
            jdbc.sql("""
                    INSERT INTO policy_versions
                      (id, organization_id, repository, git_commit, object_key, sha256, query_path)
                    VALUES
                      (:baseline, :org, 'example/repo', repeat('a', 40), repeat('b', 64), repeat('b', 64), 'data.authz.allow'),
                      (:candidate, :org, 'example/repo', repeat('c', 40), repeat('d', 64), repeat('d', 64), 'data.authz.allow')
                    """).param("baseline", BASELINE_ID).param("candidate", CANDIDATE_ID)
                    .param("org", ORGANIZATION_ID).update();
            jdbc.sql("""
                    INSERT INTO dataset_versions (id, organization_id, object_key, sha256, case_count, size_bytes)
                    VALUES (:id, :org, repeat('e', 64), repeat('e', 64), 1, 128)
                    """).param("id", DATASET_ID).param("org", ORGANIZATION_ID).update();
        });
    }

    @Test
    void migratesPartitionedTenantIsolatedSchema() {
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

    @Test
    void concurrentSubmissionsCreateExactlyOneJob() throws Exception {
        int callers = 24;
        var ready = new CountDownLatch(callers);
        var start = new CountDownLatch(1);
        List<Future<CreationResult>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return transaction.execute(status -> jobs.createIfAbsent(job("concurrent-request")));
                }));
            }
            ready.await();
            start.countDown();
            List<CreationResult> results = new ArrayList<>();
            for (Future<CreationResult> future : futures) results.add(future.get());

            assertThat(results).filteredOn(CreationResult::created).hasSize(1);
            assertThat(results.stream().map(result -> result.job().id()).collect(java.util.stream.Collectors.toSet()))
                    .hasSize(1);
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAfterWorkerCrashAndDuplicatesAreSkipped() {
        BenchmarkJob created = transaction.execute(status -> jobs.createIfAbsent(job("lease-recovery")).job());
        Instant started = Instant.parse("2026-07-18T10:00:00Z");

        ExecutionClaim initialClaim = transaction.execute(status -> jobs.claimForExecution(
                ORGANIZATION_ID, created.id(), "worker-a", started, started.plusSeconds(30)));
        ExecutionClaim liveLease = transaction.execute(status -> jobs.claimForExecution(
                ORGANIZATION_ID, created.id(), "worker-b", started.plusSeconds(10), started.plusSeconds(40)));
        ExecutionClaim recoveredClaim = transaction.execute(status -> jobs.claimForExecution(
                ORGANIZATION_ID, created.id(), "worker-b", started.plusSeconds(31), started.plusSeconds(61)));
        assertThat(initialClaim).isEqualTo(ExecutionClaim.CLAIMED);
        assertThat(liveLease).isEqualTo(ExecutionClaim.LEASED);
        assertThat(recoveredClaim).isEqualTo(ExecutionClaim.CLAIMED);
        Boolean staleOwnerRenewed = transaction.execute(status -> jobs.renewExecutionLease(
                ORGANIZATION_ID, created.id(), "worker-a", started.plusSeconds(90)));
        Boolean currentOwnerRenewed = transaction.execute(status -> jobs.renewExecutionLease(
                ORGANIZATION_ID, created.id(), "worker-b", started.plusSeconds(90)));
        assertThat(staleOwnerRenewed).isFalse();
        assertThat(currentOwnerRenewed).isTrue();

        transaction.executeWithoutResult(status -> {
            BenchmarkJob running = jobs.findById(ORGANIZATION_ID, created.id()).orElseThrow();
            running.transitionTo(JobStatus.ANALYZING, started.plusSeconds(40));
            jobs.update(running);
        });
        ExecutionClaim duplicate = transaction.execute(status -> jobs.claimForExecution(
                ORGANIZATION_ID, created.id(), "worker-c", started.plusSeconds(41), started.plusSeconds(71)));
        assertThat(duplicate).isEqualTo(ExecutionClaim.COMPLETE);
    }

    @Test
    void concurrentOutboxRelaysClaimDisjointBatches() throws Exception {
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        for (int index = 0; index < 50; index++) {
            outbox.append(new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), ORGANIZATION_ID,
                    "test.topic", "TestEvent", "key-" + index, "{}", now.plusMillis(index), 0));
        }
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<OutboxMessage>> first = executor.submit(() -> {
                start.await();
                return transaction.execute(status -> outbox.claimBatch(25));
            });
            Future<List<OutboxMessage>> second = executor.submit(() -> {
                start.await();
                return transaction.execute(status -> outbox.claimBatch(25));
            });
            start.countDown();
            List<OutboxMessage> combined = new ArrayList<>(first.get());
            combined.addAll(second.get());

            assertThat(combined).hasSize(50);
            assertThat(new HashSet<>(combined.stream().map(OutboxMessage::eventId).toList())).hasSize(50);
        }
    }

    private BenchmarkJob job(String idempotencyKey) {
        return BenchmarkJob.builder().id(UUID.randomUUID()).organizationId(ORGANIZATION_ID)
                .baselineVersionId(BASELINE_ID).candidateVersionId(CANDIDATE_ID).datasetVersionId(DATASET_ID)
                .idempotencyKey(idempotencyKey).thresholds(new BenchmarkThresholds(
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, true))
                .warmupIterations(1).measuredIterations(2).createdAt(Instant.parse("2026-07-18T10:00:00Z"))
                .build();
    }

    private void setTenant() {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", ORGANIZATION_ID.toString()).query(String.class).single();
    }
}
