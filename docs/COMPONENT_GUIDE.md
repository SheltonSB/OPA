# Component guide

This is the shortest path to explaining the code in a technical interview or design review. “Implemented” means executable code exists. It does not imply internet-scale production validation.

| Component | Why it exists | Boundary and failure behavior | Evidence | Status |
|---|---|---|---|---|
| CLI orchestration | Gives repositories a self-contained PR gate | Loads one immutable dataset, invokes paired benchmarking, analyzes, writes reports, and returns a stable exit code | CLI/unit tests and checked-in real report | Implemented |
| `PolicyEvaluator` adapters | Keep OPA process details outside the benchmark domain | CLI mode uses argument arrays rather than a shell, deadlines, bounded output, and process cleanup; worker mode uses a bounded loopback OPA server pool | unit tests + OPA container integration; no worker-pool soak yet | CLI implemented / pool prototype |
| Benchmark runner | Produces comparable samples | Alternates main/candidate run order; caps samples; separates warmup and measurement | paired-order and metric tests | Implemented |
| Regression analyzer | Owns deterministic pass/fail policy | Exact decision parity; configured percentage gates; p99/p999 minimum sample sizes | boundary and correctness tests | Implemented |
| Coordinator | Accepts asynchronous multi-tenant jobs | Validates tenant/idempotency input and atomically writes job + outbox; DB failure rejects submission | unit and PostgreSQL integration tests | Prototype |
| Transactional outbox | Prevents accepted jobs from being lost between PostgreSQL and Kafka | Concurrent relays claim disjoint rows; delivery remains at least once | PostgreSQL contention test | Prototype |
| Distributed worker | Isolates expensive execution from the API | Atomically claims a lease, heartbeats while running, releases on controlled failure, and permits recovery after lease expiry | lease-recovery test; worker unit paths | Prototype |
| Kafka transport | Supplies durability, replay, ordering, and backpressure | Consumers are at least once; duplicate delivery is expected and handled through durable state | real redelivery integration test | Prototype |
| Redis admission/cache | Coordinates replica-local admission and incremental reuse | Admission fails closed; optional cache fails open; Redis is never the system of record | failure-behavior tests | Prototype |
| PostgreSQL persistence | Holds tenant, job, result, report, lease, and outbox state | Constraints, optimistic versions, row-level security, and short transactions protect consistency | Testcontainers contention/idempotency tests | Prototype |
| Analyzer/reporting role | Separates CPU-light analysis from benchmark execution | Replays completed events, upserts reports, and emits completion events | analyzer/unit tests | Prototype |
| Kubernetes/observability | Documents deployable roles and operational signals | Restricted pod settings, probes, PDB/HPA resources, Prometheus metrics, Grafana JSON | manifest rendering only; no live cluster evidence | Prototype |
| Multi-region cells and petabyte archive | Describes an evolution path after real demand | Home-region routing, asynchronous replication, and object-storage raw samples | capacity model only | Future architecture |

## Request lifecycle

```text
CI -> coordinator -> PostgreSQL job + outbox -> Kafka -> leased worker
   -> OPA measurements -> Kafka -> analyzer -> report -> CI status/comment
```

The correctness invariant is: once the coordinator returns `202`, the job and an event intent exist in the same database transaction. The delivery invariant is: Kafka and workers may repeat work, but a live or terminal durable job claim prevents duplicate completion. Redis may degrade admission or caching, but it cannot invent or erase the system-of-record state.

## Deliberate non-features in v1

There is no arbitrary callback URL, embedded Rego execution in the coordinator, global PostgreSQL writer, or automatic LLM policy rewrite. Those omissions reduce SSRF, noisy-neighbor, cross-region consistency, and nondeterministic-change risk respectively.
