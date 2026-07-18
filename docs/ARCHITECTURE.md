# Distributed architecture

## System context

```text
Developer / CI client
        |
        v
Global DNS + API Gateway (OIDC, WAF, quotas)
        |
        v
Regional L7 Load Balancer
        |
        v
+----------------------- regional cell -------------------------+
| Benchmark Coordinator                                         |
|   | transactional write                                       |
|   +--> PostgreSQL <--> read replicas                           |
|   +--> Redis (rate limits, fingerprints, hot reads)            |
|   +--> Outbox Relay                                            |
|            |                                                   |
|            v                                                   |
|          Kafka: benchmark-job-requested.v1                     |
|            |                                                   |
|            v                                                   |
| Distributed Worker Cluster (Kafka consumer group)              |
|            |                                                   |
|            v                                                   |
| OPA Runtime Pool (isolated process/cgroup per benchmark)        |
|            |                                                   |
|            v                                                   |
| Metrics Collector --> Kafka: benchmark-shard-completed.v1      |
|                              |                                 |
|                              v                                 |
|                     Regression Analyzer                        |
|                              |                                 |
|                              v                                 |
|                       Policy Advisor                           |
|                              |                                 |
|             +----------------+------------------+              |
|             v                v                  v              |
|        PostgreSQL        Prometheus        Object storage       |
|        summaries         live SLOs         Parquet raw samples  |
|             |                |                  |              |
|             +-----------> Grafana <-------------+              |
+---------------------------------------------------------------+
                              |
                              v
                   GitHub/GitLab/Jenkins/Azure status
                   and pull-request Markdown comment
```

The requested logical chain—client, gateway, load balancer, coordinator, workers, OPA pool, metrics, Kafka, analyzer, advisor, PostgreSQL, Redis, Prometheus, Grafana, and PR comments—is shown as a dataflow. PostgreSQL, Redis, Prometheus, and object storage are parallel persistence/serving planes rather than a literal serial request path.

## Component decisions

| Component | Responsibility and choice | Alternatives and why not selected |
|---|---|---|
| API gateway | Validates OIDC tokens, applies WAF rules, request-size limits, global quotas, and region routing before traffic reaches Java. Managed gateway reduces undifferentiated control-plane work. | Envoy/Kong are appropriate for on-premises deployments; direct ingress lacks global quotas and DDoS integration. |
| Regional load balancer | Health-aware L7 balancing across coordinator pods and zones. TLS terminates here and is re-established with mTLS inside the mesh. | L4 balancing cannot make HTTP-aware admission decisions. |
| Coordinator | Stateless Spring Boot service that validates commands, enforces tenant access and idempotency, writes the job and outbox atomically, then returns `202`. | Synchronous benchmarking would couple API availability to untrusted policy execution. |
| PostgreSQL | System of record for organizations, immutable artifact metadata, job state, summarized results, reports, idempotency keys, and outbox rows. RLS is defense in depth for tenant isolation. | DynamoDB/Spanner provide easier global writes, but PostgreSQL was required and supports strong relational constraints. The deployment uses cell-local HA clusters rather than a single global primary. |
| Redis | Admission counters and 30-day incremental-result cache. It is never the system of record. Rate limiting fails closed; result caching fails open. | In-process caches cannot coordinate replicas. PostgreSQL counters create hot rows. |
| Kafka | Durable, ordered-per-job, backpressured execution and analysis streams. Replication factor 3, minimum ISR 2, idempotent producers, `read_committed` consumers. | SQS/Pub/Sub are viable managed alternatives, but Kafka supplies partition ordering, retention, and replay. |
| Worker cluster | Consumer group whose members resolve immutable artifacts, verify SHA-256, execute OPA, and publish measurements. Workers use Java virtual threads for blocking process and local HTTP I/O; CPU-bound analysis uses bounded platform-thread pools. | Running policy code in coordinators would expand blast radius. Unbounded virtual-thread concurrency would oversubscribe CPU. |
| OPA runtime pool | Long-lived OPA processes amortize startup for load/scalability runs; the CLI adapter remains for hermetic CI. Each runtime is assigned one immutable bundle, a CPU/memory cgroup, PID namespace, seccomp profile, deadline, and evaluation budget. Go runtime metrics provide allocation and GC deltas. | One process per request is simple but makes startup dominate and cannot measure sustained scalability. Shared multi-tenant OPA processes create noisy-neighbor and data-leak risks. |
| Metrics collector | Produces HDR-style latency distributions and resource deltas; report summaries include average, p95, p99, p999, throughput, CPU, RSS, allocation rate, and GC pause. Raw samples are compressed and written to object storage. | Storing raw samples in PostgreSQL makes the primary database a petabyte-scale time-series system. |
| Regression analyzer | Independently scales Kafka consumers, compares candidate with both main and historical baselines, validates decision digests, and applies per-metric thresholds. | Analysis in workers ties expensive execution and lightweight analysis scaling together. |
| Policy advisor | Conservative static Rego complexity analysis identifies new traversals/comprehensions and links evidence to recommendations. It does not decide pass/fail without a configured gate. | LLM-only advice is nondeterministic and can disclose policies; it may be added behind an explicit tenant opt-in. |
| Prometheus/Grafana | Prometheus serves operational SLOs and 15-day high-resolution metrics; Grafana visualizes live health and queries the long-term metrics store for history. | Prometheus alone is not a petabyte archive. Thanos/Mimir is the production long-term backend. |
| CI connectors | Consume `analysis-completed.v1` using installation IDs stored in a secret manager, update one marker comment, and set a commit check. The repository workflow demonstrates local mode without platform credentials. | Arbitrary callback URLs are intentionally not accepted because they introduce SSRF. |

