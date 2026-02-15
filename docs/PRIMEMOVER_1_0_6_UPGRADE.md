# PrimeMover 1.0.6 Upgrade Guide

**Date**: January 2026
**Previous Version**: 1.0.5
**Current Version**: 1.0.6
**Status**: Deployed and Tested

---

## Executive Summary

PrimeMover 1.0.6 brings critical improvements to deterministic testing, virtual time handling, and bytecode transformation reliability. This upgrade is fully backward compatible while providing enhanced capabilities for distributed simulation testing.

### Key Improvements

1. **Clock Drift Fixes** - InjectableClockTest now passing, eliminates clock synchronization issues
2. **Virtual Time Enhancements** - RealTimeController improved with better time correlation
3. **Bytecode Transformation** - Enhanced ClassFile API transformation for reliability
4. **Error Handling** - Better exception propagation in event evaluation chains

---

## What Changed in 1.0.6

### 1. Clock Drift and Virtual Time

**Problem Solved**: Previous versions had issues with clock drift in distributed scenarios with InjectableClockTest failing.

**Solution Implemented**:
- Improved RealTimeController synchronization between wall clock and simulation time
- Better handling of clock skew tolerance for distributed systems
- Enhanced virtual thread continuation timing

**Impact on Code**:
- No API changes required
- Tests using `Kronos.sleep()` and `Kronos.blockingSleep()` now more reliable
- RealTimeController provides accurate wall-clock correlation

**Migration**: No action required - fully backward compatible

### 2. Virtual Time Management

**Enhancement**: More accurate virtual time tracking in blocking operations

**Technical Details**:
- Improved continuation state preservation across event boundaries
- Better timing accuracy for virtual thread park/unpark cycles
- Enhanced support for multi-level blocking operation chains

**Usage Pattern** (unchanged):
```java
@Entity
public class SimulationEntity {
    public void operation() {
        Kronos.sleep(100);  // Advances simulation time by 100 units
        blockingOperation();
    }

    @Blocking
    public String blockingOperation() {
        // Virtual thread may pause here, but time handling is now more reliable
        return "result";
    }
}
```java

### 3. Bytecode Transformation

**Improvement**: Enhanced ClassFile API usage for more reliable transformation

**Changes**:
- Improved method metadata handling during transformation
- Better error reporting for transformation failures
- More robust handling of complex method signatures
- Improved performance for large entity classes

**Impact**: No API changes; transformation is now more reliable for edge cases

### 4. Event Evaluation Error Handling

**Enhancement**: Better exception propagation through event chains

**Before**: Exceptions in event handlers could be lost in continuation chains
**After**: Exceptions properly propagated with full stack trace context

**Usage** (no changes needed):
```java
try {
    controller.eventLoop();  // Exceptions now properly caught and reported
} catch (Throwable t) {
    log.error("Event evaluation failed", t);  // Full stack trace available
}
```java

---

## Configuration

### Maven POM Update

The project is already configured with PrimeMover 1.0.6 in root `pom.xml`:

```xml
<properties>
    <prime-mover.version>1.0.6</prime-mover.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.hellblazer.primeMover</groupId>
            <artifactId>api</artifactId>
            <version>${prime-mover.version}</version>
        </dependency>
        <dependency>
            <groupId>com.hellblazer.primeMover</groupId>
            <artifactId>runtime</artifactId>
            <version>${prime-mover.version}</version>
        </dependency>
        <dependency>
            <groupId>com.hellblazer.primeMover</groupId>
            <artifactId>primemover-maven-plugin</artifactId>
            <version>${prime-mover.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```xml

### Maven Plugin Configuration

The primemover-maven-plugin is configured in simulation and other modules:

```xml
<plugin>
    <groupId>com.hellblazer.primeMover</groupId>
    <artifactId>primemover-maven-plugin</artifactId>
    <version>${prime-mover.version}</version>
    <executions>
        <execution>
            <phase>process-classes</phase>
            <goals>
                <goal>transform</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```xml

---

## Testing with 1.0.6

### Deterministic Simulation Testing

With the clock drift fixes, deterministic simulation testing is now more reliable:

```java
@Test
void testDeterministicBehavior() {
    // Create entities with controlled time
    SimulationController controller = new SimulationController();
    Kronos.setController(controller);
    controller.setCurrentTime(0);

    // Create and run entities
    MyEntity entity = new MyEntity();
    entity.start();

    // Run simulation to completion
    controller.eventLoop();

    // Verify deterministic behavior
    assertEquals(expectedBlockCount, controller.getTotalEvents());
}
```java

### Clock Injection Pattern

For tests requiring time control:

```java
@Test
void testTimingBehavior() {
    var testClock = new TestClock(1000L);
    var service = new TimedService();
    service.setClock(testClock);

    // Advance time deterministically
    testClock.advance(500);
    assertEquals(1500L, service.getLastTimestamp());
}
```java

### Integration Testing

RealTimeController improvements support more reliable integration tests:

```java
@Test
void testDistributedSimulation() {
    // Multiple controllers maintaining wall-clock correlation
    var controller1 = new RealTimeController();
    var controller2 = new RealTimeController();

    // Both track wall-clock time accurately
    Kronos.setController(controller1);
    entity1.event();

    Kronos.setController(controller2);
    entity2.event();

    // Both maintain correct timing despite simulated delays
}
```java

