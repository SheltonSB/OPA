package dev.opaguard.platform.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.benchmark.BenchmarkRunner;
import dev.opaguard.benchmark.DatasetLoader;
import dev.opaguard.benchmark.ScalabilityBenchmarkRunner;
import dev.opaguard.domain.PolicyBenchmark;
import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.analysis.BenchmarkFingerprint;
import dev.opaguard.platform.analysis.RegoComplexityAnalyzer;
import dev.opaguard.platform.domain.BenchmarkExecutionCompleted;
import dev.opaguard.platform.domain.BenchmarkJobCompleted;
import dev.opaguard.platform.domain.BenchmarkJobRequested;
import dev.opaguard.platform.domain.ExecutionClaim;
import dev.opaguard.platform.domain.JobStatus;
import dev.opaguard.platform.domain.PolicyBenchmarkSnapshot;
import dev.opaguard.platform.messaging.KafkaTopics;
import dev.opaguard.platform.port.ArtifactCatalog;
import dev.opaguard.platform.port.ArtifactStore;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import dev.opaguard.platform.port.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Orchestrates artifact verification, benchmarking, complexity analysis, and result publication.
 *
 * <p>Baseline/candidate order is deterministically balanced to reduce systematic
 * thermal bias. Every state change is idempotent and result publication uses the outbox.</p>
 *
 * @author Shelton Bumhe
 */
@Service
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "worker")
public class DistributedBenchmarkWorker {
    private static final String HARNESS_VERSION = "1";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private final ArtifactCatalog catalog;
    private final ArtifactStore artifactStore;
    private final DatasetLoader datasets;
    private final BenchmarkRunner runner;
    private final RegoComplexityAnalyzer complexityAnalyzer;
    private final ScalabilityBenchmarkRunner scalabilityRunner;
    private final BenchmarkJobRepository jobs;
    private final OutboxRepository outbox;
    private final IncrementalResultCache cache;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String workerId = UUID.randomUUID().toString();

    public DistributedBenchmarkWorker(ArtifactCatalog catalog, ArtifactStore artifactStore, DatasetLoader datasets,
                                      BenchmarkRunner runner, RegoComplexityAnalyzer complexityAnalyzer,
                                      ScalabilityBenchmarkRunner scalabilityRunner,
                                      BenchmarkJobRepository jobs, OutboxRepository outbox,
                                      IncrementalResultCache cache, ObjectMapper objectMapper,
                                      Clock clock, TransactionTemplate transactions) {
        this.catalog = catalog; this.artifactStore = artifactStore; this.datasets = datasets; this.runner = runner;
        this.complexityAnalyzer = complexityAnalyzer; this.scalabilityRunner = scalabilityRunner;
        this.jobs = jobs; this.outbox = outbox;
        this.cache = cache; this.objectMapper = objectMapper; this.clock = clock; this.transactions = transactions;
    }

    /**
     * Executes a requested benchmark or safely reuses an identical cached execution.
     *
     * @param event immutable, versioned benchmark request
     */
    public void execute(BenchmarkJobRequested event) {
        ExecutionClaim claim = jobs.claimForExecution(event.organizationId(), event.jobId(), workerId,
                clock.instant(), clock.instant().plus(LEASE_DURATION));
        if (claim == ExecutionClaim.COMPLETE) return;
        if (claim == ExecutionClaim.LEASED) {
            throw new GuardException("Benchmark job is leased by another worker; retry after lease expiry");
        }

        try (ScheduledExecutorService heartbeat = leaseHeartbeat(event)) {
            executeClaimed(event);
        } catch (RuntimeException failure) {
            jobs.releaseExecutionLease(event.organizationId(), event.jobId(), workerId);
            throw failure;
        }
    }