## Multi-region cells

```text
                    Global DNS / Anycast Gateway
                       /          |          \
                      v           v           v
                 us-east cell  us-west cell  eu-west cell
                 PG HA + Kafka PG HA + Kafka PG HA + Kafka
                      \           |           /
                       +-- replicated object storage --+
                       +-- global tenant directory -----+
```

Each organization has a `home_region`. Writes and Kafka ordering stay within that cell. The global tenant directory is small and strongly consistent. A region outage causes the gateway to route to the designated disaster-recovery cell, which promotes an asynchronous PostgreSQL replica and resumes Kafka from mirrored topics. Recovery targets are RPO under five minutes and RTO under fifteen minutes; organizations requiring RPO zero use synchronous dual-region PostgreSQL at the latency cost. Raw samples and policy artifacts use cross-region, versioned object replication.

This avoids a single global PostgreSQL writer and limits failure domains. Cells can be added without repartitioning every tenant.

## Package structure

```text
dev.opaguard
├── domain/                       local benchmark value objects
├── opa/                          OPA evaluation port + CLI adapter
├── benchmark/                    dataset loader + harness
├── analysis/                     correctness and regression rules
├── report/                       JSON, Markdown, HTML adapters
├── cli/                          hermetic CI entry point
└── platform/
    ├── domain/                   job aggregate and versioned events
    ├── application/              use cases
    ├── port/                     repositories and artifact-store ports
    ├── api/                      REST adapter
    ├── security/                 JWT tenancy and Redis admission
    ├── persistence/              PostgreSQL adapters
    ├── messaging/                Kafka/outbox adapters
    ├── artifact/                 content-addressed filesystem adapter
    ├── worker/                   distributed execution consumer
    ├── analyzer/                 regression consumer
    └── analysis/                 fingerprint and complexity services
```

Dependencies point inward: domain objects have no Spring dependency; application services depend on ports; adapters implement ports. Spring configuration and conditional roles are at the outer boundary.

## Class diagram

