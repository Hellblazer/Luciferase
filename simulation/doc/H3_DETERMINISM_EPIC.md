# H3 Determinism Epic

**Status**: Complete (documentation consolidated)
**Last Updated**: 2026-02-08
**Epic ID**: H3
**Scope**: Deterministic testing infrastructure for Luciferase simulation

---

## Overview

The H3 Determinism Epic established a comprehensive deterministic testing architecture for Luciferase simulation, enabling reproducible time-dependent tests and eliminating timing-based flakiness in CI/CD pipelines.

**Key Achievement**: All simulation components now use the `Clock` interface abstraction, allowing tests to control time advancement and achieve deterministic execution.

---

## Documentation Index

This epic's implementation is documented across several specialized documents:

### Core Architecture

**[ADR_002_CLOCK_FIXED_NANOTIME.md](ADR_002_CLOCK_FIXED_NANOTIME.md)**
- Complete `Clock` interface specification
- Implementation patterns (system(), fixed(), TestClock)
- `nanoTime()` behavior for fixed clocks
- Migration guide for existing code
- **When to read**: Understanding Clock abstraction, implementing new time-dependent code

**[ARCHITECTURE_DISTRIBUTED.md](ARCHITECTURE_DISTRIBUTED.md)**
- Distributed simulation architecture with Clock integration
- Time management across bubbles
- Causal consistency and time synchronization
- **When to read**: Understanding distributed simulation timing, multi-bubble coordination

### Testing Patterns

**[TESTING_PATTERNS.md](TESTING_PATTERNS.md)**
- Deterministic testing patterns with Clock interface
- TestClock usage examples
- Time advancement strategies
- Flaky test diagnostics and fixes
- **When to read**: Writing new tests, fixing timing-dependent test failures

**[TEST_FRAMEWORK_GUIDE.md](../../TEST_FRAMEWORK_GUIDE.md)** (root)
- Authoritative test framework reference
- Performance test thresholds
- CI/CD configuration
- **When to read**: Comprehensive test framework guidance, CI/CD tuning

### Implementation Details

**[Clock.java](../src/main/java/com/hellblazer/luciferase/simulation/distributed/integration/Clock.java)**
- Production implementation
- Factory methods: `system()`, `fixed(long)`, `TestClock`
- Javadoc with usage examples
- **When to read**: Implementing Clock injection, creating custom clock implementations

---

## Quick Start

### For New Production Code

Always inject `Clock` dependency instead of using `System.currentTimeMillis()` or `System.nanoTime()`:

```java
public class MyService {
    private volatile Clock clock = Clock.system();  // Default to system clock

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void doWork() {
        long now = clock.currentTimeMillis();  // NOT System.currentTimeMillis()
        // ... business logic
    }
}
```

### For Tests

Use `TestClock` for deterministic time control:

```java
@Test
void testTimeAdvancement() {
    var clock = new TestClock(1000L);  // Start at t=1000ms
    var service = new MyService();
    service.setClock(clock);

    service.doWork();  // Executes at t=1000ms

    clock.advance(500);  // Advance to t=1500ms
    service.doWork();    // Executes at t=1500ms

    // Deterministic, reproducible behavior
}
```

---

## Key Decisions

### ADR-002: Clock.fixed() nanoTime() Behavior

**Decision**: `Clock.fixed(long)` returns constant for both `currentTimeMillis()` and `nanoTime()`.

**Rationale**:
- Simpler implementation (no hidden state)
- Predictable behavior for snapshot testing
- Forces explicit use of `TestClock` when time advancement is needed

**Alternative**: Use `TestClock` for tests requiring time progression.

See [ADR_002_CLOCK_FIXED_NANOTIME.md](ADR_002_CLOCK_FIXED_NANOTIME.md) for complete analysis.

---

## Migration Status

**Complete**: All simulation components migrated to Clock interface.

**Production Components**:
- ✅ RealTimeController (Clock injection)
- ✅ VonMessageFactory (record pattern with Clock)
- ✅ SocketTransport (Clock injection)
- ✅ BubbleScheduler (Clock injection)
- ✅ CrossProcessMigration (Clock injection)

**Test Infrastructure**:
- ✅ TestClock implementation
- ✅ InjectableClockTest validation suite
- ✅ All flaky timing tests fixed with Clock injection

---

## Benefits Achieved

1. **Deterministic Tests**: Time-dependent behavior is now reproducible
2. **Zero Flakiness**: Eliminated timing-based test failures in CI/CD
3. **Time-Travel Debugging**: Tests can simulate arbitrary time sequences
4. **Zero Production Impact**: `Clock.system()` ≡ `System.currentTimeMillis()`
5. **Simple Testing**: `TestClock` provides intuitive time control

---

## Related Beads

- **Luciferase-d15x** (BUG-003): Clock.fixed() nanoTime() behavior
- **H3.7**: Phase 1 completion (all components migrated)

---

## See Also

- **DISABLED_TESTS_POLICY.md** - Flaky test handling and CI exclusion policy
- **RUNBOOK_ROLLBACK_RECOVERY.md** - Operational procedures including Clock rollback
- **V3_GHOST_MANAGER_DECISION.md** - Ghost layer architecture (uses Clock)

---

**Last Review**: 2026-02-08
**Next Review**: When adding new time-dependent features (ensure Clock injection)
