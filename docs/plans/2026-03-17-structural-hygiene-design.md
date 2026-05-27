# Structural Hygiene Remediation — Design

**Date**: 2026-03-17
**Status**: Approved
**Scope**: 6 issues across CLAUDE.md, common, lucien, simulation modules

## Issues & Approaches

### 1. CLAUDE.md Module Count (Medium)
**Problem**: Lists 12 modules, only 8 are internal Maven modules.
**Fix**: Update module table. Add notes for von (package in simulation), gpu-test-framework/resource (external gpu-support), e2e-test (nonexistent).

### 2. Clock Interface Triplication (High)
**Problem**: 3 different Clock.java files: common (88 lines), simulation (109 lines, stricter), lucien/test (85 lines).
**Fix**:
- Upgrade common/Clock.java to simulation's stricter semantics (UnsupportedOperationException on fixed().nanoTime())
- Delete simulation/Clock.java
- Keep lucien/test/Clock.java with @see comment (breaks circular dep)

### 3. FaultTolerantDistributedForest Duplication (High)
**Problem**: 562-line heavyweight in fault/ vs 184-line lightweight in balancing/ — same name, different classes.
**Fix**: Rename balancing/ version → SimpleFaultTolerantForest. Update P412DecoratorPatternTest.

### 4. GhostSyncFaultAdapter Duplication (High)
**Problem**: 104-line in fault/ vs 142-line in balancing/ — different implementations.
**Fix**: Rename balancing/ version → SimpleGhostSyncAdapter. Update P413/P414 tests.

### 5. Test Infrastructure in Main Sources (Medium)
**Problem**: 6 mock/test files in lucien/src/main/java/.../balancing/fault/test/.
**Fix**: Move to lucien/src/test/java/.../balancing/fault/test/. Verified: no main source imports.

### 6. MultiBubbleSimulation Duplication (Medium)
**Problem**: Grid-based vs orchestrator-based in different packages, same name.
**Fix**: Rename distributed/grid/ version → GridMultiBubbleSimulation. Update test class.

## Verification
1. `mvn clean compile -DskipTests` — no broken imports
2. `mvn test -pl lucien` — affected tests pass
3. `mvn test -pl simulation` — affected tests pass
