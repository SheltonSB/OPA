# OPA Policy Performance Guard

[![CI](https://github.com/SheltonSB/OPA/actions/workflows/platform-ci.yml/badge.svg)](https://github.com/SheltonSB/OPA/actions/workflows/platform-ci.yml)
[![CodeQL](https://github.com/SheltonSB/OPA/actions/workflows/codeql.yml/badge.svg)](https://github.com/SheltonSB/OPA/actions/workflows/codeql.yml)
[![Tests (last local snapshot)](docs/badges/tests.svg)](docs/TEST_EVIDENCE.md)
[![Coverage (last local snapshot)](docs/badges/coverage.svg)](docs/TEST_EVIDENCE.md)
[![Release](https://img.shields.io/github/v/release/SheltonSB/OPA)](https://github.com/SheltonSB/OPA/releases/tag/v1.0.0)

OPA Policy Performance Guard is a Java 21 command-line tool that detects Open Policy Agent (OPA) performance and correctness regressions in pull requests. It runs the same JSON inputs against the main-branch and candidate Rego policies, fails when configured thresholds are exceeded, and emits Markdown and JSON reports.

The repository also contains a distributed-platform prototype with independently deployable coordinator, worker, and analyzer roles backed by PostgreSQL, Redis, and Kafka. The CLI is the release-grade path in v1.0.0; the distributed roles have executable code and integration tests but are not represented as production-proven at internet scale. Badge values are a checked-in local snapshot; hosted CI publishes authoritative reports and rejects skipped Docker integration tests.

## Delivery status

| Status | Scope |
|---|---|
| **Implemented and tested** | CLI comparison, paired run-order balancing, exact decision comparison, Markdown/JSON/HTML writers, PostgreSQL idempotency and worker leases, transactional outbox, Kafka redelivery test, Redis fail-open/fail-closed behavior, CI, CodeQL, SBOM generation, container scanning, and release signing workflow |
| **Prototype** | Distributed coordinator/worker/analyzer deployment, Kubernetes manifests, Grafana dashboard, long-lived platform operation, and external CI connectors beyond the supplied pipeline examples |
| **Future architecture / capacity model** | 5,000 simultaneous jobs, 10 billion evaluations/day, active multi-region failover, petabyte historical storage, sustained soak evidence, and external adopter evidence |

No claim in the future row has been load-tested. See the [component guide](docs/COMPONENT_GUIDE.md), [measured local evidence](docs/PERFORMANCE_RESULTS.md), and [capacity model](docs/PERFORMANCE.md) for the boundary between code, tests, and design.

Production design documents:

- [Distributed architecture, package/class/sequence diagrams, schema, Kafka, and deployment](docs/ARCHITECTURE.md)
- [Threat model and security findings with CVSS/exploit/fix evidence](docs/SECURITY.md)
- [Measured benchmark evidence and reproduction details](docs/PERFORMANCE_RESULTS.md)
- [10-billion-evaluations/day capacity model (not a load-test result)](docs/PERFORMANCE.md)
- [v1 component ownership and failure modes](docs/COMPONENT_GUIDE.md)
- [delivery/evidence matrix](docs/EVIDENCE_MATRIX.md)
- [OpenAPI 3.1 contract](docs/openapi/opa-guard-v1.yaml)
- [AsyncAPI Kafka contract](docs/asyncapi/opa-guard-events.yaml)
- [Kubernetes manifests](deploy/kubernetes)
- [Grafana dashboard](observability/grafana/dashboards/opa-guard-overview.json)
- [CI/CD, supply-chain, canary, and rollback design](docs/CICD.md)
- [Maven module-boundary decision](docs/adr/0001-modular-monolith-for-v1.md)

## What it measures

- Average, p95, p99, and p999 end-to-end `opa eval` latency
- Sequential throughput in operations per second
- Average OPA process CPU time
- Peak resident memory (RSS) sampled from `/proc` on Linux or `ps` on macOS
- Exact JSON decision parity for every named benchmark case

Tail gates are sample-size aware: p99 is informational below 100 samples and p999 is informational below 1,000. Those minima prevent a single maximum observation from being mislabeled as a statistically meaningful tail percentile; serious production gates should use substantially larger samples.

The reported latency includes OPA CLI process startup. This makes comparisons reproducible in CI and catches bundle-loading regressions as well as evaluation regressions. Run both branches on the same runner, as the supplied workflow does; values from separate machines are not directly comparable.

## Prerequisites

- Java 21+
- Maven 3.9+
- [OPA](https://www.openpolicyagent.org/docs/latest/#running-opa) on `PATH`
- Docker Engine/Desktop with Docker Compose v2 for the distributed app and integration tests

## Quick start

Build the executable jar:

```bash
mvn verify
```

Run the included policy against itself to validate the setup:

```bash
java -jar target/opa-policy-performance-guard-*.jar \
  --spring.config.additional-location=file:opa-guard.yml
```

Compare two policy trees:

```bash
java -jar target/opa-policy-performance-guard-*.jar \
  --spring.config.additional-location=file:opa-guard.yml \
  --opa-guard.baseline-policy=/work/main/policy \
  --opa-guard.candidate-policy=/work/pull-request/policy \
  --opa-guard.benchmark-dataset=/work/pull-request/benchmark/dataset.json
```

Exit codes are stable for CI use:

| Code | Meaning |
|---:|---|
| `0` | Performance and correctness checks passed |
| `1` | A regression threshold or correctness check failed |
| `2` | Configuration, OPA execution, dataset, or report generation failed |

## Configuration

Copy [`opa-guard.yml`](opa-guard.yml) and customize the `opa-guard` block:

```yaml
opa-guard:
  opa-executable: opa
  query: data.authz.allow
  baseline-policy: /work/main/policy
  candidate-policy: /work/pull-request/policy
  benchmark-dataset: benchmark/dataset.json
  maximum-latency-regression-percent: 10
  maximum-memory-regression-percent: 15
  minimum-iterations: 30
  warmup-iterations: 5
  process-timeout-seconds: 30
  fail-on-decision-change: true
  markdown-output: build/reports/opa-guard.md
  json-output: build/reports/opa-guard.json
```

Spring Boot relaxed binding also accepts the requested underscore names, such as `maximum_latency_regression_percent`, `minimum_iterations`, and `benchmark_dataset`. Any setting can be overridden with a command-line option in canonical form, for example `--opa-guard.minimum-iterations=100`.

The dataset can be a JSON array of raw OPA input objects or named cases. Named cases produce clearer mismatch reports:

```json
[
  {
    "id": "admin-can-delete",
    "input": {
      "user": {"id": "u-1", "role": "admin"},
      "action": "delete",
      "resource": {"owner": "u-2"}
    }
  }
]
```

Case IDs must be unique. Undefined OPA decisions are represented as JSON `null` and compared exactly.

## Pull request automation

The included [GitHub Actions workflow](.github/workflows/opa-policy-performance-guard.yml) checks out the pull request and its exact base commit into separate directories, builds the guard, installs a pinned OPA release, benchmarks both policy trees, and then:

- publishes the Markdown report to the workflow summary;
- creates or updates a single pull-request comment;
- uploads the Markdown and JSON reports as an artifact;
- fails the required check when the guard returns a non-zero result.

The example workflow expects policies under `policy/` and the dataset at `benchmark/dataset.json`. Change the three `--opa-guard.*-policy/dataset` options if the repository uses different paths. For security, comments are skipped for fork-originated pull requests, where the default GitHub token is read-only; the workflow summary and artifact are still produced.

Equivalent pipelines are provided for [GitLab](.gitlab-ci.yml), [Jenkins](Jenkinsfile), and [Azure DevOps](azure-pipelines.yml).

## Distributed platform

> **Prototype status:** these roles are implemented and covered by unit/container integration tests. They have not yet completed a multi-region production soak, a 5,000-job concurrency test, or a 10-billion-evaluation/day load test.

Check the Docker host before starting the distributed topology:

```bash
bash scripts/check-docker.sh
```

The CLI can run directly on a host with OPA installed, but the coordinator,
worker, analyzer, Kafka, PostgreSQL, Redis, Prometheus, and Grafana topology
requires a running Docker daemon. Docker is an external host service; the
application deliberately does not mount or control the host Docker socket.

Build the OCI image with the pinned OPA runtime:

```bash
docker build -t opa-guard:1.0.0 .
```

Run a short Dockerized smoke benchmark (the image bundles OPA, so no host OPA
binary is needed):

```bash
docker run --rm -w /workspace \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  -v "$PWD/policy:/workspace/policy:ro" \
  -v "$PWD/benchmark:/workspace/benchmark:ro" \
  opa-guard:1.0.0 \
  --spring.main.web-application-type=none \
  --opa-guard.baseline-policy=policy \
  --opa-guard.candidate-policy=policy \
  --opa-guard.benchmark-dataset=benchmark/dataset.json \
  --opa-guard.minimum-iterations=1 \
  --opa-guard.warmup-iterations=0 \
  --opa-guard.markdown-output=/tmp/opa-guard-smoke.md \
  --opa-guard.json-output=/tmp/opa-guard-smoke.json
```

The runtime role is selected with `OPA_GUARD_MODE`:

| Mode | Responsibility |
|---|---|
| `coordinator` | Authenticated REST API, tenant admission, idempotent job/outbox creation |
| `worker` | Kafka consumption, artifact integrity checks, OPA execution, incremental result publication |
| `analyzer` | Main/PR/history regression analysis and report persistence/publication |
| `cli` | Local synchronous CI gate; the default for backward compatibility |

For a local dependency topology, create external secrets and run Compose:

```bash
export POSTGRES_PASSWORD='use-a-local-secret-manager'
export REDIS_PASSWORD='use-a-local-secret-manager'
export GRAFANA_ADMIN_PASSWORD='use-a-local-secret-manager'
export OPA_GUARD_JWK_SET_URI='https://your-idp.example/.well-known/jwks.json'
export PROMETHEUS_TOKEN_FILE='/absolute/path/to/a-valid-metrics-reader-jwt-file'
docker compose up --build
```

To validate Compose interpolation without starting containers, set the
required secret variables and run:

```bash
CHECK_COMPOSE_CONFIG=1 bash scripts/check-docker.sh
```

Compose is a developer topology: Kafka, PostgreSQL, and Redis are single-node. Production uses managed multi-AZ services and the manifests in `deploy/kubernetes`. Before applying those manifests:

1. Replace example hostnames and image references with an image digest.
2. Create `opa-guard-secrets` through External Secrets or a cloud secret CSI driver. Required keys are `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_PASSWORD`, and Kafka SASL credentials.
3. Provide the `object-storage-csi` class as an immutable, read-only artifact snapshot.
4. Label dependency and monitoring namespaces to satisfy the default-deny network policies.
5. Configure gateway OIDC, WAF, global quotas, mTLS, and a metrics-reader JWT.

```bash
kubectl apply -k deploy/kubernetes
```

The coordinator API returns `202 Accepted`; callers poll the job resource or consume the CI connector result. API and event schemas are versioned under `docs/`. Production deployment recommendations in the architecture document are future-state guidance unless the delivery-status table marks them implemented.

## Report semantics

Average and p95 latency use `maximum-latency-regression-percent` as gates. p99 and p999 use the same threshold only when their minimum sample counts are met. Peak RSS uses `maximum-memory-regression-percent`. A value exactly equal to a threshold passes. CPU and throughput are informational. Any decision mismatch fails by default, independently of performance; set `fail-on-decision-change: false` only when another correctness gate owns that responsibility.

When a regression is found, a conservative source heuristic checks whether the candidate introduced additional Rego array traversal. It provides a focused recommendation when found and otherwise points to `opa eval --profile`; it never changes the pass/fail result.

## Design

The code is split along clean architecture boundaries:

- `domain`: immutable benchmark and report records
- `opa`: policy-evaluation port and OPA CLI process adapter
- `benchmark`: dataset parsing and measurement orchestration
- `analysis`: threshold/correctness rules and advisory diagnostics
- `report`: Markdown and JSON output adapters
- `cli`: Spring Boot application orchestration and exit-code contract

The benchmark core depends on the `PolicyEvaluator` interface rather than Spring or `ProcessBuilder`, allowing deterministic unit tests and alternative OPA transports. Spring owns dependency injection only at the outer application boundary.

## Testing

```bash
mvn verify
```

For the opt-in full NVD dependency scan (in addition to pull-request
Dependency Review and container Trivy checks), run:

```bash
NVD_API_KEY=... mvn --batch-mode --no-transfer-progress -Psecurity verify
```

The `security` profile fails on CVSS 7.0+ findings and writes HTML/JSON
reports under `target/`. Keep the NVD cache between runs; the public feed is
rate-limited when no API key is configured.

Unit tests cover dataset validation, warmup exclusion, alternating paired order, metric math, sample-aware tail gates, threshold boundaries, correctness failures, duplicate worker/analyzer events, Redis failure behavior, and report output. Docker-backed tests cover OPA execution, concurrent PostgreSQL idempotency, outbox contention, expired worker-lease recovery, and Kafka redelivery. They may skip when Docker is unavailable for local development; the GitHub CI gate fails if any container integration report is skipped.

For low-noise CI measurements, use a dedicated runner class, keep the dataset stable, use at least 30 measured iterations, and increase iterations when branch results are close to the configured threshold.
