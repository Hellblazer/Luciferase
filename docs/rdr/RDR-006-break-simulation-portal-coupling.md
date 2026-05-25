---
title: "Break the simulation→portal Coupling (BubbleBounds JavaFX Pull-In)"
id: RDR-006
type: Architecture
status: draft
priority: medium
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-24
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

> To be completed in `/nx:rdr-research` + design. Initial candidate directions:

1. **Isolate the UI-free geometry** — determine the exact subset of `Tetrahedral`/`RDG`/`RDGCS` that is pure RD coordinate math (no `javafx.*` references) vs. what is genuinely visualization (mesh/scene-graph helpers).
2. **Pick the new home** — `common` (already a leaf-ish dependency), `lucien` (spatial-index module, natural for spatial math), or a new small `geometry`/`rd-geom` module. Must NOT depend on JavaFX.
3. **Extract + re-point** — move the UI-free RD math to the chosen module; `BubbleBounds` and any other non-UI consumers depend on that; `portal` keeps (or re-depends on) the math for its rendering and adds only the JavaFX-specific layer.
4. **Verify JavaFX drops off the headless classpath** — `mvn dependency:tree -pl simulation` must show no `javafx-*` after the change.
5. **Add coverage** for the extracted RD math (currently zero per `portal-rdfcc-quality`).
6. Consider folding the Clock package rename (`Luciferase-7n1`) into the same "domain primitives in the right module" pass.

## Open Questions

- Is `Tetrahedral` cleanly separable, or is the RD math entangled with JavaFX `Point3D`/`Transform` types that would need swapping to `vecmath`?
- New dedicated module vs. landing in `common`/`lucien` — what does the dependency graph prefer?
- Does `portal` then depend on the new module (re-import the math) or keep its own copy for rendering?
- Scope: bundle `Luciferase-7n1` (Clock rename) here, or keep separate?

## Decision

_Pending research + gate._

## Consequences

_Pending._