```text
+----------------------+       uses       +-------------------------+
| SubmitBenchmarkJob   |----------------->| BenchmarkJobRepository  |
| application service  |                  | <<port>>                |
+----------+-----------+                  +-----------^-------------+
           | appends                                  |
           v                                          | implements
+----------------------+                  +-----------+-------------+
| OutboxRepository     |<-----------------| JdbcBenchmarkJobRepo    |
| <<port>>             |                  +-------------------------+
+----------^-----------+
           | implements
+----------+-----------+       publishes  +-------------------------+
| JdbcOutboxRepository |<-----------------| OutboxRelay             |
+----------------------+                  +-------------------------+

+---------------------------+             +-------------------------+
| BenchmarkJobKafkaListener |------------>| DistributedBenchmark   |
+---------------------------+             | Worker                  |
                                              | uses
                    +-------------------------+----------------------+
                    v                         v                      v
             +--------------+          +-------------+       +--------------+
             | ArtifactStore|          |BenchmarkRunner|      |ArtifactCatalog|
             | <<port>>     |          +------^------+       | <<port>>     |
             +--------------+                 |              +--------------+
                                      +-------+--------+
                                      |PolicyEvaluator |
                                      | <<port>>       |
                                      +-------^--------+
                                              |
                                      +-------+--------+
                                      |OpaCliEvaluator |
                                      +----------------+

+-------------------------------+     +----------------------------+
| RegressionAnalysisKafkaListener|--->| RegressionAnalysisService  |
+-------------------------------+     +-------------+--------------+
                                                   |
                              +--------------------+-------------------+
                              v                    v                   v
                     +------------------+  +----------------+  +---------------+
                     |RegressionAnalyzer|  |ReportRepository|  |OutboxRepository|
                     +------------------+  +----------------+  +---------------+
```

Patterns are used for actual variability: repository and strategy ports, a builder for aggregate rehydration, dependency injection at adapters, domain transitions as an aggregate, and Kafka observers for completed work. There is no ceremonial factory where constructors suffice.

## Submission sequence

```text
CI       Gateway     Coordinator       Redis       PostgreSQL       Outbox       Kafka
| POST      |             |               |              |              |           |
|---------->| JWT/WAF     |               |              |              |           |
|           |------------>| tenant check  |              |              |           |
|           |             |-------------->| INCR/EXPIRE  |              |           |
|           |             | begin tx      |              |              |           |
|           |             |----------------------------->| INSERT job   |           |
|           |             |----------------------------->| INSERT outbox|           |
|           |             | commit        |              |              |           |
|<----------|<------------| 202 + job URI  |              |              |           |
|           |             |               |              | OutboxRelay  |           |
|           |             |               |              |------------->| send      |
|           |             |               |              |              |---------->|
```

## Execution and analysis sequence

```text
Kafka      Worker       Artifact store     OPA pool       Kafka       Analyzer      PostgreSQL    CI connector
  | job       |                |               |             |            |              |             |
  |---------->| resolve IDs    |               |             |            |              |             |
  |           |--------------->| SHA verify    |             |            |              |             |
  |           | fingerprint/cache check        |             |            |              |             |
  |           |------------------------------->| warmup      |            |              |             |
  |           |------------------------------->| measured    |            |              |             |
  |           | collect decisions + telemetry  |             |            |              |             |
  |           |---------------------------------------------->| execution  |              |             |
  |           |                                |             |----------->| compare      |             |
  |           |                                |             |            |------------->| report tx   |
  |           |                                |             |            | analysis event            |
  |           |                                |             |<-----------|              |             |
  |           |                                |             |-------------------------->| comment/check
```

Kafka delivery is at least once. Job state transitions, unique idempotency keys, report upserts, event IDs, and terminal-state checks make consumers idempotent. A worker crash after `RUNNING` is detected by a reconciler lease and safely requeued.

## Consistency and concurrency

