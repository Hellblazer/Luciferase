---
title: "Prism Full-Cube Coverage via Two-Prism Cover"
id: RDR-009
type: Architecture
status: draft
priority: medium
author: hal.hildebrand
reviewed-by: self
created: 2026-05-26
related_issues: [Luciferase-fzm, Luciferase-4g6, RDR-001, RDR-002, RDR-003]
---

# RDR-009: Prism Full-Cube Coverage via Two-Prism Cover

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

The `Prism` spatial index uses anisotropic subdivision: a triangular element in (x,y) crossed with a linear element in (z). The triangular component (`Triangle`) is a t8code-style triangular space-filling curve whose key space enforces a **global lower-triangle constraint** (`x + y < scale`). As a result, a single `Prism` index spans only the **lower-triangular half** of `[0,worldSize)³` — it cannot represent points in the upper triangle (`x + y >= scale`).

This surfaced as a review-360 finding (T2 `luciferase/360-review-2026-05-23-summary`): users reasonably expect to insert anywhere in the cube, but a single Prism rejects/mishandles half of it. Two distinct behaviors exist in the current code, both unsatisfactory for full-cube use:

- `Triangle.fromWorldCoordinates` (`lucien/.../prism/Triangle.java`) correctly classifies a point into the per-cell type-0 (lower-left) or type-1 (upper-right) sub-triangle, **but the global SFC key space still tiles a triangle, not a square** — there is no key for the upper half of the domain as a whole.
- The integer-coordinate `Triangle` path **silently clamps** `x + y >= scale` points onto the diagonal (`x = x * total / (x + y)`), i.e. it relocates out-of-domain points rather than rejecting them — a latent data-relocation bug.

Single-prism behavior is *geometrically correct* (a triangular prism genuinely tiles a half-cube), but it limits usability and hides a silent-relocation hazard. `Octree`/`Tetree` already provide full-cube coverage; the question is whether `Prism` — the specialized anisotropic index — should too.

## Context

### Background

- **Decision to pursue this (not document-and-route):** brainstorming-gate, 2026-05-26. Two options were weighed:
  - **Option A — two-prism cover** (this RDR): pair a lower-triangle and an upper-triangle prism family in a shared key space so the two together tile the full cube. Matches t8code convention; the right long-term answer.
  - **Option B — document + fail-fast** (declined): document the half-cube domain and convert the silent clamp into an explicit `IllegalArgumentException` routing full-cube users to Octree/Tetree. Cheaper, but leaves Prism half-cube-only.
  - Option A was chosen; Option B remains the fallback if research shows the two-prism cover is disproportionately costly.
- **Prior deferral:** `Luciferase-4g6` (Tranche B) explicitly deferred this; tracking bead `Luciferase-fzm` (P2).

### Technical Environment

- **Module:** `lucien/src/main/java/com/hellblazer/luciferase/lucien/prism/` — `Prism`, `PrismKey`, `Triangle`, `Line`, `PrismSubdivisionStrategy`, `PrismNeighborFinder`, `PrismGeometry`, `PrismRayIntersector`, `PrismCollisionDetector`.
- **Key type:** `PrismKey implements SpatialKey<PrismKey>` composes a `Triangle` (x,y) and a `Line` (z) at a synchronized level. Full-cube coverage requires `PrismKey` (or `Triangle`) to encode *which half* (lower/upper triangle) a key belongs to.
- **Known adjacent bug (must be reconciled, not silently inherited):** review-360 found `Triangle.consecutiveIndex` overflows `long` at level 11+ (the `PrismKey` SFC comment at `PrismKey.java:~119` acknowledges this). A prism-half encoding consumes additional key bits — the RDR must determine whether it worsens the overflow, must coexist with a fix, or is independent.
- **Geometry baseline:** the AABT/12-DOP exact-containment work (RDR-001/RDR-002) and the S0-S5 Kuhn subdivision (Tetree) are the in-repo precedents for "N simplices tile a cube"; the refuted rhombohedral-AABR approach is a cautionary precedent (a coordinate scheme that looked like it tiled but did not tighten bounds).

