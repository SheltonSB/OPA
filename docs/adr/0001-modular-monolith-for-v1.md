# ADR 0001: Keep one Maven artifact for v1

- Status: accepted
- Date: 2026-07-18

## Context

The repository contains a local CLI and distributed coordinator, worker, and analyzer roles. Splitting them into Maven modules could improve dependency isolation and independent release cadence, but doing so immediately before v1.0.0 would change packaging, Docker builds, CI caching, and deployment entry points without adding runtime correctness.

## Decision

Version 1.0.0 remains one Maven module and one signed OCI image. Java package boundaries enforce the intended hexagonal layers, and `OPA_GUARD_MODE` selects a role. The benchmark domain and OPA adapter are shared without cross-module publication.

This is a modular monolith packaging decision, not a claim that all roles should scale together. Kubernetes deployments select different modes and resource profiles from the same immutable digest.

## Consequences

The release is simpler to reproduce and patch, but role images carry dependencies they do not use. Maven cannot enforce every architectural boundary, so tests and review must prevent adapter-to-domain inversions.

Create separate `guard-core`, `guard-cli`, `platform-coordinator`, `platform-worker`, and `platform-analyzer` modules when at least one of these triggers occurs:

- a role needs an independent release cadence;
- image size or vulnerability surface materially affects operations;
- teams own roles independently;
- the CLI requires a compatibility policy different from the service;
- build-time architecture tests can replace package-boundary review.

The likely first extraction is `guard-core` plus `guard-cli`; distributed role modules can then depend on the same versioned core contract.
