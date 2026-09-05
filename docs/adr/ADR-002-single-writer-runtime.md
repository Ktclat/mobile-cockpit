# ADR-002: Single-writer runtime

## Context

This downstream record preserves the Run ownership decision in the [Approved/Frozen System Architecture](../superpowers/specs/2026-08-30-system-architecture-v0.1.md), especially Section 7, Section 9, Section 10, and Section 30.

## Status

Status: Accepted

## Authoritative source

The Approved/Frozen System Architecture remains authoritative. If downstream work finds a conflict, it requires an explicit Architecture Change Request before changing the recorded decision.

## Decision

One serialized RunCoordinator is the sole Run-state writer; long I/O remains outside the serialized coordination turn, and stale results require version/attempt validation before state changes.

## Consequences

This record does not claim that the coordinator or its I/O integrations are implemented.

## Change control

Conflicts are not resolved in this ADR; they require the Architecture Change Request described above.
