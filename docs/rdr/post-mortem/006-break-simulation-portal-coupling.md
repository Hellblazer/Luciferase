# Post-Mortem: RDR-006 — Break the simulation→portal Coupling

**Closed:** 2026-05-28 — implemented
**Outcome:** `simulation` no longer depends on `portal`, and `mvn dependency:tree -pl simulation` carries **zero `javafx-*` and zero `lwjgl`**. Headless distributed-simulation deployments no longer drag the JavaFX toolkit onto the classpath — the goal of the whole RDR.

## What shipped

| Phase | Work | PRs |
|-------|------|-----|
| PR1 — break the module coupling | New vecmath-only `geometry` module; extracted the UI-free RD coordinate math from `portal.Tetrahedral` into a standalone `RDGCoordinates` (no `Grid`/`RDGCS`/`javafx` inheritance — that chain was the JavaFX carrier); repointed `BubbleBounds` off `portal.Tetrahedral`; **removed the `portal` dependency from `simulation/pom.xml`**. `portal.Tetrahedral` delegates its pure-math methods to the kernel (single source of truth for the 7jk/6oa/xnf fixes). Also tagged two JavaFX collision-visualization tests `@Tag("javafx")` so CI excludes them. | #149 |
| Clock rename (D6) | Moved `Clock` from the split `simulation.distributed.integration` package to common-owned `com.hellblazer.luciferase.common.time` (81 imports rewritten; split package eliminated). | #128 |
| PR2 — clear javafx from the classpath | `Point3D` → `Point3d` migration across 58 files (main + test); excluded javafx/lwjgl from simulation's `render` dependency (simulation uses only render's pure-data ESVT types); relocated `common.mesh.MeshLoader` → `render.mesh` and dropped `javafx-graphics` from `common/pom.xml` entirely. | #153 |

Final state: `simulation` and `common` dependency trees javafx/lwjgl-free; full simulation suite (2636 tests) green bar one known probabilistic flake; full reactor green in CI on both PRs.

## What went well

- **Pre-implementation API survey de-risked the migration.** Before touching the 58 Point3D files, an exhaustive survey established that the dangerous immutable-vs-mutable hazard (javafx `a.add(b)` returns new; vecmath mutates in place) *never applied* — every `.add/.subtract/.normalize` call was already on `Vector3f`. That turned a feared semantic minefield into a verified mechanical token-swap.
- **Stacked review caught the right things** (per `feedback_review-stacking`). On PR1 the substantive-critic flagged the duplicate fix-laden math (two copies of 7jk/6oa/xnf) as a divergence hazard; that drove the `Tetrahedral`-delegates-to-kernel design, and portal's 31 existing RD-math tests then cross-validated kernel ≡ portal behavior.
- **Each layer was empirically validated before proceeding** — kernel tests, BubbleBounds tests, viz.render runtime tests (proving the render exclusion is safe), and the full 2636-test suite. The dependency:tree was the objective acceptance oracle throughout.
- **The relocation beat the workaround.** When the user asked "do we even use MeshLoader," chasing it produced the *proper* fix (move to render, strip javafx from the common leaf) rather than leaving the simulation-scoped exclusion stopgap — clearing javafx from common's API for all consumers.

## Lessons / what was tricky

1. **The documented blast radius was wrong, and verifying it first mattered.** The accepted RDR scoped the coupling as "BubbleBounds + von" (~2 files). A blast-radius sweep found ~21 `javafx.geometry.Point3D` files across von, distributed/migration, bubble, ghost, and delos/fireflies. Had the migration trusted the RDR's count, the `dependency:tree` criterion would have silently failed. The corrected scope was recorded in the RDR's Implementation Progress before any code changed.
2. **The RDR's stated type (`Point3f`) would have caused a silent precision regression.** `Point3f` is float; the javafx `Point3D` positions are double. The precision-preserving replacement is `Point3d`. A literal reading of the accepted RDR would have quietly downgraded AOI/distance/boundary math. Worth flagging assumptions in an accepted doc rather than executing them blindly.
3. **Breaking one module dependency surfaced latent transitive ones.** Dropping `portal` from `simulation` exposed two couplings portal had been masking: a `jetbrains:annotations` convergence conflict (javalin vs kotlin-stdlib), and a real but undeclared `simulation → render` dependency (`viz.render.SerializationUtils` uses render's ESVT types). Both were made explicit/honest rather than papered over — and PR1's review correctly pushed back on framing the render dep as "hygiene" when it was net-neutral on JavaFX.
4. **"Clear javafx" needed three carriers removed, not one.** Migrating simulation's own Point3D usage was necessary but insufficient — javafx still arrived via `render` (PR1's coupling) and `common` (MeshLoader). The criterion is only met once *every* compile-classpath carrier is addressed; the dependency:tree is the only honest check.
5. **A leaf module advertising a UI dependency for one class poisons everyone.** `common` declared `javafx-graphics` solely for `MeshLoader`, forcing JavaFX onto the transitive classpath of every consumer. Relocating the single misplaced class to where it was actually used (`render`) was the structural fix; the per-consumer exclusion was a band-aid.
6. **A stale research note nearly created phantom work.** RDR-006's research (2026-05-25) listed the Clock rename as outstanding; it had in fact been completed by PR #128 (2026-05-27). Reconciling against the actual repo state (one `Clock.java`, correctly in `common.time`) before "doing" it avoided redundant churn.

## Process notes

- T2 never held an RDR-006 record (nor 004/005) — the file frontmatter was the source of truth; the close was registered into T2 (`Luciferase_rdr/006`) as part of this close to reconcile.
- The bead gate was clean: RDR-006's own beads (`Luciferase-jvs` PR1, `Luciferase-3pu` PR2) both closed; the open beads in the workspace belong to other RDRs (ah3/irh/va5 → RDR-004/005; pi1.* → RDR-010).

## Follow-ups (tracked, out of RDR-006 scope)

- None outstanding for RDR-006 itself. The `render` javafx/lwjgl exclusion in `simulation/pom.xml` remains (render legitimately carries JavaFX/LWJGL for its GPU/UI renderers, which simulation's pure-data ESVT path never touches); relocating `simulation.viz.render.*` into the `render` module would remove even that coupling, but is not required for the javafx-free criterion and was left as an optional architectural cleanup.
