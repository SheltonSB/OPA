# Delivery and evidence matrix

This page is the reviewer's boundary between code that is runnable today and
architecture that still needs operational proof. A design document is not
treated as a benchmark result.

| Area | Implemented now | Evidence in this repository | Not claimed |
|---|---|---|---|
| Local CLI | Java 21 Spring Boot executable; paired baseline/candidate `opa eval`; correctness, latency, Markdown/JSON/HTML reports | JUnit unit tests, checked-in [example report](examples/benchmark-report.md), reproducible [M4 run](PERFORMANCE_RESULTS.md) | Production service SLO or cross-machine latency equivalence |
| Policy safety | Bounded datasets/output, fixed argument vectors, artifact hashing and symlink checks, decision parity gate | `docs/SECURITY.md`, OPA evaluator tests, CodeQL/Trivy/SBOM workflows | Independent penetration test or container-escape certification |
| Coordinator | Tenant-scoped API, validation, idempotent job creation, transactional outbox | API tests and PostgreSQL schema/integrity tests | Multi-region API availability |
| Worker | Leases, heartbeat/recovery, duplicate-claim suppression, incremental fingerprint cache, OPA execution | Worker idempotency test and lease-recovery integration test | 5,000-job concurrency or long-duration worker soak |
| Kafka/analyzer | At-least-once listener, replay-safe terminal analysis and report upsert | Kafka redelivery test plus duplicate worker/analyzer tests | Broker outage/rebalance chaos in a deployed cluster |
| PostgreSQL/Redis | RLS schema, optimistic/versioned job state, outbox `SKIP LOCKED`, Redis admission/cache degradation policy | Testcontainers tests and failure-behavior tests | Managed database failover, replica lag, or production contention profile |
| Docker/Compose | Reproducible OCI image, pinned OPA runtime, Compose topology for coordinator/worker/analyzer plus Kafka/PostgreSQL/Redis/Prometheus/Grafana | `scripts/check-docker.sh`, `docker compose config`, `docker compose up --build` | A live multi-node Docker or Kubernetes deployment |
| Kubernetes/observability | Coordinator/worker/analyzer manifests, probes, limits, network policies, Prometheus/Grafana definitions | `kubectl kustomize deploy/kubernetes`, checked-in dashboard | A live cluster deployment or Grafana screenshot |
| Supply chain | Dependabot, CodeQL, dependency review, opt-in OWASP Dependency-Check, CycloneDX SBOM, Trivy, Cosign/attestations | `.github/workflows`, `pom.xml`, release workflow | A successful public release run cannot be inferred from local git metadata |
| Scale roadmap | Partitioning, quotas, backpressure, multi-region and object-storage design | [capacity model](PERFORMANCE.md) and [architecture](ARCHITECTURE.md) | 10 billion evaluations/day, 5,000 simultaneous jobs, petabyte history |

## Promotion rules

- **Implemented** means the code path exists, has automated evidence, and its
  operational limits are documented.
- **Prototype** means the code path is executable and testable but still lacks
  a deployed failure/scale trial. Prototype components are not a production
  readiness claim.
- **Future architecture** means a sizing hypothesis or design option only.
  The 10-billion-evaluations/day figure is capacity modeling, never a measured
  throughput result.

The next evidence milestones are intentionally external to the source tree:
run 500–1,000 jobs for 10–30 minutes, kill and recover workers, inject Kafka/
Redis/PostgreSQL failures, deploy the manifests to a real cluster, capture
Prometheus/Grafana output, and have an external team consume the CLI. Until
those artifacts are attached to a release, the corresponding rows remain
prototype or future architecture.
