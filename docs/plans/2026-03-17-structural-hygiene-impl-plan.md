# Structural Hygiene Remediation -- Implementation Plan

**Date**: 2026-03-17
**Status**: Audited — READY (with corrections applied)
**Design**: docs/plans/2026-03-17-structural-hygiene-design.md
**Branch**: feature/structural-hygiene-remediation
**Git strategy**: One commit per phase for clean bisect

---

## Overview

6 confirmed technical debt issues, organized into 5 execution phases ordered by risk (lowest first). Each phase is independently verifiable. No cross-phase dependencies exist between the 6 issues, but phasing provides clean verification boundaries.

## Dependency Graph

```
Phase 1 (CLAUDE.md)  ──┐
Phase 2 (test move)  ──┼── all independent, ordered by risk
Phase 3 (lucien renames) ──┤
Phase 4 (Clock canon) ──┤
Phase 5 (sim rename) ──┘
                        │
                        v
              Final: mvn clean install
```

Critical path: Phase 4 (Clock) is highest risk due to cross-module semantic change. All other phases are module-scoped.

---

## Phase 1: CLAUDE.md Module Table (Issue 1)

**Risk**: None (documentation only)
**Commit message**: `docs: correct CLAUDE.md module table from 12 to 8 entries`

### Steps

1. Edit `/Users/hal.hildebrand/git/Luciferase/CLAUDE.md` lines 74-87
2. Remove rows: `von`, `gpu-test-framework`, `resource`, `e2e-test`
3. Keep 8 rows: common, grpc, lucien, render, sentry, portal, simulation, dyada-java
4. Add note after table:

```markdown
> **Note**: `von` is a package inside the simulation module (not a separate module).
> `gpu-test-framework` and `resource` are external dependencies from `com.hellblazer.gpu-support`.
> `e2e-test` does not exist in this repository.
```

### Verification

- Visual review: table has 8 rows matching `ls */pom.xml` output
- No compilation needed

### Acceptance Criteria

- [ ] Module table lists exactly 8 modules
- [ ] Footnote explains von, gpu-test-framework, resource, e2e-test

---

## Phase 2: Test Infrastructure Move (Issue 5)

**Risk**: Low
**Commit message**: `refactor(lucien): move test infrastructure from main to test sources`

### Context

6 mock/test helper files in `lucien/src/main/java/.../balancing/fault/test/` belong in test sources. Verified: no main source file imports from this package.

### Steps

1. Create destination directory:
```bash
mkdir -p lucien/src/test/java/com/hellblazer/luciferase/lucien/balancing/fault/test
```

2. Move all 6 files via `git mv`:
```bash
BASE_SRC=lucien/src/main/java/com/hellblazer/luciferase/lucien/balancing/fault/test
BASE_DST=lucien/src/test/java/com/hellblazer/luciferase/lucien/balancing/fault/test

git mv $BASE_SRC/TestFaultScenarios.java  $BASE_DST/
git mv $BASE_SRC/FaultInjectionHelper.java $BASE_DST/
git mv $BASE_SRC/TestAssertions.java       $BASE_DST/
git mv $BASE_SRC/PartitionSimulator.java   $BASE_DST/
git mv $BASE_SRC/MockPartitionView.java    $BASE_DST/
git mv $BASE_SRC/MockFaultHandler.java     $BASE_DST/
```

3. Remove empty source directory: `rmdir $BASE_SRC`

4. No edits needed -- package declarations remain `com.hellblazer.luciferase.lucien.balancing.fault.test`

### Verification

```bash
mvn clean compile -pl lucien -DskipTests    # main sources still compile
mvn test-compile -pl lucien                  # test sources compile
```

### Acceptance Criteria

- [ ] No files remain in lucien/src/main/.../fault/test/
- [ ] All 6 files present in lucien/src/test/.../fault/test/
- [ ] `mvn clean compile -pl lucien -DskipTests` passes
- [ ] `mvn test-compile -pl lucien` passes

