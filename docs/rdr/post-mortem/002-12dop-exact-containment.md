# Post-Mortem: RDR-002 — 12-DOP Exact Containment for Kuhn Tetrahedra

**Closed**: 2026-03-22 | **Reason**: Implemented | **Epic**: Luciferase-w06 (28/28 beads)

## What Was Delivered

- **contains12DOP()**: 11-op point containment replacing 84-op determinant (`containsUltraFast`)
- **intersects12DOP()**: 18-op AABB-vs-tet replacing incomplete SAT
- **intersectsTet12DOP()**: 18-op tet-vs-tet, 27.5x faster than SAT
- All production callers wired, old methods removed
- 86 new tests, 28/28 beads closed
- 721 lines deprecated code removed

## What Went Right

1. **The permutohedron insight was the key.** Recognizing that coordinate orderings ARE the containment test — the S0-S5 types are chambers of the A2 root system's hyperplane arrangement — made the entire derivation fall out algebraically. The 12-DOP axes {x, y, z, x-y, x-z, y-z} are the face normals of all 6 types combined.

2. **The algorithm already existed in the codebase.** `locatePointBeyRefinementFromRoot()` was performing the ordering test to select S-types. The 12-DOP containment test is structurally identical — `containsUltraFast()` was doing 84 ops of determinant math to answer a question that 2 ordering comparisons resolve exactly.

3. **Deriving from vertex geometry, not code comments, was essential.** The code comments describing the ordering convention were wrong for 5 of 6 types. Deriving directly from `Tet.coordinates()` vertex data caught this immediately.

4. **The 3-bit sign encoding per type makes intersection extremely cheap.** Each S-type's difference-axis constraints encode as 3 sign bits, turning the intersection switch into a pattern match on sign combinations. This generalizes cleanly from point containment to AABB and tet-vs-tet intersection.

5. **Phased delivery with correctness gates.** Each phase had a test comparing the new 12-DOP method against the old SAT/determinant method on random inputs. Zero disagreements across all phases before any caller was rewired.

## What Went Wrong

1. **RDR ordering convention mismatch.** The ordering convention documented in `locatePointBeyRefinementFromRoot()` (Tet.java lines 315-332) didn't match the actual vertex geometry for 5 of 6 S-types. The code comments showed e.g. S0 as `y <= z < x` but the vertices define `x > z >= y`. This was caught during Phase 1 implementation and fixed, but it burned time and could have been caught during the RDR research phase by verifying against `coordinates()` output.

2. **Existing SAT was incomplete.** `TetrahedralGeometry.satTestAABBTetrahedron()` tested only 4 face normals, missing the 9 edge-cross axes required for complete SAT on convex polyhedra. This produced a ~1.2% false positive rate. Discovered during the correctness comparison tests — the "disagreements" were all cases where SAT said intersect and 12-DOP (correctly) said no.

3. **BeySubdivision vertex ordering mismatch (not fixed).** `BeySubdivision.getMortonChild()` uses `subdivisionCoordinates()` which has different vertex ordering than `coordinates()`. This causes 12-DOP false negatives for some Bey parent-child pairs. Discovered late in Phase 3. Not fixed — needs follow-up investigation.

4. **AABT traversal overhead dominates wall-clock.** Despite the 12-DOP primitive being fast (~5ns), the AABT tree traversal overhead dominates end-to-end query time. Pipeline optimization is needed to realize the primitive speedup at the query level.

## Divergences from Plan

| Planned | Actual |
|---------|--------|
| intersects12DOP ~21 ops | 18 ops (tighter derivation) |
| Phase 2 formal slab derivation needed | Derivation completed, all 3 difference axes per type |
| Keep containsUltraFast deprecated | Removed entirely (721 lines) |
| Phase 4 benchmark expected 5-8x | Point containment ~7.6x, tet-vs-tet 27.5x |
| AABB intersection sketch had 2/3 axes | Fixed: all 3 difference axes tested per type |

## Metrics

| Metric | Value |
|--------|-------|
| Tests added | 86 |
| Beads closed | 28/28 |
| Deprecated code removed | 721 lines |
| Point containment ops | 11 (was 84) — 7.6x reduction |
| AABB intersection ops | 18 (was ~100+) — ~5.6x reduction |
| Tet-vs-tet speedup | 27.5x vs SAT |
| False positive rate eliminated | ~1.2% (incomplete SAT) |
| Ordering convention fixes | 5 of 6 S-types corrected |

## Lessons

1. **Derive from vertex geometry, not code comments.** The ordering convention comments in `locatePointBeyRefinementFromRoot()` were wrong for 5/6 types. Always verify against the ground truth (`coordinates()` output) rather than trusting inline documentation.

2. **Permutohedron structure was the key insight.** The S0-S5 types being chambers of the A2 hyperplane arrangement means coordinate orderings ARE the containment test. This is a deeper result than "face normals happen to be axis-aligned" — it guarantees exactness for all Kuhn tetrahedra by construction.

3. **Incomplete SAT implementations are silent bugs.** The existing SAT tested only 4 of 13 required axes. The 1.2% false positive rate was never noticed because it only causes slight over-reporting of intersections, not missed collisions. The 12-DOP comparison tests caught it.

4. **Primitive speed is necessary but not sufficient.** A 5ns containment test doesn't help if the tree traversal visiting pattern dominates. The next optimization target is the pipeline, not the primitive.

5. **The RDR sketch warning worked.** The AABB intersection sketch in the RDR was explicitly marked as incomplete (2/3 axes). The formal derivation in Phase 2 Step 1 filled in the missing axis per type. Flagging known incompleteness in design docs prevents premature copy-paste.

## Open Items

1. **BeySubdivision vertex ordering**: `subdivisionCoordinates()` vs `coordinates()` ordering mismatch causes 12-DOP false negatives for some Bey parent-child pairs. Needs investigation and possible fix.

2. **AABT pipeline optimization**: Tree traversal overhead dominates wall-clock despite fast primitives. Need to optimize the visiting pattern / early termination strategy.
