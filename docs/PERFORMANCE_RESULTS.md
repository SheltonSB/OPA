# Measured performance evidence

This page records the benchmark that is checked into the v1.0.0 repository. It is evidence that the CLI completed a real OPA comparison and produced stable report artifacts. It is **not** evidence for the modeled 10-billion-evaluations/day platform target.

## Environment

| Item | Value |
|---|---|
| Date | 2026-07-18 |
| Release | v1.0.0 release candidate |
| Host | MacBook Pro `Mac16,1` |
| Processor | Apple M4, 10 cores (4 performance + 6 efficiency) |
| Memory | 16 GB |
| Operating system | macOS 26.5, Darwin 25.5.0, arm64 |
| Java runtime | OpenJDK 23.0.2; Maven compiler release 21 |
| Maven | 3.9.9 |
| OPA | 1.16.2, darwin/arm64 |
| OPA SHA-256 | `93877f01c21bb8aa8794833d0d603c92d001b3c0ce3015630aa778b73e12cf02` |
| Power/isolation | Developer laptop; no CPU pinning or exclusive-runner isolation |

## Workload and result

Both sides used the same [`policy/`](../policy) directory and the three-case [`benchmark/dataset.json`](../benchmark/dataset.json). The harness performed 20 warmup iterations and 100 measured iterations, producing 300 measured samples per policy. It alternated which policy ran first for each pair.

| Metric | Main | Candidate | Change |
|---|---:|---:|---:|
| Average latency | 20.00 ms | 20.07 ms | +0.32% |
| p95 latency | 22.79 ms | 22.89 ms | +0.46% |
| p99 latency | 24.35 ms | 23.73 ms | -2.56% |
| Sequential throughput | 49.99 ops/s | 49.83 ops/s | -0.32% |
| Decisions | 3/3 expected | 3/3 identical | PASS |

The checked-in [Markdown report](examples/benchmark-report.md) and [JSON report](examples/benchmark-report.json) are the unedited outputs. The example intentionally uses a 50% latency threshold because per-evaluation OPA CLI startup on a shared developer laptop has high tail variance. An earlier 300-sample run with a 10% gate failed p95/p99 despite comparing the policy to itself; publishing that as a policy regression would have been misleading. Dedicated CI runners and more observations are required for tighter tail gates.

p999 is shown but is informational at 300 samples. The CLI does not expose allocation rate or OPA GC pauses, and process memory sampling returned unavailable on this run, represented by zero in the v1 JSON compatibility contract. These fields are not claimed as measured evidence here.

## Reproduction

Download OPA 1.16.2 for the target platform, verify its vendor checksum, then run:

```bash
mvn clean verify
java -jar target/opa-policy-performance-guard-1.0.0.jar \
  --spring.main.banner-mode=off \
  --opa-guard.opa-executable=/absolute/path/to/opa-1.16.2 \
  --opa-guard.baseline-policy=policy \
  --opa-guard.candidate-policy=policy \
  --opa-guard.benchmark-dataset=benchmark/dataset.json \
  --opa-guard.minimum-iterations=100 \
  --opa-guard.warmup-iterations=20 \
  --opa-guard.maximum-latency-regression-percent=50 \
  --opa-guard.maximum-memory-regression-percent=15 \
  --opa-guard.markdown-output=docs/examples/benchmark-report.md \
  --opa-guard.json-output=docs/examples/benchmark-report.json
```

Absolute latency will vary by host. Regression comparisons are meaningful only when both policy versions run on the same named runner under comparable load.

## Evidence still required

The following are portfolio targets, not current claims: a 10–30 minute sustained run, 500/1,000 completed jobs, 5,000 simultaneous jobs, a live Kubernetes deployment, Redis/PostgreSQL fault injection against a deployed environment, Prometheus/Grafana screenshots, and an external adopter.
