# Test evidence

The v1.0.0 verification was run with Docker Desktop 4.41.2 (Engine 28.1.1) and discovered 33 JUnit tests: all 33 passed, with no failures, errors, or skips. The Docker-backed tests used pinned OPA 1.18.2, Kafka 3.8.0, and PostgreSQL 17.5 images. JaCoCo reported 42.6% line coverage. The badges now reflect this complete Docker-backed run rather than a partial local snapshot.

GitHub CI runs `mvn verify` on a Docker-capable Linux runner, publishes the full JaCoCo HTML report, and prints the test totals in the workflow summary. The suite covers the following paths; each Docker-backed path was exercised in the verification run:

- real OPA evaluation through Testcontainers;
- PostgreSQL row-level security and schema constraints;
- 24 concurrent submissions sharing one idempotency key;
- two concurrent outbox relays using `FOR UPDATE SKIP LOCKED`;
- worker crash simulation through an expired execution lease and redelivery;
- actual Kafka redelivery after a consumer exits before committing;
- duplicate worker event handling without a second OPA run;
- Redis admission failure (fail closed) and cache failure (fail open);
- database write failure without an orphaned outbox event (unit failure-path test);
- real PostgreSQL outage surfaced to the caller.

Recreate the badges after a verified run with:

```bash
python3 scripts/summarize-verification.py \
  target/surefire-reports target/site/jacoco/jacoco.xml \
  --write-badges docs/badges
```

On a Docker-capable CI runner, `platform-ci.yml` runs
`scripts/require-container-tests.py` after Maven and fails if any integration
report is skipped, failed, or missing. This prevents a green CI check from
silently omitting the Kafka/PostgreSQL/OPA evidence. Local CLI development can
still run without Docker; the distributed app and complete integration
evidence require the daemon.

Coverage is currently below a mature production target. Increasing service-level branch coverage and running destructive fault tests against a deployed environment remain release-hardening work; the passing suite must not be read as certification of production readiness.