- Commands use strong consistency in the tenant's home region.
- Status/read projections and dashboards are eventually consistent, normally under one second.
- PostgreSQL optimistic versions prevent lost job transitions.
- `FOR UPDATE SKIP LOCKED` claims outbox batches without a distributed mutex.
- Kafka partitions are keyed by `organizationId:jobId`, preserving per-job order while distributing tenants.
- Event sourcing is deliberately not the system of record. Immutable integration events are retained for replay, while the job aggregate remains in PostgreSQL; full event sourcing would add operational and privacy-erasure complexity without a compensating need.
- CQRS is applied pragmatically: coordinator writes normalized aggregates; read replicas/materialized projections serve list/report queries.

## Database model

The executable schema is [`V1__platform_schema.sql`](../src/main/resources/db/migration/V1__platform_schema.sql).

```text
organizations 1---* policy_versions
      |        1---* dataset_versions
      |        1---* benchmark_jobs *---1 policy_versions (baseline/candidate/history)
      |                       |
      |                       +---* benchmark_results (monthly range partitions)
      |                       +---1 benchmark_reports
      +---* outbox_events
```

Summary result partitions are monthly, indexed by `(organization_id, job_id, completed_at)`. A scheduled DDL controller creates partitions 90 days ahead and migrates rows out of the default partition. Tenant cells are assigned by rendezvous hashing on organization ID; large tenants can receive dedicated cells. PostgreSQL keeps 100 million summaries; raw histogram/sample data is Parquet in object storage partitioned by `region/date/organization/job`, queried through Trino and compacted daily.

## Kafka topics

| Topic | Key | Partitions/cell | Retention | Producer → consumer |
|---|---|---:|---:|---|
| `opa.guard.benchmark-job-requested.v1` | `org:job` | 256 | 7 days | coordinator outbox → workers |
| `opa.guard.benchmark-shard-completed.v1` | `org:job` | 256 | 7 days | worker outbox → analyzers |
| `opa.guard.analysis-completed.v1` | `org:job` | 128 | 30 days | analyzer outbox → CI connectors/read projections |
| `opa.guard.dead-letter.v1` | original key | 64 | 30 days | retry recovery → operations/replay tool |

Payload schemas are versioned, reject unknown major versions, carry event IDs and UTC timestamps, and are documented in [`opa-guard-events.yaml`](asyncapi/opa-guard-events.yaml). Schema Registry compatibility is `BACKWARD_TRANSITIVE`. Production uses RF=3, min ISR=2, `acks=all`, idempotent producers, rack awareness, TLS, and SCRAM or workload identity.

## Retry, circuit breaking, and backpressure

- HTTP clients retry only idempotent operations with full-jitter exponential backoff and a total deadline.
- Kafka consumers retry seven times from 1 to 60 seconds, then publish to the dead-letter topic.
- Outbox rows use a 30-second lease and at most 20 attempts; duplicates remain possible and are safe.
- Circuit breakers protect Kafka/JWK/object-store calls; PostgreSQL failures fail API writes rather than accepting work that cannot be recorded.
- Consumer concurrency never exceeds allocated CPU slots. Kafka lag drives KEDA/HPA scaling; partitions bound maximum parallelism.
- Per-organization admission is 60 submissions/minute by default plus quotas on concurrent jobs, evaluations, artifact bytes, and daily CPU-seconds.

## Deployment and zero downtime

Kubernetes manifests are under [`deploy/kubernetes`](../deploy/kubernetes). Coordinators and analyzers use `maxUnavailable: 0`; workers drain Kafka and have a 120-second termination grace period. Pod disruption budgets, zone spreading, readiness/startup probes, restricted pod security, read-only filesystems, dropped capabilities, default-deny network policies, and HPA limits are included. Database migrations obey expand/migrate/contract: additive Flyway changes deploy first, old and new application versions coexist, backfill runs, and destructive cleanup happens in a later release.

Production dependencies are managed, multi-AZ PostgreSQL, Kafka, Redis, object storage, Prometheus/Mimir, and Grafana—not the single-node Compose services. Images are promoted by digest, signed with keyless Cosign, accompanied by an SBOM and provenance, then rolled through canary cells before global deployment.
