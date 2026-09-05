# ADR-003: Persistence event ledger

## Context

This downstream record preserves the persistence decision in the [Approved/Frozen System Architecture](../superpowers/specs/2026-08-30-system-architecture-v0.1.md), especially Section 12, Section 13, and Section 30.

## Status

Status: Accepted

## Authoritative source

The Approved/Frozen System Architecture remains authoritative. If downstream work finds a conflict, it requires an explicit Architecture Change Request before changing the recorded decision.

## Decision

Persist authoritative relational facts with an append-only Runtime Event Ledger, separate encrypted content/evidence, and rebuildable projections.

## Consequences

This record does not claim that a database, encryption implementation, or projection engine already exists.

## Change control

Conflicts are not resolved in this ADR; they require the Architecture Change Request described above.
