# CI/CD and release pipeline

## Pull-request gates

```text
source -> compile/test -> OPA baseline gate -> CodeQL -> dependency scan -> image build
       -> container scan -> SBOM/provenance -> Kubernetes policy/render -> review
```

`platform-ci.yml` implements compilation, JUnit/Testcontainers, JaCoCo, CycloneDX, GitHub dependency review, an OCI build, Trivy, and Kubernetes rendering. The dedicated `codeql.yml` performs Java CodeQL analysis. `opa-policy-performance-guard.yml` performs the actual main-versus-PR Rego gate and updates one pull-request comment. GitLab, Jenkins, and Azure examples provide equivalent local CLI behavior.

Dependabot tracks Maven, GitHub Actions, and Docker updates. Release builds generate a CycloneDX SBOM, scan the published image with Trivy, sign the immutable digest through keyless Cosign/OIDC, and create GitHub artifact attestations for the image and downloadable files. The Trivy action is pinned to the signed v0.36.0 commit rather than a mutable tag because its upstream project disclosed a 2026 tag-compromise incident.

## Implemented release pipeline

A pushed `v*` tag must exactly match the non-SNAPSHOT Maven version. The workflow rebuilds and tests the artifact, generates Javadoc and a CycloneDX SBOM, publishes the GHCR image, signs its digest, scans it, attests release files/image, and creates a GitHub Release with checksums and the example report.

The Maven output timestamp is fixed and the CycloneDX serial number is omitted because a random BOM UUID made the executable JAR nondeterministic. Two clean local package builds from the corrected release source both produced SHA-256 `9157d840059a09b0038c87eaeb80377f983f5e0ed19d2ed28f540b49b43caff3`. A different source revision is expected to produce a different digest.

## Future deployment promotion

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