---

## Phase 3: Lucien Class Renames (Issues 3 + 4)

**Risk**: Medium
**Commit message**: `refactor(lucien): rename ambiguous balancing classes to avoid fault/ collisions`

### 3a: FaultTolerantDistributedForest -> SimpleFaultTolerantForest

**Source**: `lucien/src/main/java/.../balancing/FaultTolerantDistributedForest.java`
**Unchanged**: `lucien/src/main/java/.../balancing/fault/FaultTolerantDistributedForest.java`

#### Steps

1. Rename file:
```bash
git mv lucien/src/main/java/.../balancing/FaultTolerantDistributedForest.java \
       lucien/src/main/java/.../balancing/SimpleFaultTolerantForest.java
```

2. Edit `SimpleFaultTolerantForest.java`:
   - Replace class name `FaultTolerantDistributedForest` with `SimpleFaultTolerantForest` (in class declaration, constructors, factory methods, any self-referencing Javadoc)

3. Edit `P412DecoratorPatternTest.java`:
   - Replace all `FaultTolerantDistributedForest` references with `SimpleFaultTolerantForest`
   - Update Javadoc description

4. Edit `fault/FaultTolerantDistributedForest.java`:
   - Update Javadoc `@link` (~line 34) from `balancing.FaultTolerantDistributedForest` to `balancing.SimpleFaultTolerantForest`
   - Update Javadoc `@see` (~line 46) from `balancing.FaultTolerantDistributedForest` to `balancing.SimpleFaultTolerantForest`

### 3b: GhostSyncFaultAdapter -> SimpleGhostSyncAdapter

**Source**: `lucien/src/main/java/.../balancing/GhostSyncFaultAdapter.java`
**Unchanged**: `lucien/src/main/java/.../balancing/fault/GhostSyncFaultAdapter.java`

#### Steps

1. Rename file:
```bash
git mv lucien/src/main/java/.../balancing/GhostSyncFaultAdapter.java \
       lucien/src/main/java/.../balancing/SimpleGhostSyncAdapter.java
```

2. Edit `SimpleGhostSyncAdapter.java`:
   - Replace class name `GhostSyncFaultAdapter` with `SimpleGhostSyncAdapter` (class declaration, constructors)

3. Edit `P413GhostSyncFaultWiringTest.java`:
   - Replace all `GhostSyncFaultAdapter` references with `SimpleGhostSyncAdapter`

4. Edit `P414IntegrationSuiteTest.java`:
   - Replace all `GhostSyncFaultAdapter` references with `SimpleGhostSyncAdapter`

5. Edit `DistributedGhostManager.java` (line ~367):
   - Update Javadoc `@link` from `balancing.GhostSyncFaultAdapter` to `balancing.SimpleGhostSyncAdapter`

### Verification

```bash
mvn clean compile -pl lucien -DskipTests
mvn test -pl lucien -Dtest="P412DecoratorPatternTest,P413GhostSyncFaultWiringTest,P414IntegrationSuiteTest,PhaseA2FaultTolerantDistributedForestTest,Phase43IntegrationTest"
```

### Acceptance Criteria

- [ ] No file named `balancing/FaultTolerantDistributedForest.java` (only `fault/` copy remains)
- [ ] No file named `balancing/GhostSyncFaultAdapter.java` (only `fault/` copy remains)
- [ ] `SimpleFaultTolerantForest.java` exists in balancing/
- [ ] `SimpleGhostSyncAdapter.java` exists in balancing/
- [ ] All 5 test classes pass
- [ ] `grep -r "balancing\.FaultTolerantDistributedForest" lucien/` returns only fault/ subpackage references
- [ ] `grep -r "balancing\.GhostSyncFaultAdapter" lucien/` returns only fault/ subpackage references

---

## Phase 4: Clock Canonicalization (Issue 2)

