# OPA Policy Performance Guard

OPA Policy Performance Guard is a Java 21 command-line tool that detects Open Policy Agent (OPA) performance and correctness regressions in pull requests. It runs the same JSON inputs against the main-branch and candidate Rego policies, fails when configured thresholds are exceeded, and emits Markdown and JSON reports.

## What it measures

- Average, p95, and p99 end-to-end `opa eval` latency
- Sequential throughput in operations per second
- Average OPA process CPU time
- Peak resident memory (RSS) sampled from `/proc` on Linux or `ps` on macOS
- Exact JSON decision parity for every named benchmark case

The reported latency includes OPA CLI process startup. This makes comparisons reproducible in CI and catches bundle-loading regressions as well as evaluation regressions. Run both branches on the same runner, as the supplied workflow does; values from separate machines are not directly comparable.

## Prerequisites

- Java 21+
- Maven 3.9+
- [OPA](https://www.openpolicyagent.org/docs/latest/#running-opa) on `PATH`
- Docker only for the optional Testcontainers integration test

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

## Report semantics

Average, p95, and p99 latency each use `maximum-latency-regression-percent` as a gate. Peak RSS uses `maximum-memory-regression-percent`. A value exactly equal to a threshold passes. CPU and throughput are informational. Any decision mismatch fails by default, independently of performance; set `fail-on-decision-change: false` only when another correctness gate owns that responsibility.

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

Unit tests cover dataset validation, warmup exclusion, metric math, threshold boundaries, correctness failures, and Markdown output. `OpaContainerIntegrationTest` uses the pinned official OPA container through Testcontainers and automatically skips when Docker is unavailable.

For low-noise CI measurements, use a dedicated runner class, keep the dataset stable, use at least 30 measured iterations, and increase iterations when branch results are close to the configured threshold.
