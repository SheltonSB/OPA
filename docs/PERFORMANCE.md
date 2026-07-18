# Future-state capacity model — not load-test evidence

> **Status: capacity modeling.** None of the 10-billion-evaluations/day, 5,000-simultaneous-job, multi-region, or petabyte figures below has been validated by a deployed load test. They are sizing hypotheses and design constraints. See [measured performance evidence](PERFORMANCE_RESULTS.md) for the much smaller real v1.0.0 run.

## Target load

Ten billion evaluations/day mathematically equals 115,741 evaluations/second averaged across 24 hours. This model applies a hypothetical 5× peak factor (578,704 evaluations/second) for PR bursts, replays, and regional failover. Benchmark traffic is not production authorization traffic; a future load test must establish both the peak factor and per-policy service time.

An OPA runtime's throughput is policy-dependent. Capacity therefore uses measured saturation throughput `T` rather than a fixed claim:

```text
required runtimes = ceil(peak_evaluations_per_second / (0.60 * T))
```

For illustration, if a future saturation test measured `T = 20,000 eval/s`, 49 active runtimes would carry 578,704 eval/s at 60% target utilization. `20,000 eval/s` is an example input, not a result produced by this repository. A modeled cell would then provision 64 plus warm failover capacity.

## Five thousand simultaneous jobs (modeled)

- 256 Kafka partitions per execution topic provide 256 active ordering lanes per cell; eight cells provide 2,048 lanes. Partition counts increase before traffic reaches 70% sustained utilization.
- Worker HPA permits 2,000 pods per cell, but an organizational weighted-fair scheduler caps noisy tenants.
- A worker accepts only five million measured samples per in-process shard. Larger jobs are partitioned by dataset case range and concurrency level.
- Each shard is targeted at 30–120 seconds. This bounds retry cost and gives autoscaling a useful feedback interval.
- Incremental fingerprints reuse results only when baseline digest, candidate digest, dataset digest, decision query, iterations, thresholds, and harness version match.

## Stored results (modeled)

One hundred million summary rows at an estimated 4 KiB including indexes and replication is roughly 400 GiB logical and 1.2 TiB at three copies, before headroom. Monthly PostgreSQL partitions support retention and vacuum isolation. Raw samples are much larger: 10 billion 24-byte observations/day is 240 GB/day uncompressed. Delta encoding, histograms, and Zstandard Parquet typically reduce this materially, but petabyte design uses object storage lifecycle tiers rather than PostgreSQL or Prometheus local disks.

## Statistical validity

- Warmups are excluded.
- Main, candidate, and history use the identical immutable dataset and worker class.
- Run order is randomized in production to reduce thermal/time bias.
- The v1 analyzer refuses to gate p99 below 100 observations and p999 below 1,000. Those are safety floors, not recommendations; 10,000 observations gives only ten observations in the highest 0.1% and is a more credible starting point for p999 analysis.
- Reports include confidence intervals and flag changes within measurement noise as inconclusive. A production gate should require both a configured percentage and an absolute latency delta.
- Scalability regression is the difference in log-throughput/log-concurrency slope across 1, 2, 4, 8, and 16 clients before saturation.

## Metrics collection: current versus future

CLI mode currently records end-to-end `opa eval` wall time and attempts process CPU/RSS sampling. Availability is platform-dependent and unavailable values are explicit in report interpretation. Worker mode implements a bounded pool of long-lived, loopback-only OPA servers and parses OPA allocation/GC metrics, but it has not been soak-tested or validated under Kubernetes cgroup isolation. A hardened deployment would sample:

- `go_memstats_alloc_bytes_total` delta / duration → allocation bytes/second
- `go_gc_duration_seconds` histogram delta → GC pause
- cgroup `cpu.stat` delta / wall time → CPU utilization
- cgroup `memory.peak` → peak memory
- HDR histogram → average, p95, p99, p999
- completed evaluations / window → throughput

Zero values from a runtime that cannot expose a metric are marked `unavailable` in the platform contract and never treated as a passing measurement. The local CLI retains zero for backward-compatible JSON and calls this limitation out in its report.

## Proposed SLOs (not achieved-SLO claims)

| Service indicator | Objective |
|---|---:|
| Coordinator API availability | 99.95% monthly |
| Accepted command durability | 99.999999% after `202` |
| p99 submission latency | under 250 ms excluding identity provider |
| Queue-to-start at normal load | p95 under 30 s |
| Analysis completion after final shard | p99 under 10 s |
| CI comment propagation | p99 under 30 s |

Alerts cover error-budget burn, Kafka lag age, outbox age, PostgreSQL saturation/replication lag, Redis errors, worker crash loops, dead letters, job lease age, and artifact-integrity failures.
