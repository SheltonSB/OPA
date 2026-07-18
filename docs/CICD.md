# CI/CD and release pipeline

## Pull-request gates

```text
source -> compile/test -> OPA baseline gate -> CodeQL -> dependency scan -> image build
       -> container scan -> SBOM/provenance -> Kubernetes policy/render -> review
```

`platform-ci.yml` implements compilation, JUnit/Testcontainers, JaCoCo, CycloneDX, GitHub dependency review, an OCI build, Trivy, and Kubernetes rendering. Its explicit container-evidence step fails the job if Kafka/PostgreSQL/OPA integration reports are skipped, so Docker misconfiguration cannot look green. The dedicated `codeql.yml` performs Java CodeQL analysis. `opa-policy-performance-guard.yml` performs the actual main-versus-PR Rego gate and updates one pull-request comment. GitLab, Jenkins, and Azure examples provide equivalent local CLI behavior; their container-runtime requirements must be enabled by the host project.

The Maven OWASP Dependency-Check integration is available as an explicit
security profile. It is intentionally not part of every pull-request build
because the NVD feed is large and rate-limited; scheduled or release-hardening
jobs should run it with a cached data directory and (where available) an
`NVD_API_KEY`:

```bash
mvn --batch-mode --no-transfer-progress -Psecurity verify
```

The profile fails on CVSS 7.0 or higher and writes HTML/JSON evidence under
`target/`. This complements, rather than replaces, Dependabot, GitHub
dependency review, CodeQL, the CycloneDX SBOM, and Trivy image scanning.

Dependabot tracks Maven, GitHub Actions, and Docker updates. Release builds generate a CycloneDX SBOM, scan the published image with Trivy, sign the immutable digest through keyless Cosign/OIDC, and create GitHub artifact attestations for the image and downloadable files. The Trivy action is pinned to the signed v0.36.0 commit rather than a mutable tag because its upstream project disclosed a 2026 tag-compromise incident.

The container pipeline deliberately separates reporting from enforcement. The first scan uploads every HIGH/CRITICAL result to GitHub code scanning, including findings without an upstream fix. A second scan fails promotion for every remediable HIGH/CRITICAL result. Exceptions must be component-scoped, justified, and dated in `.trivyignore.yaml`; an expired exception fails the gate. Unfixed findings remain visible and must be reconsidered on every base-image or OPA refresh.

## Implemented release pipeline

A pushed `v*` tag must exactly match the non-SNAPSHOT Maven version. The workflow rebuilds and tests the artifact, generates Javadoc and a CycloneDX SBOM, publishes the GHCR image, signs its digest, scans it, attests release files/image, and creates a GitHub Release with checksums and the example report. The [v1.0.0 release](https://github.com/SheltonSB/OPA/releases/tag/v1.0.0) is the first verified public release; its assets are the concrete supply-chain and benchmark evidence for this repository.

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
