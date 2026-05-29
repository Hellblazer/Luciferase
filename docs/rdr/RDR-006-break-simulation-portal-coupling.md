---
title: "Break the simulation→portal Coupling (BubbleBounds JavaFX Pull-In)"
id: RDR-006
type: Architecture
status: accepted
priority: medium
author: hal.hildebrand
reviewed-by: self
created: 2026-05-24
accepted_date: 2026-05-25
related_issues: [Luciferase-jvs, Luciferase-7n1, RDR-003]
---

# RDR-006: Break the simulation→portal Coupling (BubbleBounds JavaFX Pull-In)

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

`simulation` — the distributed, headless-by-design simulation module — has a hard compile dependency on `portal`, the JavaFX 3D visualization module. The coupling is concrete: `BubbleBounds` (`simulation/src/main/java/.../bubble/BubbleBounds.java:7`) imports `com.hellblazer.luciferase.portal.Tetrahedral` and constructs it (`:49,65,130,201`) to do RDGCS coordinate transforms (`Tetrahedral.toRDG()` / `toCartesian()`).

`portal` depends on JavaFX 25 (and LWJGL). So every distributed-simulation deployment — including headless cluster nodes that never render anything — drags the entire JavaFX toolkit onto the classpath transitively through `BubbleBounds`, a core domain type used throughout the simulation. This is an architecture-layer inversion: a UI/visualization module is a dependency of the distributed compute core.

The geometry `BubbleBounds` actually needs (RD grid coordinate math) is pure math with no UI. It does not belong in the JavaFX module.

## Context

### Background

Flagged in the 360-review architecture pass (T2 `luciferase/360-review-2026-05-23-summary`; architecture finding `a1542d17`): "simulation→portal coupling (BubbleBounds imports portal.Tetrahedral which is a JavaFX UI subclass — pulls JavaFX onto distributed simulation classpath)." RDR-003 documented that the RD coordinate math (`Tetrahedral`, `RDG`, `RDGCS`) currently lives in `portal` and is mathematically correct but visualization-coupled.

This RDR overlaps the Clock-package-rename concern (`Luciferase-7n1`): both are about putting domain primitives in the right module rather than where they accreted.

### Technical Environment

- **Modules**: `simulation` (consumer), `portal` (current home of the RD math + JavaFX), `common`/`lucien` (candidate new homes for the extracted geometry), possibly a new `geometry`-style module
- **Key files**:
  - `simulation/src/main/java/.../bubble/BubbleBounds.java:7,36,49,65,130,199-201` — the coupling: `import portal.Tetrahedral`, `transient Tetrahedral coordSystem`, repeated `new Tetrahedral()`
  - `portal/src/main/java/.../Tetrahedral.java` — RDGCS transforms; per RDR-003 the math is verified-correct, UI-independent in substance but packaged in the JavaFX module
  - `portal/src/main/java/.../RDG.java`, `RDGCS.java` — companion RD coordinate-system classes
  - `pom.xml` module graph — `simulation` → `portal` → JavaFX 25
- **Prior art / constraints**: RDR-003 (FCC/RD spatial indexing) depends on this RD math and would benefit from it living in a non-UI module. `portal-rdfcc-quality` beads catalog dormant bugs + zero coverage in the RD classes — extraction is a chance to add tests.

## Approach

