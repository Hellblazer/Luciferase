# ADR-002: Clock.fixed() nanoTime() Behavior

**Status**: Accepted
**Date**: 2026-02-06
**Author**: Hal Hildebrand
**Related Bead**: Luciferase-d15x (BUG-003)
**Context**: H3 Determinism Epic (simulation/doc/H3_DETERMINISM_EPIC.md)

---

## Context

The `Clock` interface provides a pluggable abstraction for time queries to enable deterministic testing. It defines two methods:

1. `currentTimeMillis()` - Returns absolute timestamp (milliseconds since epoch)
2. `nanoTime()` - Returns high-resolution time for elapsed time measurements

The `Clock.fixed(long fixedTime)` factory method creates a clock that returns constant values. Currently, both methods return constants:

```java
static Clock fixed(long fixedTime) {
    return new Clock() {
        @Override
        public long currentTimeMillis() {
            return fixedTime;
        }

        @Override
        public long nanoTime() {
            return fixedTime * 1_000_000;  // Constant
        }
    };
}
```

### The Problem

Production code uses `nanoTime()` for elapsed time measurements:

```java
long start = clock.nanoTime();
doWork();
long elapsed = clock.nanoTime() - start;  // Always 0 with Clock.fixed()
```

When a fixed clock is injected, elapsed time calculations always return 0, breaking functionality that depends on measuring time intervals.

### Investigation Summary

**Audit of Clock.fixed() Usage** (2026-02-06):
- **Total usages**: 4 test files
- **None call nanoTime()**: All usage is for constant timestamps via `currentTimeMillis()`
- **Use cases**: Message timestamp generation, deterministic test setup
- **No production code** uses `Clock.fixed()`

**Files using Clock.fixed()**:
1. `Phase42ClockIntegrationTest.java` - Tests Clock pattern (no nanoTime calls)
2. `ByzantineTopologyTest.java` - Message timestamps via `currentTimeMillis()`
3. `TopologyProposalTest.java` - Message timestamps via `currentTimeMillis()`
4. `ClockTest.java` - Tests fixed clock behavior (only `currentTimeMillis()`)

**Existing Architecture**:
- **TestClock**: Provides advancing time for both `currentTimeMillis()` and `nanoTime()`
- **Clock.fixed()**: Intended for constant timestamps, not elapsed time measurements
- **Usage pattern**: Tests needing time advancement already use `TestClock`

---

## Decision

**Clock.fixed().nanoTime() will throw UnsupportedOperationException.**

Rationale:

1. **Semantic Clarity**: "Fixed clock" means ALL time methods return constants. Allowing `nanoTime()` to advance would contradict the "fixed" semantic.

2. **Fail-Fast Design**: Exception provides immediate feedback during test development, preventing subtle bugs from constant elapsed time of 0.

3. **Architectural Alignment**: Matches existing usage patterns:
   - `Clock.fixed()` → Constant timestamps (message creation, validation)
   - `TestClock` → Advancing time (elapsed time measurements, animations)

4. **Zero Breaking Changes**: No current code calls `Clock.fixed().nanoTime()` for elapsed time.

5. **Educational Value**: Exception message guides developers to the correct solution (`TestClock`).

---

## Implementation

### Updated Clock.fixed()

```java
/**
 * Returns a clock that always returns the given fixed time.
 * <p>
 * <b>Important</b>: {@link #nanoTime()} throws {@link UnsupportedOperationException}
 * because fixed clocks cannot support elapsed time measurements. Use {@link TestClock}
 * for tests requiring time advancement.
 *
 * @param fixedTime the fixed timestamp in milliseconds
 * @return fixed clock implementation
 * @throws UnsupportedOperationException if {@link #nanoTime()} is called
 */
static Clock fixed(long fixedTime) {
    return new Clock() {
        @Override
        public long currentTimeMillis() {
            return fixedTime;
        }

        @Override
        public long nanoTime() {
            throw new UnsupportedOperationException(
                "Clock.fixed() does not support elapsed time measurements via nanoTime(). " +
                "Fixed clocks return constant values, making elapsed time always 0. " +
                "Use TestClock for tests requiring time advancement. " +
                "Example: var clock = new TestClock(1000L); clock.advance(500); " +
                "See simulation/doc/H3_DETERMINISM_EPIC.md and ADR_002_CLOCK_FIXED_NANOTIME.md"
            );
        }
    };
}
```

### Updated Clock Interface Javadoc

Add to `nanoTime()` method documentation:

```java
/**
 * Returns the current high-resolution time in nanoseconds.
 * <p>
 * This is used for measuring elapsed time intervals (relative time), not absolute timestamps.
 * The returned value is relative to an arbitrary origin and is NOT comparable across
 * JVM instances or to {@link #currentTimeMillis()}.
 * <p>
 * <b>Note</b>: {@link #fixed(long)} does not support this method and will throw
 * {@link UnsupportedOperationException}. Use {@link TestClock} for tests requiring
 * elapsed time measurements.
 * <p>
 * Default implementation delegates to {@link System#nanoTime()}.
 *
 * @return current high-resolution time in nanoseconds
 * @throws UnsupportedOperationException if called on {@link #fixed(long)} clock
 */
default long nanoTime() {
    return System.nanoTime();
}
```

### Test for New Behavior

Add to `ClockTest.java`:

```java
@Test
void testFixedClockNanoTimeThrowsException() {
    var clock = Clock.fixed(1000L);

    var exception = assertThrows(UnsupportedOperationException.class,
        () -> clock.nanoTime(),
        "Fixed clock should throw on nanoTime() call"
    );

    assertTrue(exception.getMessage().contains("TestClock"),
        "Exception should guide to TestClock");
    assertTrue(exception.getMessage().contains("elapsed time"),
        "Exception should explain the limitation");
}
```

