# ADR-001: Module boundaries

## Context

This downstream record preserves the module-boundary decision in the [Approved/Frozen System Architecture](../superpowers/specs/2026-08-30-system-architecture-v0.1.md), especially Section 5 and Section 30.

## Status

Status: Accepted

## Authoritative source

The Approved/Frozen System Architecture remains authoritative. If downstream work finds a conflict, it requires an explicit Architecture Change Request before changing the recorded decision.

## Decision

Use a modular monolith with Ports/Adapters and enforced module dependency boundaries.

## Consequences

This record does not claim that future runtime or adapter behavior is implemented.

## Change control

Conflicts are not resolved in this ADR; they require the Architecture Change Request described above.
