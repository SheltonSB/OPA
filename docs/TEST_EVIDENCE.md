# Test evidence

The final v1.0.0 local verification on 2026-07-18 discovered 30 JUnit tests: all 30 passed, none failed, and none were skipped. The six container-backed tests ran against Docker Desktop 28.1.1 using pinned OPA 1.18.2, Kafka 3.8.0, and PostgreSQL 17.5 images. JaCoCo reported 41.0% line coverage. The badges deliberately show these measured numbers rather than an aspirational target.

GitHub CI runs `mvn verify` on a Docker-capable Linux runner, publishes the full JaCoCo HTML report, and prints the test totals in the workflow summary. The local Docker-backed run now proves the following paths; the hosted run remains an independent portability gate:

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

Coverage is currently below a mature production target. Increasing service-level branch coverage and running destructive fault tests against a deployed environment remain release-hardening work; the passing suite must not be read as certification of production readiness.
