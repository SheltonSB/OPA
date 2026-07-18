# CI/CD and release pipeline

## Pull-request gates

```text
source -> compile/test -> OPA baseline gate -> CodeQL -> dependency scan -> image build
       -> container scan -> SBOM/provenance -> Kubernetes policy/render -> review
```

`platform-ci.yml` implements compilation, JUnit/Testcontainers, CycloneDX, OWASP Dependency Check, CodeQL, an OCI build, Trivy, and Kubernetes rendering. `opa-policy-performance-guard.yml` performs the actual main-versus-PR Rego gate and updates one pull-request comment. GitLab, Jenkins, and Azure examples provide equivalent local CLI behavior.

The OWASP job requires an `NVD_API_KEY` repository secret. Without it, NVD's unauthenticated 367,000-record synchronization is too slow and rate-limited to be a dependable release gate.

## Release and deployment

```text
main merge
   -> reproducible image build
   -> SBOM + SLSA provenance
   -> Code signing (keyless Cosign)
   -> immutable registry digest
   -> GitOps manifest digest update
   -> development cell
   -> integration/chaos/security tests
   -> 1% canary cell
   -> 10% cells
   -> regional rollout
   -> global rollout
```

Promotion moves the same digest; it never rebuilds. Admission policy rejects unsigned images, mutable tags, privileged pods, missing limits, and critical vulnerabilities without an approved, expiring VEX exception. Argo Rollouts or the managed deployment controller evaluates API error rate, Kafka lag age, worker error rate, and job completion latency for at least 30 minutes at each canary stage. Rollback changes the digest pointer; database changes follow expand/migrate/contract so the previous version remains compatible.

Production secrets come from a cloud secret manager through workload identity and External Secrets/CSI. CI receives short-lived federation credentials, never static cloud keys. GitHub environments require approval for production and prevent unreviewed workflows from accessing deployment identity.

## Failure and rollback rules

- Abort immediately on security admission failure, migration failure, elevated authorization errors, data-integrity alarms, or artifact hash mismatch.
- Pause on SLO burn, Kafka lag, PostgreSQL saturation, or statistically significant benchmark drift.
- Coordinators roll with `maxUnavailable=0`; workers drain partitions; analyzers are replay-safe.
- Flyway migration is a separate pre-deployment job using a dedicated schema-owner identity. Runtime identities cannot execute DDL.
- Disaster-recovery promotion is rehearsed quarterly, including Kafka replay and duplicate completion events.