    private void executeClaimed(BenchmarkJobRequested event) {
        var baselineArtifact = catalog.policy(event.organizationId(), event.baselineVersionId());
        var candidateArtifact = catalog.policy(event.organizationId(), event.candidateVersionId());
        var datasetArtifact = catalog.dataset(event.organizationId(), event.datasetVersionId());
        if (!baselineArtifact.query().equals(candidateArtifact.query())) {
            throw new GuardException("Baseline and candidate policy versions use different decision queries");
        }
        String fingerprint = BenchmarkFingerprint.calculate(event, baselineArtifact, candidateArtifact, datasetArtifact, HARNESS_VERSION);
        var cached = cache.get(fingerprint);
        if (cached.isPresent()) {
            publishExecution(event, rebindCachedExecution(event, fingerprint, cached.get()));
            return;
        }

        var baselinePath = artifactStore.resolvePolicy(event.organizationId(), baselineArtifact.objectKey(), baselineArtifact.sha256());
        var candidatePath = artifactStore.resolvePolicy(event.organizationId(), candidateArtifact.objectKey(), candidateArtifact.sha256());
        var datasetPath = artifactStore.resolveDataset(event.organizationId(), datasetArtifact.objectKey(), datasetArtifact.sha256());
        var benchmarkCases = datasets.load(datasetPath);
        Duration timeout = Duration.ofSeconds(30);
        PolicyBenchmark baseline;
        PolicyBenchmark candidate;
        boolean candidateFirst = Character.digit(fingerprint.charAt(0), 16) % 2 == 0;
        if (candidateFirst) {
            candidate = runner.run("candidate", candidatePath, candidateArtifact.query(), benchmarkCases,
                    event.warmupIterations(), event.measuredIterations(), timeout);
            baseline = runner.run("main", baselinePath, baselineArtifact.query(), benchmarkCases,
                    event.warmupIterations(), event.measuredIterations(), timeout);
        } else {
            baseline = runner.run("main", baselinePath, baselineArtifact.query(), benchmarkCases,
                    event.warmupIterations(), event.measuredIterations(), timeout);
            candidate = runner.run("candidate", candidatePath, candidateArtifact.query(), benchmarkCases,
                    event.warmupIterations(), event.measuredIterations(), timeout);
        }
        baseline = withScalability(baseline, scalabilityRunner.run(baselinePath, baselineArtifact.query(),
                benchmarkCases, event.measuredIterations(), timeout).slope());
        candidate = withScalability(candidate, scalabilityRunner.run(candidatePath, candidateArtifact.query(),
                benchmarkCases, event.measuredIterations(), timeout).slope());
        PolicyBenchmark historical = null;
        if (event.historicalVersionId() != null) {
            var historicalArtifact = catalog.policy(event.organizationId(), event.historicalVersionId());
            if (!baselineArtifact.query().equals(historicalArtifact.query())) {
                throw new GuardException("Historical policy version uses a different decision query");
            }
            var historicalPath = artifactStore.resolvePolicy(event.organizationId(), historicalArtifact.objectKey(), historicalArtifact.sha256());
            historical = runner.run("historical", historicalPath, historicalArtifact.query(), benchmarkCases,
                    event.warmupIterations(), event.measuredIterations(), timeout);
            historical = withScalability(historical, scalabilityRunner.run(historicalPath, historicalArtifact.query(),
                    benchmarkCases, event.measuredIterations(), timeout).slope());
        }
        var execution = new BenchmarkExecutionCompleted(
                UUID.randomUUID(), event.jobId(), event.organizationId(), fingerprint,
                PolicyBenchmarkSnapshot.from(baseline), PolicyBenchmarkSnapshot.from(candidate),
                historical == null ? null : PolicyBenchmarkSnapshot.from(historical), event.thresholds(),
                complexity(complexityAnalyzer.analyze(baselinePath)), complexity(complexityAnalyzer.analyze(candidatePath)),
                clock.instant(), 1);
        String payload = json(execution);
        publishExecution(event, payload);
        cache.put(fingerprint, payload);
    }

    private ScheduledExecutorService leaseHeartbeat(BenchmarkJobRequested event) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("opa-guard-lease-" + event.jobId()).factory());
        executor.scheduleAtFixedRate(() -> {
            try {
                jobs.renewExecutionLease(event.organizationId(), event.jobId(), workerId,
                        clock.instant().plus(LEASE_DURATION));
            } catch (RuntimeException ignored) {
                // The main transaction still owns correctness; an expired lease makes the delivery retryable.
            }
        }, 10, 10, TimeUnit.SECONDS);
        return executor;
    }

    private void publishExecution(BenchmarkJobRequested requested, String payload) {
        if (!jobs.renewExecutionLease(requested.organizationId(), requested.jobId(), workerId,
                clock.instant().plus(LEASE_DURATION))) {
            throw new GuardException("Benchmark execution lease was lost before result publication");
        }
        BenchmarkExecutionCompleted execution;
        try {
            execution = objectMapper.readValue(payload, BenchmarkExecutionCompleted.class);
        } catch (JsonProcessingException exception) {
            throw new GuardException("Benchmark execution payload is invalid", exception);
        }
        transactions.executeWithoutResult(ignored -> {
            var job = jobs.findById(requested.organizationId(), requested.jobId()).orElseThrow();
            if (job.status() == JobStatus.RUNNING) {
                job.transitionTo(JobStatus.ANALYZING, clock.instant());
                jobs.update(job);
            }
            outbox.append(new OutboxRepository.OutboxMessage(
                    execution.eventId(), requested.jobId(), requested.organizationId(), KafkaTopics.SHARD_COMPLETED_V1,
                    BenchmarkExecutionCompleted.class.getSimpleName(), requested.organizationId() + ":" + requested.jobId(),
                    payload, clock.instant(), 0));
        });
    }

    private String rebindCachedExecution(BenchmarkJobRequested requested, String fingerprint, String cachedPayload) {
        try {
            BenchmarkExecutionCompleted cached = objectMapper.readValue(cachedPayload, BenchmarkExecutionCompleted.class);
            BenchmarkExecutionCompleted rebound = new BenchmarkExecutionCompleted(
                    UUID.randomUUID(), requested.jobId(), requested.organizationId(), fingerprint,
                    cached.baseline(), cached.candidate(), cached.historical(), requested.thresholds(),
                    cached.baselineComplexity(), cached.candidateComplexity(), clock.instant(), 1);
            return json(rebound);
        } catch (JsonProcessingException exception) {
            throw new GuardException("Cached benchmark execution is invalid", exception);
        }
    }

    private static BenchmarkJobCompleted.RegoComplexity complexity(RegoComplexityAnalyzer.ComplexityMetrics metrics) {
        return new BenchmarkJobCompleted.RegoComplexity(metrics.files(), metrics.lines(), metrics.rules(),
                metrics.traversals(), metrics.comprehensions(), metrics.score());
    }

    private static PolicyBenchmark withScalability(PolicyBenchmark benchmark, double slope) {
        return new PolicyBenchmark(benchmark.label(), benchmark.policyPath(),
                benchmark.metrics().withScalabilitySlope(slope), benchmark.decisions());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new GuardException("Unable to serialize execution result", exception); }
    }
}
