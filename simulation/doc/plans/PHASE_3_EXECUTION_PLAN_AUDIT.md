# Phase 3 Execution Plan -- Audit Report

**Date**: 2026-02-14
**Auditor**: plan-auditor
**Plan Under Review**: `simulation/doc/plans/PHASE_3_EXECUTION_PLAN.md` (1,176 lines)
**Architecture Reference**: `simulation/doc/plans/PHASE_3_VIEWPORT_STREAMING_ARCHITECTURE.md`
**Verdict**: CONDITIONAL GO -- fix 2 critical API errors and 2 moderate inconsistencies before implementation

---

## 1. Audit Criteria Checklist

| # | Criterion | Result | Notes |
|---|-----------|--------|-------|
| 1 | All 7 implementation steps from architecture Section 9.1 mapped to beads | PASS | Steps 1-2 merged into Day 1, Step 3 = Day 2, Step 4 split Days 3-4, Step 5 split Days 5-6, Steps 6-7 merged Day 7 |
| 2 | Dependency graph has no cycles and critical path is correct | PASS | DAG verified: 5kdb -> {rgnd, 2t6d} -> {r21u, 0k8o} -> ibme -> u006. Critical path: 5kdb -> 2t6d -> 0k8o -> ibme -> u006 (5 steps) |
| 3 | Each day has TDD red-green cycle specified | PASS | All 7 days show explicit RED -> GREEN -> REFACTOR sequences |
| 4 | Test count covers 30+ tests (architecture specifies 30+, plan has 41) | PASS | Plan specifies 41 tests across 5 test classes: ClientViewportTest(3), BinaryFrameCodecTest(7), ViewportTrackerTest(16), RegionStreamerTest(12), EndToEndStreamingTest(3) |
| 5 | Risk assessment covers each day | PASS | R1-R10 mapped to Days 1-7 with severity/likelihood/mitigation |
| 6 | Hour estimates are realistic (6-8h per day) | PASS | Range: 6h (Day 2) to 8h (Day 7), total 49h across 7 days |
| 7 | All verified API dependencies match actual codebase | FAIL | 2 critical API mismatches in test examples (see F1, F2 below) |
| 8 | No gaps between architecture spec and execution plan | PASS with NOTES | LOC estimate inconsistency (see F3), constructor count undercount (see F4) |

---

## 2. Critical Findings (Must Fix Before Implementation)

### F1: EntityPosition Constructor Signature Mismatch

**Severity**: CRITICAL
**Location**: Plan lines 435-439, 839
**Impact**: Test code will not compile

**Problem**: Plan examples use `long` as 5th constructor parameter:
```java
// Plan says:
new EntityPosition("e1", 128f, 128f, 128f, 0L)  // 0L is a long
```

**Actual API** (`EntityPosition.java:23`):
```java
public record EntityPosition(String id, float x, float y, float z, String type)
```

The 5th parameter is `String type` (e.g., "PREY", "PREDATOR"), not `long`.

**Fix**: Change all occurrences of `0L` to `"ENTITY"` or `"PREY"`:
```java
new EntityPosition("e1", 128f, 128f, 128f, "PREY")
```

**Affected lines**: 435, 436, 437, 438, 439, 839 (6 occurrences)

---

### F2: AdaptiveRegionManager.updateEntity() API Mismatch

**Severity**: CRITICAL
**Location**: Plan lines 435-439
**Impact**: Test code will not compile

**Problem**: Plan shows calling `updateEntity` with an `EntityPosition` record:
```java
// Plan says:
regionManager.updateEntity("e1", new EntityPosition("e1", 128f, 128f, 128f, 0L));
```

**Actual API** (`AdaptiveRegionManager.java:263`):
```java
public void updateEntity(String entityId, float x, float y, float z, String type)
```

The method takes primitive arguments, not an `EntityPosition` record.

**Fix**: Change all occurrences to use primitive arguments:
```java
regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");
```

**Affected lines**: 435, 436, 437, 438, 439 (5 occurrences)

---

## 3. Moderate Findings (Should Fix)

### F3: LOC Estimate Inconsistency

**Severity**: MODERATE
**Location**: Plan line 24 vs line 1022

**Problem**: Executive summary (line 24) states:
> ~1,800 LOC production, ~2,200 LOC test

Section 6 file layout (line 1022) totals:
> ~1,105 LOC new + ~70 LOC modified = ~1,175 LOC production
> ~1,540 LOC test

Delta: 625 LOC production (53% inflation), 660 LOC test (43% inflation) in summary vs detail.

**Risk**: Inflated LOC estimates may cause implementers to over-engineer, expecting more code than architecturally required.

**Fix**: Update executive summary to match Section 6 detailed breakdown:
> ~1,175 LOC production, ~1,540 LOC test

---

### F4: RenderingServerConfig Constructor Call Count Undercount

**Severity**: MODERATE
**Location**: Plan line 23, 121, 152