> Candidate directions below; resolved by research (see [Research Findings](#research-findings)) into the recommendation that follows.

1. **Isolate the UI-free geometry** — determine the exact subset of `Tetrahedral`/`RDG`/`RDGCS` that is pure RD coordinate math (no `javafx.*` references) vs. what is genuinely visualization (mesh/scene-graph helpers).
2. **Pick the new home** — `common` (already a leaf-ish dependency), `lucien` (spatial-index module, natural for spatial math), or a new small `geometry`/`rd-geom` module. Must NOT depend on JavaFX.
3. **Extract + re-point** — move the UI-free RD math to the chosen module; `BubbleBounds` and any other non-UI consumers depend on that; `portal` keeps (or re-depends on) the math for its rendering and adds only the JavaFX-specific layer.
4. **Verify JavaFX drops off the headless classpath** — `mvn dependency:tree -pl simulation` must show no `javafx-*` after the change.
5. **Add coverage** for the extracted RD math (currently zero per `portal-rdfcc-quality`).
6. Consider folding the Clock package rename (`Luciferase-7n1`) into the same "domain primitives in the right module" pass.

### Recommended direction (pending gate)

Extract the **UI-free RD math kernel** — `toRDG` plus the `vecmath`-only methods (`euclideanNorm`, `l1`, `cross`, `dot`, `rotateVectorCC`, the connected-neighbor helpers) — into a non-JavaFX home, leaving the scene-graph/mesh layer (`construct`, `positionTransform`, the `Point3D`-typed `toCartesian` overloads) in `portal`.

- **Extraction shape — standalone class, NOT a subclass (avoids the inheritance trap).** The kernel must be a new **standalone** class with **no inheritance from `Grid`, `RDGCS`, or any `javafx.*` type**; the vecmath methods are moved/copied, not inherited. The JavaFX pull-in is *structural via the `Tetrahedral → RDGCS → Grid` chain* (`Grid` is the JavaFX carrier), so an implementer who reaches the kernel by subclassing `RDGCS` would silently drag the entire JavaFX chain back in and the regression would survive undetected until `dependency:tree`.
- **`common` is disqualified.** Research found `common` *already declares* `javafx-graphics`, so landing the math there does not remove JavaFX from the headless classpath — it would defeat the purpose. (This is a separate smell: `common` is not the clean leaf the module name implies — a follow-up worth filing.)
- **Home — `lucien` vs a new `geometry`/`rd-geom` module (the real tradeoff).** `lucien` is the minimal-change option (no new module; `simulation` already depends on it; `portal` already depends on it; RDR-003's FCC work wants this math reachable from `lucien` anyway). The counter-pull: **RDR-008 is actively trying to *shrink* `lucien`**, so growing it with RD math is in tension. A small `vecmath`-only `geometry` module that both `lucien` and `portal` depend on is the purer single-responsibility answer at the cost of one new module. **Decision deferred to the gate**; both are viable and neither creates a dependency cycle (`portal → lucien → common` already holds).
- **API ripple.** The two `toCartesian` overloads return `javafx.geometry.Point3D`. The kernel's version must return `Point3f`/`Tuple3f`; this propagates to `BubbleBounds.toCartesian` and `BubbleBounds.centroid` (both currently `Point3D`). `portal` keeps a JavaFX-typed rendering layer (subclass or thin wrapper) over the extracted math.
- **`Tetrahedral` consumers are minimal** — `BubbleBounds` is the *only* non-portal compile consumer of `Tetrahedral`; `RDGridViewer` (portal) is the only other.
- **⚠️ But fixing `BubbleBounds` alone does NOT remove JavaFX from `simulation` — the `von` package carries a second coupling.** `simulation/.../von/TransportNeighborInfo.java:22` imports `javafx.geometry.Point3D`, and `Message.java` embeds `Point3D` in the records `JoinRequest`, `Move`, `NeighborInfo`, and `Bubble.NeighborState`. The blast radius is therefore **not** "two files": the `von`-layer `Point3D` usages must be **in scope for this pass** (swap to `Point3f`/`Tuple3f`), *or* explicitly carved out as a documented follow-up with the honest acknowledgement that the `dependency:tree` criterion below will not yet pass. Silence is not an option — the original "two files" framing was wrong.
- **Wire-format note (cross-ref RDR-004).** `BubbleBounds` itself is *not* on the wire today (its `coordSystem` is `transient`; `TransportVonMessage` decomposes to primitives; `TransportNeighborInfo` Phase 6A nulls `BubbleBounds`). So the `toCartesian`/`centroid` return-type change (`Point3D`→`Point3f`) is an internal compile-time ripple, not a wire-compat break. **However**, `TransportNeighborInfo`'s Phase 6B ("add `BubbleBounds` serialization when needed", `:34`) *will* introduce a `BubbleBounds` wire dependency — when that lands it must cross-reference RDR-004's deserialization hardening.
- **Coverage.** Three RD-math tests already exist in `portal` (`RDFCCMathCoverageTest`, `PortalCleanupBatchTest`, `RDGeometrySmokeTest`) — the RDR's "zero coverage" was inaccurate for `portal`. Port these to the new home with the extraction.
- **Fold in the Clock rename** (`Luciferase-7n1`) as a second "domain primitive in the right module" move in the same pass.
- **Verify** `mvn dependency:tree -pl simulation` shows no `javafx-*` afterward — which **requires the `von`-package `Point3D` usages above to be addressed**, not just `BubbleBounds`.

## Research Findings

> Investigation 2026-05-25 (`codebase-deep-analyzer`). Full detail in T2 `luciferase_rdr/006-research-1`.

1. **The JavaFX pull-in is structural via inheritance.** `Tetrahedral` (`:38`) `extends RDGCS extends Grid`, and `Grid.java:1-18` imports `javafx.scene.*` / `javafx.geometry.Point3D` heavily. `Tetrahedral` itself imports `javafx.geometry.Point3D` (`:17`) but only in its two `toCartesian` overloads (`:157`, `:163`); the rest (`toRDG:181` + the vecmath methods) is JavaFX-free.
2. **`BubbleBounds` is the sole non-portal consumer.** Imports `portal.Tetrahedral` (`:7`) and `javafx.geometry.Point3D` (`:8`); constructs `Tetrahedral` at `:49,65,130,201`, calls `toRDG` (`:68,134,213`) and `toCartesian` (`:223`). The only other reference anywhere is `RDGridViewer` (portal). No consumers in `lucien`/`common`/`grpc`/`sentry`/`render`/`dyada-java`.
3. **`common` carries JavaFX.** `common/pom.xml:19` declares `javafx-graphics`; `lucien` has no direct JavaFX. The dependency chain `portal → lucien → common` already exists, so placing the math in `lucien` (or a new vecmath-only module) removes JavaFX from the headless path with no cycle; placing it in `common` does not.
4. **Portal needs no new dependency** post-extraction (already depends on `lucien`/`common`); it retains a `Point3D`-typed rendering layer. RD-math test coverage already exists in `portal` (3 files).
5. **Clock mismatch confirmed.** `common/.../simulation/distributed/integration/Clock.java:18` is physically in `common` but package-named for `simulation`; its own javadoc (`:26`) says the placement is deliberate (avoids a `lucien`↔`simulation` cycle) — only the package label is wrong. Foldable into this pass.

## Open Questions

- ~~Is `Tetrahedral` cleanly separable, or is the RD math entangled with JavaFX `Point3D`/`Transform` types that would need swapping to `vecmath`?~~ **Resolved:** Mostly separable — `toRDG` + the vecmath methods are JavaFX-free; only the two `toCartesian(...)→Point3D` overloads are entangled and need a return-type swap to `Point3f`. The inheritance chain (`Tetrahedral→RDGCS→Grid`) is what drags JavaFX, so the kernel must be lifted *out* of that hierarchy.
- ~~New dedicated module vs. landing in `common`/`lucien` — what does the dependency graph prefer?~~ **Resolved (narrowed):** `common` is out (it already pulls `javafx-graphics`). Choice is `lucien` (minimal change) vs a new vecmath-only `geometry` module (purer, but `lucien` is being shrunk by RDR-008). Final pick deferred to gate.
- ~~Does `portal` then depend on the new module (re-import the math) or keep its own copy for rendering?~~ **Resolved:** `portal` re-depends (it already depends on `lucien`/`common`) and keeps a thin `Point3D`-typed rendering layer over the extracted kernel.
- ~~Scope: bundle `Luciferase-7n1` (Clock rename) here, or keep separate?~~ **Resolved (recommended):** Bundle — same "domain primitive in the right module" pass.

**New follow-up from research:** `common` declaring `javafx-graphics` undermines it as a leaf module — worth a separate cleanup item.

## Decision

Accepted 2026-05-25 (gate PASSED, self-reviewed). Locked:

1. **Extract the UI-free RD math kernel** (`toRDG` + the vecmath methods) as a **standalone class with no `Grid`/`RDGCS`/`javafx.*` inheritance** — the inheritance chain is the JavaFX carrier, so the kernel must be lifted out of it, not subclassed.
2. **Home:** a new `vecmath`-only geometry module is preferred over `lucien` (which RDR-008 is shrinking); `common` is disqualified (it already pulls `javafx-graphics`). The final home is an implementation-planning pick between those two — both are cycle-free.
3. **Scope includes the `von`-package `Point3D` usages** (`TransportNeighborInfo.java:22`, `Message.java`), not just `BubbleBounds` — otherwise `mvn dependency:tree -pl simulation` will still show `javafx-*`. The `toCartesian` `Point3D`→`Point3f` change ripples to `BubbleBounds.toCartesian`/`centroid`.
4. **Fold in the Clock package rename** (`Luciferase-7n1`) and **port the three existing portal RD tests** to the new home.

## Consequences

- **Positive:** removes JavaFX from the headless `simulation`/distributed classpath; puts the RD math where RDR-003's FCC work can reach it without UI coupling; corrects the `domain-primitive-in-wrong-module` smell (incl. Clock).
- **Cost / risk:** an internal API ripple (`Point3D`→`Point3f`) across `BubbleBounds` and the `von` records; if a new module is chosen, one module of overhead. `portal` keeps a thin `Point3D`-typed rendering layer over the kernel.
- **Watch:** `TransportNeighborInfo` Phase 6B will introduce a `BubbleBounds` wire dependency — at that point it must cross-reference RDR-004's deserialization hardening. **Follow-up:** `common`'s `javafx-graphics` dependency undermines it as a leaf module (separate cleanup).

## Implementation Progress

### Corrected blast radius (2026-05-28)

A pre-implementation blast-radius sweep found the JavaFX coupling is **wider than the
"`BubbleBounds` + `von`" scope this RDR documented**. `javafx.geometry.Point3D` is used across
~21 `simulation/src/main` files: the `von` package (10), `distributed/migration` (5 records),
`bubble` (4, incl. `BubbleBounds`), `ghost/GhostSyncVONIntegration`, and
`delos/fireflies/TetreeKeyRouter`. The `dependency:tree`-shows-no-`javafx-*` acceptance criterion
therefore requires migrating **all** of them, not just `BubbleBounds`/`von`.

Two corrections to the locked Decision:
- **Position type is `Point3d` (double), not `Point3f`.** The RDR text said `Point3f`; swapping the
  distributed-simulation position type to float would silently drop precision on AOI/distance/
  boundary math. The precision-preserving replacement for javafx `Point3D` is `javax.vecmath.Point3d`.
- **`BubbleBounds` was the *only* `simulation` main file importing `portal`** — so breaking the
  `simulation → portal` *module* dependency (the literal D5 goal) is separable from, and smaller
  than, fully clearing `javafx-*`.

### Phased delivery

- **PR1 (this increment, bead Luciferase-jvs):** new `geometry` module (vecmath-only, no JavaFX);
  extracted the UI-free RD-math kernel as standalone `geometry/.../rd/RDGCoordinates` (no
  `Grid`/`RDGCS`/`javafx` inheritance — the inheritance chain is the JavaFX carrier);
  `toCartesian` returns `Point3d`. Repointed `BubbleBounds` off `portal.Tetrahedral`;
  **dropped the `portal` dependency from `simulation/pom.xml`** (`mvn dependency:tree -pl simulation`
  confirms no `portal`). Added kernel coverage (`RDGCoordinatesTest`: round-trip identity, metric-
  tensor dot, neighbor shells — guards the 7jk/6oa/xnf fixes). Two latent transitive couplings
  surfaced and were made explicit: (a) `org.jetbrains:annotations` convergence pin (javalin 24.1.0
  vs kotlin-stdlib 13.0, previously masked by portal's tree); (b) explicit `render` dependency
  (simulation.viz.render.SerializationUtils uses render ESVT types, previously satisfied via
  portal→render). `portal.Tetrahedral` retained unchanged as the JavaFX-typed rendering variant.
- **PR2 (follow-up):** repo-wide `Point3D` → `Point3d` migration across the ~21 files so
  `mvn dependency:tree -pl simulation` shows zero `javafx-*`; this is where the criterion is met.
  JavaFX still reaches `simulation` transitively via `render` and `common` after PR1.
- **Deferred:** Clock package rename (`Luciferase-7n1`) — kept out of PR1 to keep the diff
  reviewable; it is an orthogonal ~26-file package move.

### PR2 complete — D5 javafx-free criterion met (2026-05-28)

Bead Luciferase-3pu. The `mvn dependency:tree -pl simulation` shows **zero `javafx-*` and zero `lwjgl`** — the headless simulation/distributed classpath no longer carries the JavaFX toolkit. Delivered in two steps:

1. **Point3D → Point3d migration** (58 files, main + test): replaced all
   `javafx.geometry.Point3D` with `javax.vecmath.Point3d`. Mechanically safe —
   the immutable-vs-mutable hazard (`.add`/`.subtract`/`.normalize`) never applied
   (those calls were all on `Vector3f`, already vecmath); accessors (`getX/Y/Z`),
   `.distance()`, and the `(double,double,double)` ctor are signature-identical;
   wire format already decomposed to `posX/Y/Z` primitives. `BubbleBounds.toCartesian`
   now returns the kernel's `Point3d` directly (dropped the PR1 javafx wrapper).

2. **Transitive-carrier exclusions** (simulation/pom.xml): after the migration,
   javafx/lwjgl still reached simulation's compile classpath via two deps whose
   javafx-using classes simulation never links —
   - `render` → javafx-controls + lwjgl (simulation.viz.render uses only render's
     pure-data ESVT types); 65 viz.render runtime tests pass with it excluded.
   - `common` → javafx-graphics (for `common.mesh.MeshLoader`, unused by simulation).
   Both excluded at simulation's dependency declarations.

**Remaining (separate, not blocking D5):** isolate `MeshLoader`'s javafx out of the
`common` leaf module so common stops advertising `javafx-graphics` to all consumers
(the original RDR follow-up). Clock package rename (Luciferase-7n1) still deferred.

### common-leaf javafx cleanup done (2026-05-28, Luciferase-3pu)

The "isolate MeshLoader's javafx out of common" follow-up is **done** (no longer deferred).
`common.mesh.MeshLoader` — the sole javafx user in the `common` leaf module, used only by
`render` (`esvo.app`) — was relocated `common.mesh → render.mesh`, and `javafx-graphics` was
removed from `common/pom.xml`. `common` is now javafx-free, which clears JavaFX from the API of
**all** consumers, not just simulation's excluded view; the simulation-side `common` javafx
exclusion was accordingly dropped (the `render` exclusion remains — render legitimately carries
JavaFX/LWJGL for its GPU/UI renderers). Verified common/portal/lucien/render/simulation compile;
common + simulation dependency trees javafx-free.

**Only remaining RDR-006 item:** Clock package rename (Luciferase-7n1), still deferred.

### Correction: Clock rename already done; RDR-006 complete (2026-05-28)

Earlier notes listed the Clock package rename (Luciferase-7n1) as a remaining
deferred item. That is **stale** — 7n1 was already CLOSED via PR #128 (2026-05-27):
`Clock` was moved from the split `simulation.distributed.integration` package to
the common-owned `com.hellblazer.luciferase.common.time`, eliminating the split
package (81 imports rewritten). The research note predated that merge.

**RDR-006 is therefore functionally complete:** sim→portal module coupling broken
(PR #149), javafx/lwjgl fully cleared from the simulation classpath including the
common-leaf cleanup (PR #153), and the Clock rename done (PR #128). No outstanding
items. (The residual `simulation/src/test/.../distributed/integration/` files —
TestClock, InjectableClock, ClockTest — are simulation's own test utilities and
correctly placed.)