## Approach

> Candidate research/design directions below; to be resolved by research (see [Research Findings](#research-findings)) into a locked design at gate. These numbered items are the scope contract for phase-review at implementation time.

1. **Prism-half encoding.** Extend `PrismKey`/`Triangle` to encode the lower vs upper triangle half of the cube cross-section. Determine the minimal encoding (a single discriminator bit vs a richer type field), where it lives (in `Triangle` alongside `type`, or in `PrismKey`), and its cost in the SFC key width.
2. **SFC continuity / ordering across the two halves.** Decide how the two triangle families order within the space-filling curve — interleaved per cell, or lower-half-then-upper-half — and what that does to spatial locality and range-query contiguity.
3. **Neighbor traversal across the shared diagonal.** Define neighbor-finding (`PrismNeighborFinder`) across the lower/upper boundary — the diagonal is now an interior face between the two prism families, not a domain edge.
4. **Subdivision strategy.** Update `PrismSubdivisionStrategy` so refinement produces children covering both halves correctly, preserving the tiling at every level.
5. **Query operations across both halves.** Ray intersection, collision detection, range queries, and kNN (`PrismRayIntersector`, `PrismCollisionDetector`, `PrismGeometry`, and the `AbstractSpatialIndex` query paths) must traverse both prism families without gaps or double-counting along the diagonal.
6. **Reconcile with the `Triangle.consecutiveIndex` level-11 long-overflow.** Establish whether the half-encoding worsens the overflow, must land with an overflow fix, or is orthogonal — and at what max level the two-prism index is correct.
7. **Backward compatibility / migration.** Define behavior for existing single-prism (lower-half-only) users and persisted indices: is the upper half opt-in, is the key format versioned, and does an existing lower-half index remain readable?

## Research Findings

> Pending. Populate via `/conexus:rdr-research` before the gate. Open the t8code triangular/prism SFC references and the in-repo `Triangle`/`PrismKey` SFC implementation; quantify the key-width and max-level impact of the half-encoding; confirm whether the two-prism cover composes cleanly with the existing per-cell type-0/1 classification or replaces it.

## Open Questions

- Does the existing per-cell `type` (0/1) in `Triangle` already provide the upper/lower discriminator at the leaf, such that the gap is purely in the *global* key space — or is a new field genuinely required?
- Interleaved vs sequential SFC ordering of the two halves — which preserves range-query locality, and does either break existing `consecutiveIndex`/`tmIndex` contracts?
- Does this intersect the open FCC/rhombic-dodecahedral lattice direction (RDR-003 and the T3 `architecture-luciferase-fcc-*` threads)? Is two-prism cover a stepping stone or orthogonal to an FCC overlay?
- Is the level-11 `long` overflow a hard ceiling that makes the full-cube Prism only correct to ~level 10, and is that acceptable?
- Confirm the fallback trigger: at what cost/complexity threshold do we abandon Option A and ship Option B (document + fail-fast) instead?

## Decision

Pending gate. (Run `/conexus:rdr-research` to populate findings, then `/conexus:rdr-gate` and `/conexus:rdr-accept` before any implementation.)

## Consequences

- **Positive:** Prism becomes a full-cube index, removing the silent upper-triangle relocation hazard and the usability cliff; aligns with t8code convention; preserves the anisotropic (triangular×linear) advantage that distinguishes Prism from Octree/Tetree.
- **Cost / risk:** This is a key-format and traversal change, not a localized fix — it touches `PrismKey`, `Triangle`, subdivision, neighbor-finding, and every query path, and must be reconciled with the level-11 SFC overflow. Under-scoping the SFC-ordering or neighbor-across-diagonal work risks a subtly wrong (gappy or double-counting) index. Option B remains the documented fallback.
- **Sequencing:** independent of the RDR-005/RDR-007 distributed arc; geometry-local. No external module impact expected beyond `lucien`.
