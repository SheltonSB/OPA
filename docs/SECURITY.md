# Security assessment and threat model

Assessment date: 2026-07-18. CVSS values are CVSS 3.1 base estimates unless an upstream score is cited. The review covers source, configuration, container, Kubernetes, dependency, and event trust boundaries. A passing automated scan is not a substitute for an independent penetration test before handling regulated policies.

Local dependency-scan status: the CycloneDX SBOM was generated successfully. An earlier OWASP Dependency Check attempt could not complete its unauthenticated NVD synchronization, so no clean local vulnerability-scan claim is made. Pull requests use GitHub dependency review; CI and release workflows scan OCI images with Trivy. The release workflow signs and attests artifacts, but its first successful public run remains the evidence boundary. Spring Boot's known 2026 issue identified during manual review was fixed as SEC-001.

## Assets and adversaries

Critical assets are proprietary Rego policies, benchmark datasets that may contain production-shaped identities, authorization decisions, Git provider installation tokens, tenant metadata, and performance history. Adversaries include an unauthenticated Internet client, a malicious tenant user, a compromised CI job, a malicious Rego author, a compromised worker, and an operator with excessive database access.

Trust boundaries are the public gateway, tenant JWT boundary, Kafka topics, PostgreSQL RLS boundary, artifact store, OPA process sandbox, CI connector egress, and region replication. Policy and dataset content is always untrusted.

## Fixed findings

| ID | Issue and why it existed | Severity / CVSS | Exploit scenario | Implemented fix |
|---|---|---:|---|---|
| SEC-001 | The original Spring Boot 3.4.5 dependency is affected by CVE-2026-40975's weak random property source. | High, NVD 7.5; vendor 4.8 | If `${random.value}` were later used for an API secret, a remote attacker could predict it and gain confidentiality. | Upgraded to 3.5.16, newer than the fixed 3.5.14 release; secrets are external and never generated from Spring random properties. See [NVD](https://nvd.nist.gov/vuln/detail/CVE-2026-40975) and [Spring release](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/). |
| SEC-002 | `readAllBytes()` consumed unbounded OPA stdout/stderr. A policy could force a very large decision and exhaust the worker heap. | High / 6.5 (`AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:H`) | A tenant submits a policy returning a multi-gigabyte value; concurrent workers OOM and restart. | Both streams are read on virtual threads with a strict 16 MiB cap; overflow terminates the process tree and fails the shard. |
| SEC-003 | Dataset JSON had no byte, nesting, string, or case-count limits. | High / 6.5 | A compressed-looking but huge/deep JSON artifact exhausts heap or parser stack. | 64 MiB, 100,000-case, 100-level nesting, 1 MiB string, and number-length limits; larger work is pre-sharded outside the JVM. |
| SEC-004 | Timeout handling killed only the immediate OPA process and could leave descendants. | Medium / 6.5 | A replaced or wrapped OPA executable spawns children that retain CPU/memory after timeout. | Descendants and parent are terminated, workers run as non-root with PID/cgroup limits, no-new-privileges, seccomp, and dropped capabilities. |
| SEC-005 | Blocking stream readers used the global `CompletableFuture` common pool, enabling starvation and retained tasks. | Medium / 5.3 | Many timed-out evaluations occupy common-pool workers and stall unrelated asynchronous work. | A per-call virtual-thread executor has deterministic closure; streams and samplers are `AutoCloseable`. |
| SEC-006 | Policy and dataset paths accepted symlinks, allowing path escape and TOCTOU-assisted cross-tenant reads. | High / 8.1 (`AV:N/AC:H/PR:L/UI:N/S:U/C:H/I:H/A:H`) | A malicious artifact replaces a symlink between validation and OPA open, targeting another mounted tenant. | Platform artifacts use tenant-confined content keys, `toRealPath`, no-follow checks, recursive symlink rejection, constant-time SHA-256 comparison, read-only mounts, and one tenant/job per runtime. |
| SEC-007 | Sample capacity used `int` multiplication and could overflow before `ArrayList` allocation. | Medium / 5.3 | Large `cases × iterations` wraps negative, crashes every retry, and poisons the queue. | Multiplication uses `long`/`Math.multiplyExact`; worker shards are capped at five million samples. API bounds are validated. |
| SEC-008 | The initial CLI had no multi-tenant authentication or authorization because it was local-only. Reusing it as an API would be an IDOR risk. | Critical / 9.1 | Tenant A changes an organization UUID and reads or submits jobs for tenant B. | OAuth2 JWT scopes plus `org_id/org_ids` checks, tenant-qualified primary keys, parameterized queries, and PostgreSQL RLS using transaction-local tenant context. |
| SEC-009 | No distributed admission control existed. Benchmark creation is computationally expensive. | High / 7.5 | An authenticated tenant floods job submissions and exhausts Kafka/worker capacity. | Redis atomic fixed-window admission, gateway/WAF limits, per-tenant quotas, bounded topic partitions, worker sample limits, and HPA ceilings. Submission fails closed if admission state is unavailable. |
| SEC-010 | Raw exceptions could disclose paths, broker addresses, or policy parse fragments in an API response/log. | Medium / 5.3 | A user induces errors and collects infrastructure details or log-forges new lines. | Public errors contain an opaque incident ID; logs record only bounded type/context; outbox errors strip control characters and cap length. CLI retains local diagnostics because its caller owns the process. |
| SEC-011 | Dynamic HTML or Markdown reports can become stored XSS when policy decisions or advisor text are embedded. | High / 7.3 | A policy returns `<script>` and a report viewer executes it under the platform origin. | HTML escapes five metacharacters and is served under a `default-src 'none'` CSP; Markdown table/JSON values escape pipes, newlines, and backticks. Reports are never marked as trusted HTML in CI. |
| SEC-012 | Direct database write followed by Kafka send can lose or duplicate commands. | High / 7.1 availability/integrity | Coordinator crashes between commit and send, leaving a permanently queued job, or sends twice after timeout. | Transactional outbox, leased `SKIP LOCKED` relay, idempotent Kafka producer, idempotency keys, terminal-state checks, and report upsert. |
| SEC-013 | A globally keyed incremental cache could return one tenant's decisions and original job ID to another job. | Critical / 9.1 | Two tenants submit identical public policy hashes; tenant B receives tenant A's cached decision payload, while B's job remains stuck. | Fingerprints include organization ID and historical identity; cache hits are rebound to a fresh event ID and the requesting job/tenant before publication. |
| SEC-014 | Artifact integrity hashing ran before a byte/file-count bound, so an oversized tree could consume worker I/O for hours. macOS RSS sampling also resolved `ps` through `PATH`. | High / 6.5 | A tenant registers a huge tree to pin workers, or a hostile local PATH substitutes `ps` in CLI mode. | Policy trees are capped at 256 MiB/10,000 files, dataset shards at 64 MiB/100,000 cases, checked arithmetic is used, and RSS invokes an absolute system `ps` with a 1 KiB output cap. |

