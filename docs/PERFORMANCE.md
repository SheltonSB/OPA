# Capacity and performance model

## Target load

Ten billion evaluations/day equals 115,741 evaluations/second averaged across 24 hours. Capacity is planned at 5× average (578,704 evaluations/second) to absorb PR bursts, replays, and regional failover. Benchmark traffic is not production authorization traffic; the policies and datasets model that traffic under controlled load.

An OPA runtime's throughput is policy-dependent. Capacity therefore uses measured saturation throughput `T` rather than a fixed claim:

```text
required runtimes = ceil(peak_evaluations_per_second / (0.60 * T))
```

At a conservative measured `T = 20,000 eval/s`, 49 active runtimes carry 578,704 eval/s at 60% target utilization. The cell provisions 64 plus warm failover capacity. Expensive policies automatically receive more shards and lower per-runtime concurrency.

## Five thousand simultaneous jobs

- 256 Kafka partitions per execution topic provide 256 active ordering lanes per cell; eight cells provide 2,048 lanes. Partition counts increase before traffic reaches 70% sustained utilization.
- Worker HPA permits 2,000 pods per cell, but an organizational weighted-fair scheduler caps noisy tenants.
- A worker accepts only five million measured samples per in-process shard. Larger jobs are partitioned by dataset case range and concurrency level.
- Each shard is targeted at 30–120 seconds. This bounds retry cost and gives autoscaling a useful feedback interval.
- Incremental fingerprints reuse results only when baseline digest, candidate digest, dataset digest, decision query, iterations, thresholds, and harness version match.

## Stored results

One hundred million summary rows at an estimated 4 KiB including indexes and replication is roughly 400 GiB logical and 1.2 TiB at three copies, before headroom. Monthly PostgreSQL partitions support retention and vacuum isolation. Raw samples are much larger: 10 billion 24-byte observations/day is 240 GB/day uncompressed. Delta encoding, histograms, and Zstandard Parquet typically reduce this materially, but petabyte design uses object storage lifecycle tiers rather than PostgreSQL or Prometheus local disks.

## Statistical validity

- Warmups are excluded.
- Main, candidate, and history use the identical immutable dataset and worker class.
- Run order is randomized in production to reduce thermal/time bias.
- p999 needs at least 10,000 observations to represent ten tail samples; the API warns or rejects tail gates with insufficient samples.
- Reports include confidence intervals and flag changes within measurement noise as inconclusive. A production gate should require both a configured percentage and an absolute latency delta.
- Scalability regression is the difference in log-throughput/log-concurrency slope across 1, 2, 4, 8, and 16 clients before saturation.

## Metrics collection

CLI mode records end-to-end `opa eval` wall time, process CPU, and RSS. Distributed mode uses long-lived, per-bundle OPA runtimes. It samples OPA Go metrics before and after a window:

- `go_memstats_alloc_bytes_total` delta / duration → allocation bytes/second
- `go_gc_duration_seconds` histogram delta → GC pause
- cgroup `cpu.stat` delta / wall time → CPU utilization
- cgroup `memory.peak` → peak memory
- HDR histogram → average, p95, p99, p999
- completed evaluations / window → throughput

Zero values from a runtime that cannot expose a metric are marked `unavailable` in the platform contract and never treated as a passing measurement. The local CLI retains zero for backward-compatible JSON and calls this limitation out in its report.

## SLOs

| Service indicator | Objective |
|---|---:|
| Coordinator API availability | 99.95% monthly |
| Accepted command durability | 99.999999% after `202` |
| p99 submission latency | under 250 ms excluding identity provider |
| Queue-to-start at normal load | p95 under 30 s |
| Analysis completion after final shard | p99 under 10 s |
| CI comment propagation | p99 under 30 s |

Alerts cover error-budget burn, Kafka lag age, outbox age, PostgreSQL saturation/replication lag, Redis errors, worker crash loops, dead letters, job lease age, and artifact-integrity failures.
