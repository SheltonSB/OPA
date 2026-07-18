# Changelog

All notable changes are documented here. The project follows semantic versioning.

## 1.0.0 — 2026-07-18

- Release the Java 21 CLI with paired OPA benchmarking, correctness comparison, Markdown/JSON/HTML reports, and sample-aware p99/p999 gates.
- Add the distributed coordinator, worker, analyzer, PostgreSQL outbox/lease state, Kafka transport, Redis admission/cache behavior, Kubernetes manifests, and Grafana dashboard as an explicitly labeled prototype.
- Add concurrency and fault integration tests for idempotent submission, outbox contention, Kafka redelivery, expired worker recovery, Redis failure, and database failure.
- Bind Java timestamps explicitly in PostgreSQL adapters; the real container suite caught the driver incompatibility before the release tag.
- Add Dependabot, CodeQL, JaCoCo evidence, CycloneDX SBOMs, Trivy container scans, keyless Cosign image signing, and GitHub artifact attestations.
- Publish a reproducible example benchmark from a named Apple M4 environment and separate measured evidence from future capacity modeling.