## Category-by-category review

| Category | Result and evidence |
|---|---|
| OWASP Broken Access Control | JWT scopes, explicit tenant claim check, tenant-qualified keys, RLS, deny-by-default routes and network policies. No numeric/guessable cross-tenant lookup is trusted without organization context. |
| Cryptographic failures | TLS/SASL is required in Kubernetes; SHA-256 uses constant-time comparison; no custom encryption. Secrets come from `opa-guard-secrets`/external secret managers. |
| Injection / SQL injection | All SQL values use `JdbcClient` bind parameters. Table/topic names are constants. No user value is concatenated into SQL. |
| Command injection | `ProcessBuilder` receives a fixed argument vector; query grammar is allowlisted. No shell is invoked by application code. The executable is administrator configuration, not an API field. |
| Path traversal | Object keys match a content-addressed allowlist, normalized paths must remain below the tenant root, real paths and hashes are verified, symlinks rejected. |
| XXE | The application exposes no XML parser or XML media type. OpenAPI accepts JSON only. |
| SSRF | The API accepts version UUIDs, never URLs. JWK and dependency endpoints are administrator configuration and HTTPS constrained. CI notification uses stored Git installation IDs, not arbitrary callbacks. |
| CSRF | Disabled intentionally for a stateless bearer-token API with no cookies or browser session. CORS is disabled. |
| XSS | HTML encoding, Markdown escaping, JSON media types, CSP, `X-Content-Type-Options`, frame denial and no inline untrusted markup. |
| Unsafe deserialization | Jackson polymorphic default typing is not enabled; JSON depth/string/number limits apply; event schema versions and typed records are required. Java native serialization is absent. |
| RCE | Rego runs only in an OPA binary inside a non-root, read-only, capability-free sandbox with time/resource limits. Artifact executables are not launched. OPA executable configuration is not tenant controlled. |
| Race conditions / thread safety | Immutable records, a single mutable aggregate confined to a request, optimistic job versions, transaction boundaries, Kafka key ordering, thread-safe counters. No static mutable tenant state. |
| TOCTOU | Content hash and symlink checks plus immutable read-only content mounts reduce exposure. See residual risk R-001. |
| Memory/resource leaks | Bounded input/output, try-with-resources for streams/walks/executors, process-tree termination, capped samples, Kafka backpressure and Kubernetes memory limits. |
| Denial of service | Gateway+Redis quotas, request limits, parser limits, process deadline, sample cap, Kafka partitions, retry/DLT budget, cgroups and HPA maximums. |
| Integer overflow | Sample cardinality uses checked long arithmetic; sizes are `long`; API numeric maxima are explicit. |
| Null risks | Required constructor fields use validation/`requireNonNull`; optional history is explicit and branches are guarded. Undefined OPA decisions become JSON null. |
| Temporary files | Application does not create predictable temporary policy files. Containers use size-limited `tmpfs` with `noexec,nosuid`; artifacts are immutable mounts. |
| Secrets in source | No passwords, tokens, or private keys are committed. Compose requires external values; Kubernetes references a non-committed secret. Sample hostnames are not credentials. |
| Dependency vulnerabilities | Spring Boot upgraded; CycloneDX SBOM is generated at package; Dependabot, GitHub dependency review, CodeQL, and Trivy workflows are configured. A successful workflow run is still required before claiming a clean scan. |
| Insecure logging | No policy input, decision, bearer token, JDBC URL credential, or event payload is logged by application code. Incident IDs correlate restricted logs. |
| Input validation | Bean Validation, UUID types, bounded percentages/iterations, idempotency length, query grammar, JSON constraints, digest/key grammar. |
| Output encoding | Dedicated encoders for HTML/Markdown; Jackson emits JSON. API uses Problem Details, never string-built JSON. |