**Problem**: Plan states "~15 existing test call sites" and "15 direct constructor calls" in multiple places.

**Actual count** (verified by grep):
| File | Direct Constructor Calls | Lines |
|------|-------------------------|-------|
| RenderingServerTest.java | 11 | 117, 300, 364, 411, 479, 526, 559, 626, 650, 673, 697 |
| RenderingServerConfigTest.java | 5 | 57, 73, 88, 143, 168 |
| RenderingServerIntegrationTest.java | 3 | 73, 186, 301 |
| **Test total** | **19** | |
| RenderingServerConfig.java (factories) | 3 | 61, 81, 96 |
| **Grand total** | **22** | |

Additionally, `RenderingServerConfigExtensions.withUpstreams()` at `RenderingServerIntegrationTest.java:299-310` is a helper that constructs `new RenderingServerConfig(...)` with all 7 parameters -- this must also be updated and is not mentioned in the plan.

**Note**: Section 6 file layout (lines 1009-1011) actually states 12+4+6=22 total, which contradicts the ~15 stated elsewhere. The Section 6 numbers are closer to correct but slightly inflated (22 vs actual 19 for tests).

**Risk**: Implementer stops searching after finding 15 call sites, leaving 4 unupdated sites that will cause compilation errors.

**Fix**: Update all references from "~15" to "~19" for test call sites, or "~22" including production factories. Explicitly mention `RenderingServerConfigExtensions.withUpstreams()` helper.

---

## 4. Minor Findings (Informational)

### F5: Javalin WsCloseContext Method Name

**Severity**: MINOR
**Location**: Plan line 776

**Problem**: Plan uses `ctx.status()`:
```java
ws.onClose(ctx -> regionStreamer.onClose(ctx, ctx.status(), ctx.reason()));
```

Javalin 6.x `WsCloseContext` provides `statusCode()` (returns `int`) not `status()`. The existing RenderingServer code at line 262 uses `ws.onClose(ctx -> { ... })` without calling status methods directly.

**Fix**: Change to `ctx.statusCode()`:
```java
ws.onClose(ctx -> regionStreamer.onClose(ctx, ctx.statusCode(), ctx.reason()));
```

---

### F6: Risk R3 (RegionBounds centerX/Y/Z) is a False Alarm

**Severity**: MINOR / INFORMATIONAL
**Location**: Plan line 1034 (Risk Register R3)

**Status**: `RegionBounds.java` lines 33-48 confirm `centerX()`, `centerY()`, `centerZ()` methods exist. The plan's own Appendix C also lists RegionBounds verified APIs but omits the center methods. The risk mitigation "Compute in ViewportTracker: (minX+maxX)/2" is unnecessary.

**Fix**: Downgrade R3 to "False alarm -- centerX/Y/Z confirmed to exist" or remove it. Add center methods to Appendix C.

---

## 5. Architecture Coverage Mapping

### 9.1 Build Order Step to Bead Mapping

| Architecture Step | Plan Day | Bead | Coverage |
|-------------------|----------|------|----------|
| Step 1: Data Types | Day 1 | Luciferase-5kdb | Complete: ClientViewport, VisibleRegion, ViewportDiff, StreamingConfig, ProtocolConstants |
| Step 2: RenderingServerConfig modification | Day 1 | Luciferase-5kdb | Complete: Add StreamingConfig field, update factories, update call sites |
| Step 3: BinaryFrameCodec | Day 2 | Luciferase-rgnd | Complete: BinaryFrameCodec + 7 tests |
| Step 4: ViewportTracker | Days 3-4 | Luciferase-2t6d, Luciferase-r21u | Complete: Implementation split from full test suite (good decision) |
| Step 5: RegionStreamer | Days 5-6 | Luciferase-0k8o, Luciferase-ibme | Complete: Core handler split from streaming loop (good decision) |
| Step 6: Integration | Day 7 | Luciferase-u006 | Complete: RenderingServer wiring + E2E tests |
| Step 7: Verification | Day 7 | Luciferase-u006 | Complete: Full regression + quality gates |

All 7 architecture steps are covered. The plan's decision to split Steps 4 and 5 into two days each is sound -- ViewportTracker has 16 tests and RegionStreamer has 12 tests, justifying dedicated test days.

### 9.2 Testability Checkpoints

All 7 checkpoints from architecture Section 9.2 are present in the plan's day structure:

| Checkpoint | Plan Location | Verified |
|------------|---------------|----------|
| Records compile after Step 1 | Day 1, 5.5h mark | Yes |
| All existing tests pass after Step 2 | Day 1, 7h mark (GREEN phase) | Yes |
| BinaryFrameCodecTest passes | Day 2, 6h mark | Yes |
| ViewportTrackerTest passes | Day 4, 7h mark | Yes |
| RegionStreamerTest passes | Day 6, 7h mark | Yes |
| EndToEndStreamingTest passes | Day 7, 6h mark | Yes |
| Full module test suite passes | Day 7, 8h mark (quality gates) | Yes |