**Risk**: High (cross-module semantic change)
**Commit message**: `fix(common): canonicalize Clock interface, stricter fixed() nanoTime semantics`

### Context

Three Clock.java files exist:
- **common/** (88 lines) -- canonical, but fixed().nanoTime() returns fixedTime * 1_000_000
- **simulation/** (109 lines) -- stricter: fixed().nanoTime() throws UnsupportedOperationException
- **lucien/test/** (85 lines) -- duck-typed copy to break circular dependency

The simulation version has better semantics (returning a constant from nanoTime() silently produces incorrect elapsed-time calculations). The simulation ClockTest already asserts UnsupportedOperationException.

### Steps

0. **Add common dependency to simulation/pom.xml** (AUDIT CORRECTION: simulation does not depend on common):
   ```xml
   <dependency>
       <groupId>com.hellblazer.luciferase</groupId>
       <artifactId>common</artifactId>
   </dependency>
   ```
   Verify: `mvn compile -pl simulation -DskipTests` passes before proceeding.

1. **Upgrade common/Clock.java** (`/Users/hal.hildebrand/git/Luciferase/common/src/main/java/com/hellblazer/luciferase/simulation/distributed/integration/Clock.java`):
   - Replace the `fixed()` method (lines 69-87) with simulation's stricter version:
     - nanoTime() throws UnsupportedOperationException with descriptive message
     - Update Javadoc on the `fixed()` method to document the exception
   - Merge simulation's improved Javadoc for `nanoTime()` default method (document that it measures relative time, not absolute)
   - Keep the AGPL copyright header as-is

2. **Add @see to lucien test Clock.java** (`/Users/hal.hildebrand/git/Luciferase/lucien/src/test/java/com/hellblazer/luciferase/simulation/distributed/integration/Clock.java`):
   - Add to class Javadoc: `@see com.hellblazer.luciferase.simulation.distributed.integration.Clock` (canonical in common module)
   - Explain this copy exists to break lucien -> simulation circular dependency

3. **Delete simulation/Clock.java**:
```bash
git rm simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/integration/Clock.java
```

### Risk Mitigation

Verified callers of Clock.fixed() that also call nanoTime():
- **simulation/ClockTest.java**: Already expects UnsupportedOperationException (line 52-64) -- SAFE
- **lucien/Phase42ClockIntegrationTest.java**: Uses Clock.fixed(5000) but only calls currentTimeMillis() -- SAFE
- **lucien/FaultDetectionBenchmark.java**: Uses java.time.Clock.fixed() (different API entirely) -- NOT AFFECTED
- **lucien/PartitionStatusTracker.java**: Uses java.time.Clock (different API) -- NOT AFFECTED
- **lucien/FailureDetector.java**: Uses java.time.Clock (different API) -- NOT AFFECTED

No caller will break from this change.

### Verification

```bash
mvn clean compile -DskipTests                           # cross-module compilation
mvn test -pl simulation -Dtest=ClockTest                # Clock semantics
mvn test -pl lucien -Dtest=Phase42ClockIntegrationTest  # lucien Clock usage
```

### Acceptance Criteria

- [ ] Only 2 Clock.java files remain (common/main, lucien/test)
- [ ] simulation/Clock.java deleted
- [ ] common/Clock.fixed().nanoTime() throws UnsupportedOperationException
- [ ] lucien/test/Clock.java has @see pointing to canonical
- [ ] `mvn clean compile -DskipTests` passes
- [ ] ClockTest passes
- [ ] Phase42ClockIntegrationTest passes

---

## Phase 5: MultiBubbleSimulation Rename (Issue 6)

**Risk**: Medium
**Commit message**: `refactor(simulation): rename grid MultiBubbleSimulation to GridMultiBubbleSimulation`

### Context

Two classes named MultiBubbleSimulation:
- `simulation/.../bubble/MultiBubbleSimulation.java` -- orchestrator (KEEP)
- `simulation/.../distributed/grid/MultiBubbleSimulation.java` -- grid-based (RENAME)

No external imports of the grid version found. All references are within the grid package.

### Steps

1. Rename source:
```bash
git mv simulation/src/main/java/.../distributed/grid/MultiBubbleSimulation.java \
       simulation/src/main/java/.../distributed/grid/GridMultiBubbleSimulation.java
```

2. Edit `GridMultiBubbleSimulation.java`:
   - Replace class name `MultiBubbleSimulation` with `GridMultiBubbleSimulation` (class declaration, constructors, factory methods)

3. Rename test:
```bash
git mv simulation/src/test/java/.../distributed/grid/MultiBubbleSimulationTest.java \
       simulation/src/test/java/.../distributed/grid/GridMultiBubbleSimulationTest.java
```

4. Edit `GridMultiBubbleSimulationTest.java`:
   - Replace class name `MultiBubbleSimulationTest` with `GridMultiBubbleSimulationTest`
   - Replace all `new MultiBubbleSimulation(` with `new GridMultiBubbleSimulation(`

5. Edit all other grid-package files that reference MultiBubbleSimulation (AUDIT CORRECTION: must explicitly update, not just check):
   - `PerformanceBenchmark.java` -- replace all `MultiBubbleSimulation` with `GridMultiBubbleSimulation`
   - `MultiBubbleGhostSyncTest.java` -- replace all `MultiBubbleSimulation` with `GridMultiBubbleSimulation`
   - `MultiBubbleLoadTest.java` -- replace all `MultiBubbleSimulation` with `GridMultiBubbleSimulation`, including inner class `MultiBubbleSimulation.EntitySnapshot` → `GridMultiBubbleSimulation.EntitySnapshot`

### Verification

```bash
mvn clean compile -pl simulation -DskipTests
mvn test -pl simulation -Dtest="GridMultiBubbleSimulationTest,MultiBubbleSimulationTest"
```

The second test name (`MultiBubbleSimulationTest`) runs the bubble/ package test to ensure it's unaffected.

### Acceptance Criteria

- [ ] No file named `distributed/grid/MultiBubbleSimulation.java`
- [ ] `GridMultiBubbleSimulation.java` exists in grid/
- [ ] `GridMultiBubbleSimulationTest.java` exists and passes
- [ ] `bubble/MultiBubbleSimulationTest.java` still passes (unaffected)
- [ ] `mvn clean compile -pl simulation -DskipTests` passes

---

## Final Verification

After all phases complete:

```bash
mvn clean install
```

### Global Acceptance Criteria

- [ ] Full build passes with all tests
- [ ] `grep -rn "FaultTolerantDistributedForest" lucien/src/main/java/*/balancing/` shows only fault/ subpackage
- [ ] `grep -rn "GhostSyncFaultAdapter" lucien/src/main/java/*/balancing/` shows only fault/ subpackage
- [ ] `find lucien/src/main -path "*/fault/test/*"` returns empty
- [ ] `find . -name "Clock.java" -path "*/simulation/distributed/integration/*"` returns exactly 2 files (common/main, lucien/test)
- [ ] CLAUDE.md module table has 8 entries

---

## Parallelization Guidance

- **Phases 1 + 2**: Can execute in parallel (zero overlap in files)
- **Phase 3**: Depends on Phase 2 completing (both touch lucien; easier to verify sequentially)
- **Phase 4**: Independent, but run after Phase 3 to isolate failures
- **Phase 5**: Independent, can run in parallel with Phase 3 or 4

**Recommended**: Execute sequentially for a single implementing agent. Parallel only if using separate agents per module.

---

## Rollback Strategy

Each phase is a separate commit. To roll back any phase:

```bash
git revert <phase-commit-hash>
```

No phase creates a dependency that subsequent phases rely on, so any single phase can be reverted independently.