---

## Consequences

### Positive

1. **Clear Semantics**: Fixed clock means truly fixed - no hidden advancing behavior
2. **Fail-Fast**: Errors detected immediately during test development, not in production
3. **Guided Learning**: Exception message teaches correct pattern (TestClock)
4. **No Silent Failures**: Prevents elapsed time always returning 0
5. **Architectural Consistency**: Reinforces separation between constant and advancing clocks

### Negative

1. **Breaking Change** (mitigated): Technically breaks API contract, but:
   - No current code affected (verified by audit)
   - Only affects test code (Clock.fixed() not used in production)
   - Error is immediate and clear (not a runtime surprise)

2. **Extra Step for Developers**: Developers who try `Clock.fixed()` expecting elapsed time will get an error and need to switch to `TestClock`
   - **Mitigation**: Clear exception message with code example
   - **Benefit**: Prevents incorrect usage before it becomes a problem

### Neutral

1. **Documentation Burden**: Requires updating:
   - Clock.java javadoc (done above)
   - H3_DETERMINISM_EPIC.md (add guidance section)
   - TEST_FRAMEWORK_GUIDE.md (mention Clock.fixed() limitation)

---

## Alternatives Considered

### Alternative 1: Make nanoTime() Advance Proportionally

Make `Clock.fixed().nanoTime()` return `System.nanoTime() + offset` to support elapsed time.

**Rejected because**:
- Violates "fixed" semantic (currentTimeMillis fixed, nanoTime advances?)
- Adds hidden complexity and state (needs to track creation time)
- Makes fixed clock non-deterministic (depends on when created)
- No current code needs this functionality
- Confusing behavior: which methods are fixed vs advancing?

### Alternative 2: Return fixedTime * 1_000_000 (Status Quo)

Keep current behavior where `nanoTime()` returns a constant.

**Rejected because**:
- Silent failure: elapsed time always 0
- Misleading: appears to work but produces wrong results
- Harder to debug: error not discovered until runtime behavior is analyzed
- No clear guidance to correct solution

### Alternative 3: Create Clock.fixedWithAdvancingNanos()

Add new factory method for fixed millis + advancing nanos.

**Rejected because**:
- API proliferation for edge case with no current usage
- Adds complexity without demonstrated need
- TestClock already provides this functionality
- Confusing naming: "fixed with advancing" is contradictory

---

## Migration Path

**No migration required** - verified by code audit that no current usage calls `Clock.fixed().nanoTime()`.

### For Future Code

**If you need**:
- **Constant timestamps only** → Use `Clock.fixed(long)`
  ```java
  var clock = Clock.fixed(1000L);
  var timestamp = clock.currentTimeMillis();  // Always 1000
  ```

- **Advancing time in tests** → Use `TestClock`
  ```java
  var clock = new TestClock(1000L);  // Start at t=1000ms
  clock.advance(500);                 // Advance to t=1500ms
  long elapsed = clock.nanoTime() - start;  // Works correctly
  ```

- **Production code** → Use `Clock.system()`
  ```java
  private volatile Clock clock = Clock.system();

  public void setClock(Clock clock) {
      this.clock = clock;  // Inject TestClock in tests
  }
  ```

---

## References

- **H3 Determinism Epic**: `simulation/doc/H3_DETERMINISM_EPIC.md`
- **TestClock Implementation**: `simulation/src/main/java/.../integration/TestClock.java`
- **Clock Interface**: `simulation/src/main/java/.../integration/Clock.java`
- **Related Bead**: Luciferase-d15x (P1, 1 hour)
- **Blocked Bead**: Luciferase-ie8x (VolumeAnimator ghost timing fix)

---

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-02-06 | Accepted | Based on code audit showing zero current usage and clear architectural separation between fixed and advancing clocks |

---

## Appendix: Code Audit Details

### Production Code Using nanoTime() for Elapsed Time

These classes would break if injected with `Clock.fixed()` but currently receive `Clock.system()` or `TestClock`:

1. `SimulationTickOrchestrator.java` - Frame time measurement
2. `VolumeAnimator.java` - Animation timing and idle detection
3. `BubbleMigrator.java` - Migration duration tracking
4. `ServerMetrics.java` - Metrics age calculation
5. `GhostStateManager.java` - Operation duration metrics
6. `InstrumentedGhostChannel.java` - Message handling latency
7. `HybridBubbleController.java` - Tick processing time

**All verified**: None of these currently receive `Clock.fixed()` - they use `Clock.system()` in production and `TestClock` in tests requiring time advancement.

### Clock.fixed() Usage (Complete List)

1. **Phase42ClockIntegrationTest.java:58**
   - `Clock fixedClock = Clock.fixed(5000);`
   - Usage: `fixedClock.currentTimeMillis()` only
   - Purpose: Test clock pattern validation

2. **ByzantineTopologyTest.java:46**
   - `clock = Clock.fixed(1000L);`
   - Usage: `clock.currentTimeMillis()` in SplitProposal creation
   - Purpose: Deterministic message timestamps

3. **TopologyProposalTest.java:46**
   - `clock = Clock.fixed(1000L);`
   - Usage: `clock.currentTimeMillis()` in proposal validation
   - Purpose: Deterministic test setup

4. **ClockTest.java:37**
   - `var clock = Clock.fixed(fixedTime);`
   - Usage: Tests `currentTimeMillis()` returns fixed value
   - Purpose: Unit test for Clock.fixed() behavior

**Conclusion**: Zero uses of `Clock.fixed().nanoTime()` for elapsed time measurements.
