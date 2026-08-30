# ADR-004: Projection boundary and event ordering

## Context

This downstream record preserves the projection and ordering decision in the [Approved/Frozen System Architecture](../superpowers/specs/2026-08-30-system-architecture-v0.1.md), especially Section 12.4, Section 13.4, Section 14, and Section 30.

## Status

Status: Accepted

## Authoritative source

The Approved/Frozen System Architecture remains authoritative. If downstream work finds a conflict, it requires an explicit Architecture Change Request before changing the recorded decision.

## Decision

Facts have a global event ordinal, per-Run sequence, and expected version. Projections consume committed facts and never becoming authority.

## Consequences

This record does not claim that the event store or projection consumers are implemented.

## Change control

Conflicts are not resolved in this ADR; they require the Architecture Change Request described above.