### 9.3 Critical Dependencies

All dependencies from architecture Section 9.3 are verified in Plan Appendix C with file paths and line numbers. This is a strength of this plan.

---

## 6. Dependency Graph Analysis

### Cycle Check: PASS

The dependency DAG has no cycles:
```
5kdb (Day 1) --> rgnd (Day 2)
5kdb (Day 1) --> 2t6d (Day 3)
2t6d (Day 3) --> r21u (Day 4)
rgnd (Day 2) --> 0k8o (Day 5)
2t6d (Day 3) --> 0k8o (Day 5)
0k8o (Day 5) --> ibme (Day 6)
r21u (Day 4) --> u006 (Day 7)
ibme (Day 6) --> u006 (Day 7)
```

### Critical Path: 5kdb -> 2t6d -> 0k8o -> ibme -> u006 = 36 hours

The critical path correctly identified as spanning 5 tasks (Days 1, 3, 5, 6, 7).

### Parallelization: Correctly identified

- Days 2+3 can run in parallel after Day 1 (saving 1 day)
- Days 4+5 can partially overlap if Day 2 finishes before Day 3

---

## 7. TDD Compliance

All 7 days follow TDD discipline with explicit RED/GREEN/REFACTOR sequences. Spot-check of each day:

| Day | RED Phase | GREEN Phase | Verified |
|-----|-----------|-------------|----------|
| 1 | ClientViewportTest stubs | Implement records, compile | Yes |
| 2 | BinaryFrameCodecTest 7 tests written | Implement codec, pass all | Yes |
| 3 | ViewportTrackerTest stubs (fail) | Implement ViewportTracker | Yes |
| 4 | Remaining ViewportTrackerTest tests | Complete implementation | Yes |
| 5 | RegionStreamerTest stubs | Implement core handler | Yes |
| 6 | Streaming loop tests | Implement loop, backpressure | Yes |
| 7 | E2E tests | Integration wiring | Yes |

---

## 8. Risk Assessment Evaluation

The plan's risk register (R1-R10) covers:
- Configuration change risk (R1): Properly identified, but call count underestimated (F4)
- Frustum3D constraints (R2): Properly mitigated
- RegionBounds API risk (R3): False alarm (F6) -- methods exist
- Javalin API version (R4): Relevant, but specific `statusCode()` vs `status()` not caught (F5)
- Mock complexity (R5): Properly mitigated
- Streaming timing (R6): Properly mitigated with TestClock
- E2E race conditions (R7): Properly mitigated
- WS stub replacement (R8): Properly identified as HIGH likelihood
- Wiring order (R9): Properly mitigated
- Byte order (R10): Properly mitigated

**Missing Risk**: No risk entry for EntityPosition/updateEntity API mismatch (F1/F2). These would be caught at compile time, but the plan should not contain code that won't compile as-is.

---

## 9. Strengths

1. **Verified API Appendix** (Appendix C): Every external dependency verified with file path and line number. This is best practice.
2. **Clear TDD sequences**: Each day has explicit RED/GREEN/REFACTOR phases with test-first discipline.
3. **Comprehensive test coverage**: 41 tests across 5 test classes exceeds the architecture's 30+ requirement.
4. **Parallelization guidance**: Section 8 correctly identifies which days can run in parallel.
5. **Bead quick reference** (Appendix A): Complete table with IDs, dependencies, and status.
6. **Command reference** (Appendix B): Ready-to-execute Maven and bd commands.
7. **Phase 4 integration points** (Section 9): Forward-looking guidance for the next phase.

---

## 10. Verdict

### CONDITIONAL GO

The plan is structurally sound, well-organized, and covers all architecture requirements. The dependency graph is correct, TDD discipline is embedded throughout, and the bead hierarchy properly reflects the work breakdown.

**However**, 2 critical API errors in test examples (F1, F2) will cause compilation failures if used verbatim. These must be corrected before implementation begins.

### Required Fixes (Before Implementation)

| # | Finding | Severity | Effort |
|---|---------|----------|--------|
| F1 | EntityPosition constructor: `0L` -> `"PREY"` | CRITICAL | 5 min |
| F2 | updateEntity API: record -> primitives | CRITICAL | 5 min |
| F3 | LOC estimate: ~1,800 -> ~1,175 | MODERATE | 2 min |
| F4 | Constructor count: ~15 -> ~19 test + mention helper | MODERATE | 5 min |

### Recommended Fixes (Before Implementation)

| # | Finding | Severity | Effort |
|---|---------|----------|--------|
| F5 | Javalin `ctx.status()` -> `ctx.statusCode()` | MINOR | 1 min |
| F6 | Downgrade Risk R3 (false alarm) | MINOR | 1 min |

Total fix effort: ~20 minutes.

After applying the required fixes, this plan is approved for implementation.

---

*Audit completed 2026-02-14 by plan-auditor*