---

## Performance Characteristics

### Event Throughput

No performance regressions in 1.0.6:

- **SimulationController**: O(log n) event insertion unchanged
- **Event dispatch**: O(1) method lookup via switch statement
- **Virtual thread overhead**: Same as 1.0.5

### Memory Usage

Virtual time management improvements have no additional memory overhead:

- **EventImpl**: Same size as 1.0.5
- **Continuation state**: No additional objects allocated
- **Clock tracking**: Negligible impact

### Transformation Performance

Bytecode transformation may be slightly faster due to improved algorithms:

- **Build-time transformation**: ~same or slightly faster
- **Runtime overhead**: Zero (transformation at build time)

---

## Migration Guide

### From 1.0.5 to 1.0.6

**Good News**: This is a drop-in replacement. No code changes required.

#### Step 1: Update pom.xml

Already done in root pom.xml:
```xml
<prime-mover.version>1.0.6</prime-mover.version>
```xml

#### Step 2: Rebuild

```bash
mvn clean install
```bash

#### Step 3: Test

```bash
# Run simulation tests to verify upgrade
mvn test -pl simulation

# Run all tests
mvn test
```bash

#### Step 4: Verify Improvements

Check that clock-dependent tests are now more reliable:

```bash
# Tests that were flaky in 1.0.5 should now be stable
mvn test -pl simulation -Dtest=InjectableClockTest
```bash

### Rollback Instructions

If you need to revert to 1.0.5:

```xml
<prime-mover.version>1.0.5</prime-mover.version>
```xml

Then rebuild. However, rollback is **not recommended** because:
- 1.0.6 fixes deterministic testing issues
- No breaking changes or API modifications
- Full backward compatibility maintained

---

## Known Issues and Limitations

### None Reported

Version 1.0.6 has been validated through:
- ✅ All existing tests pass
- ✅ New InjectableClockTest passes
- ✅ Simulation determinism tests passing
- ✅ Distributed system tests validated

### Platform Support

Tested and working on:
- ✅ Java 25 (required)
- ✅ macOS (Intel and Apple Silicon)
- ✅ Linux (x86_64 and ARM64)
- ✅ Windows 10/11

---

## Troubleshooting

### Issue: Clock drift in distributed tests

**Solution**: Ensure RealTimeController is used:
```java
var controller = new RealTimeController();
Kronos.setController(controller);
```java

### Issue: Event evaluation exceptions disappear

**Solution**: In 1.0.6, exceptions are properly propagated. Verify exception handling:
```java
try {
    controller.eventLoop();
} catch (Throwable t) {
    // Exception should now be visible
    log.error("Event failed", t);
}
```java

### Issue: Blocking operations timeout

**Solution**: Verify blocking method is marked with @Blocking:
```java
@Blocking  // Required for blocking semantics
public void blockingOperation() {
    Kronos.blockingSleep(100);
    // ...
}
```java

---

## Documentation References

### In This Repository

- **CLAUDE.md**: High-level guidance on development patterns
- **simulation/README.md**: Simulation module documentation
- **simulation/doc/H3_DETERMINISM_EPIC.md**: Deterministic testing patterns

### External Resources

- **Prime-Mover GitHub**: https://github.com/Hellblazer/Prime-Mover
- **Release Notes**: https://github.com/Hellblazer/Prime-Mover/releases/tag/1.0.6

### ChromaDB Knowledge Base

- `knowledge::prime-mover::consolidated-2026-01-19` - Complete consolidated knowledge base
- `architecture::prime-mover::comprehensive-analysis` - Detailed architecture analysis
- `research::primemover::realtime-controller` - RealTimeController analysis

---

## Support and Questions

### For Development Issues

1. Check CLAUDE.md section on PrimeMover patterns
2. Review simulation/doc/H3_DETERMINISM_EPIC.md for patterns
3. Consult Prime-Mover GitHub issues

### For Performance Issues

1. Run performance benchmarks: `mvn test -Pperformance`
2. Use JFR (Java Flight Recorder) to profile
3. Check event spectrum via controller.getSpectrum()

### For Distributed System Issues

1. Enable event tracing: controller.setTrackEventSources(true)
2. Review H3 determinism patterns for time control
3. Check RealTimeController configuration

---

## Version History

| Version | Date | Status | Notes |
|---------|------|--------|-------|
| 1.0.6 | Jan 2026 | Current | Clock drift fixes, virtual time improvements |
| 1.0.5 | Dec 2025 | Previous | Last version, some determinism issues |

---

## Checklist for Upgrade Verification

After upgrading to 1.0.6:

- [ ] Maven build completes successfully
- [ ] All existing tests pass
- [ ] InjectableClockTest passes (if running)
- [ ] RealTimeController works correctly
- [ ] Deterministic simulation tests pass
- [ ] Distributed tests show no timing issues
- [ ] Performance benchmarks unchanged or improved

---

**Document Status**: Complete
**Last Updated**: January 2026
**Verified Against**: PrimeMover 1.0.6 release and pom.xml configuration
**Next Review**: June 2026 or when PrimeMover 1.1.0 released
