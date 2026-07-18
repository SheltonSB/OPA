## ✅ OPA Policy Performance Guard: PASS

| Metric | Main | PR | Regression | Threshold | Status |
|---|---:|---:|---:|---:|:---:|
| Average latency (ms) | 20.00 ms | 20.07 ms | +0.32% | 50.00% | PASS |
| p95 latency (ms) | 22.79 ms | 22.89 ms | +0.46% | 50.00% | PASS |
| p99 latency (ms) | 24.35 ms | 23.73 ms | -2.56% | 50.00% | PASS |
| p999 latency (ms) (informational; needs 1000+ samples) | 30.92 ms | 25.89 ms | -16.28% | 50.00% | PASS |
| Peak memory (bytes) | 0.00 MiB | 0.00 MiB | +0.00% | 15.00% | PASS |

### Additional metrics

| Branch | Throughput (ops/s) | Avg CPU (ms) | CPU utilization | Allocation rate | GC pause | Samples |
|---|---:|---:|---:|---:|---:|---:|
| Main | 49.99 | 0.00 | 0.00% | 0.00 B/s | 0.00 ms | 300 |
| PR | 49.83 | 0.00 | 0.00% | 0.00 B/s | 0.00 ms | 300 |

**Decision correctness:** PASS — all benchmark decisions are identical.

<sub>Generated at 2026-07-18T16:57:28.989110Z</sub>