## Residual risks and required controls

| ID | Residual risk | Rating | Required production control |
|---|---|---:|---|
| R-001 | POSIX path validation and open are not atomic; a privileged storage actor could swap an inode after hashing. | Medium | Mount versioned object-store snapshots read-only, prohibit mutable shared POSIX writers, and preferably pass an already-open directory/file descriptor to a sandbox launcher. |
| R-002 | The included filesystem artifact adapter is suitable for CSI snapshots, not direct Internet object retrieval. | Medium | Use a cloud object-store adapter with private endpoints, workload identity, version IDs, checksum validation, and no caller-supplied URL. |
| R-003 | CLI mode cannot obtain Go allocation and GC metrics from one-shot `opa eval`; it reports them unavailable/zero. | Low | Worker mode has a long-lived OPA pool and Go-metric parsing, but it is prototype status. Do not gate unavailable metrics from CLI mode; validate cgroup/Go telemetry in the target runtime before production use. |
| R-004 | Multi-region asynchronous failover can replay events and lose the last replication window. | Medium | Use event IDs/idempotent consumers, mirror topics, regularly test failover, and offer synchronous DR for RPO-zero tenants. |
| R-005 | A malicious but valid Rego policy can consume its entire CPU/memory budget. | Medium | One job per sandbox, cgroup quotas, OPA timeout, evaluation budget, queue fairness, and artifact quarantine after repeated crashes. |
| R-006 | Third-party CI actions and container base images are supply-chain dependencies. | High | Pin actions/images by immutable digest, verify signatures, generate SLSA provenance, scan on promotion, and enforce admission policy. The checked-in examples use readable version tags and must be digest-pinned by the release pipeline. |

## Security verification gates

Implemented release gates are unit/integration tests, CodeQL, GitHub dependency review, Trivy image scanning, SBOM generation, keyless signing, and attestations. Recommended additional production gates are secret scanning, Rego/Kubernetes policy checks, ZAP against the coordinator, tenant-isolation property tests, Kafka duplicate/reorder chaos, container-escape validation, and a quarterly independent threat-model review.
