# Test evidence

The v1.0.0 local verification on 2026-07-18 discovered 30 JUnit tests: 24 passed, 0 failed, and 6 Docker-backed tests were skipped because Docker Desktop was not running. JaCoCo reported 34.4% line coverage for that local run. The badges deliberately show these measured numbers rather than an aspirational target.

GitHub CI runs `mvn verify` on a Docker-capable Linux runner, publishes the full JaCoCo HTML report, and prints the test totals in the workflow summary. Container integration coverage includes:

- real OPA evaluation through Testcontainers;
- PostgreSQL row-level security and schema constraints;
- 24 concurrent submissions sharing one idempotency key;
- two concurrent outbox relays using `FOR UPDATE SKIP LOCKED`;
- worker crash simulation through an expired execution lease and redelivery;
- actual Kafka redelivery after a consumer exits before committing;
- Redis admission failure (fail closed) and cache failure (fail open);
- database write failure without an orphaned outbox event.

Recreate the badges after a verified run with:

```bash
python3 scripts/summarize-verification.py \
  target/surefire-reports target/site/jacoco/jacoco.xml \
  --write-badges docs/badges
```

Coverage is currently below a mature production target. Increasing service-level branch coverage and running destructive fault tests against a deployed environment remain release-hardening work; the current badge must not be read as certification of production readiness.
