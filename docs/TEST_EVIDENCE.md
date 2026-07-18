# Test evidence

The checked-in v1.0.0 evidence was collected from a local Maven run that discovered 33 JUnit tests: 26 passed, none failed, and seven Docker-backed tests were skipped because Docker was unavailable in the capture environment. JaCoCo reported 42.6% line coverage. The badges deliberately show this measured snapshot rather than an aspirational target; they are not a substitute for the hosted Docker run.

GitHub CI runs `mvn verify` on a Docker-capable Linux runner, publishes the full JaCoCo HTML report, and prints the test totals in the workflow summary. The suite covers the following paths; entries marked “requires Docker” are evidence targets for the hosted run and were skipped in the local capture:

- real OPA evaluation through Testcontainers (requires Docker);
- PostgreSQL row-level security and schema constraints (requires Docker);
- 24 concurrent submissions sharing one idempotency key;
- two concurrent outbox relays using `FOR UPDATE SKIP LOCKED`;
- worker crash simulation through an expired execution lease and redelivery;
- actual Kafka redelivery after a consumer exits before committing (requires Docker);
- duplicate worker event handling without a second OPA run;
- Redis admission failure (fail closed) and cache failure (fail open);
- database write failure without an orphaned outbox event (unit failure-path test);
- real PostgreSQL outage surfaced to the caller (requires Docker).

Recreate the badges after a verified run with:

```bash
python3 scripts/summarize-verification.py \
  target/surefire-reports target/site/jacoco/jacoco.xml \
  --write-badges docs/badges
```

On a Docker-capable CI runner, `platform-ci.yml` runs
`scripts/require-container-tests.py` after Maven and fails if any integration
report is skipped, failed, or missing. This prevents a green CI check from
silently omitting the Kafka/PostgreSQL/OPA evidence. Local development still
allows skips so the CLI can be built without Docker.

Coverage is currently below a mature production target. Increasing service-level branch coverage and running destructive fault tests against a deployed environment remain release-hardening work; the passing suite must not be read as certification of production readiness.
