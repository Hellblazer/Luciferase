# Contributing to Luciferase

**Last Updated**: 2026-01-12

Thank you for your interest in contributing to Luciferase. This document provides guidelines and best practices for contributors.

## Table of Contents

- [Code Style](#code-style)
- [Testing Guidelines](#testing-guidelines)
- [Writing Time-Dependent Code](#writing-time-dependent-code)
- [Performance Testing](#performance-testing)
- [Pull Request Process](#pull-request-process)
- [Architecture Decisions](#architecture-decisions)

---

## Code Style

### Java Version

- Target: **Java 25**
- Use modern Java patterns: `var` for local variables, pattern matching, sealed types
- FFM API (Foreign Function & Memory) for native integration

### Naming Conventions

- **Classes**: PascalCase (`OctreeNode`, `EntityManager`)
- **Methods**: camelCase (`insert()`, `kNearestNeighbors()`)
- **Constants**: UPPER_SNAKE_CASE (`MAX_DEPTH`, `DEFAULT_BUCKET_SIZE`)
- **Packages**: lowercase (`com.hellblazer.luciferase.lucien.octree`)

### Logging

Use SLF4J for all logging:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyClass {
    private static final Logger log = LoggerFactory.getLogger(MyClass.class);

    public void doWork() {
        log.debug("Processing entity with id={}", entityId);  // NOT {:.2f}
        log.info("Operation completed in {}ms", duration);
    }
}
```

**Important**: SLF4J uses `{}` placeholders, NOT Python-style format specifiers like `{:.2f}`.

### Concurrency

- **Never use `synchronized`**: Use concurrent collections instead
- **ConcurrentSkipListMap**: For ordered concurrent access
- **CopyOnWriteArrayList**: For thread-safe iteration
- **volatile**: For visibility guarantees without locks
- **ObjectPool**: For lock-free resource management

---

## Testing Guidelines

### Test Organization

- **Unit tests**: Test single class in isolation
- **Integration tests**: Test component interactions
- **Performance tests**: JMH benchmarks with `-Pperformance` profile

### Test Configuration

- **Dynamic ports**: Always use random ports in tests to avoid conflicts
- **GPU tests**: Require `dangerouslyDisableSandbox: true` in IDE configuration
- **Test output**: Suppressed by default, enable with `VERBOSE_TESTS` env var

### Writing Tests

```java
@Test
void testEntityInsertion() {
    // Arrange
    var octree = new Octree<>(idGenerator, 10, (byte)10);
    var position = new Point3f(10, 20, 30);

    // Act
    octree.insert(position, (byte)5, "entity1");

    // Assert
    var entities = octree.getEntitiesInBounds(new AABB(position, 1.0f));
    assertEquals(1, entities.size());
}
```

### Flaky Tests

If you encounter a flaky test:

1. **Run in isolation**: `mvn test -Dtest=ClassName#methodName`
2. **Run repeatedly**: Check for timing/probabilistic issues
3. **If passes alone but fails under load** → timing issue

Use `@DisabledIfEnvironmentVariable` to disable flaky tests in CI while keeping them for local development:

```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: probabilistic test with 30% packet loss"
)
@Test
void testFailureRecovery() {
    // Test runs locally for development
    // Skips in CI to prevent probabilistic failures
}
```

**When to use**:
- Probabilistic tests (packet loss simulation, random failures)
- Timing-sensitive tests (race conditions, timeout windows)
- Resource-constrained tests (tests that fail under CI load)

---

## Writing Time-Dependent Code

**CRITICAL RULE**: Never use `System.currentTimeMillis()` or `System.nanoTime()` directly in production code.

### Standard Clock Injection Pattern

For regular classes (non-record, non-static):

```java
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;

public class MyService {
    private volatile Clock clock = Clock.system();

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void doWork() {
        long now = clock.currentTimeMillis();  // Instead of System.currentTimeMillis()
        long nanos = clock.nanoTime();         // Instead of System.nanoTime()
        // ... business logic
    }
}
```

**Why `volatile`?** Ensures visibility across threads without synchronization overhead.

**Why setter injection?** Avoids constructor changes that break existing callers.

### Record Class Pattern (VonMessageFactory)

For Java records that cannot have mutable fields:

```java
public record MigrationMessage(
    String entityId,
    long timestamp,
    Point3D position
) {
    public MigrationMessage {
        // Compact constructor uses factory-injected time
        timestamp = VonMessageFactory.currentTimeMillis();
    }
}
```

**Alternative (Direct Clock.system())**: MigrationProtocolMessages uses a simpler variation:

```java
public record PrepareRequest(
    UUID migrationId,
    String entityId,
    long timestamp
) {
    // Compact constructor with Clock.system()
    public PrepareRequest(UUID migrationId, String entityId) {
        this(migrationId, entityId, Clock.system().currentTimeMillis());
    }
}
```

**When to use**:
- **Factory pattern**: When multiple record types share timing logic (VonMessage)
- **Direct Clock.system()**: When records are independent (MigrationProtocolMessages)

### Test Clock Usage

```java
@Test
void testTimeBasedBehavior() {
    // Setup deterministic clock
    var testClock = new TestClock();
    testClock.setMillis(1000L);

    var service = new MyService();
    service.setClock(testClock);

    // Execute behavior at T=1000ms
    service.doWork();

    // Advance time by 500ms
    testClock.advance(500);

    // Execute behavior at T=1500ms
    service.doWork();

    // Verify time-dependent behavior
    verify(result).isConsistentWith(1500L);
}
```

### Benefits of Clock Injection

- **Reproducible tests**: Control time progression explicitly
- **Time-travel debugging**: Set arbitrary time points for test scenarios
- **Eliminate flakiness**: Remove timing-dependent test failures
- **Consistent CI results**: Tests behave identically across all environments

**See**: [simulation/doc/H3_DETERMINISM_EPIC.md](simulation/doc/H3_DETERMINISM_EPIC.md) for complete architecture and patterns.

---

## Performance Testing

### Running Benchmarks

```bash
# Run all performance benchmarks
mvn clean test -Pperformance

# Run specific benchmark
mvn test -pl lucien -Dtest=OctreeVsTetreeVsPrismBenchmark

# Full workflow (tests + metrics extraction + doc update)
mvn clean verify -Pperformance-full
```

### Important Performance Notes

- **Disable assertions**: Java assertions add overhead to benchmarks
- **Warm-up iterations**: JMH handles warm-up automatically
- **Multiple runs**: Performance varies by hardware, run multiple times
- **CI environment**: CI hardware differs from development machines

### Benchmark Guidelines

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms4G", "-Xmx4G"})
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
public class MyBenchmark {

    @Benchmark
    public void testInsertion(BenchmarkState state) {
        state.octree.insert(state.position, (byte)5, "entity");
    }
}
```

**See**: [lucien/doc/PERFORMANCE_METRICS_MASTER.md](lucien/doc/PERFORMANCE_METRICS_MASTER.md) for standardized benchmarking process.

---

## Pull Request Process

### Before Submitting

1. **Run full test suite**: `mvn clean install`
2. **Check code style**: Follow project conventions
3. **Add tests**: All new functionality requires tests
4. **Update documentation**: README, CLAUDE.md, module docs as needed
5. **Performance impact**: Run benchmarks if changes affect hot paths

### PR Description Template

```markdown
## Summary
Brief description of changes

## Motivation
Why is this change needed?

## Changes
- List of specific changes
- Breaking changes (if any)

## Testing
- Tests added/modified
- Performance impact (if applicable)

## Documentation
- Documentation updated
- Architecture decisions documented

## Related Issues
- Fixes #123
- Related to #456
```

### Review Process

- At least one approval required
- CI must pass (GitHub Actions)
- Performance benchmarks reviewed for significant changes
- Architecture decisions documented in relevant .md files

---

## Design Review Gate

**IMPORTANT**: Significant architectural changes require design review before implementation.

### When Design Review is Required

- **Phase X Day 1 Work**: Any new phase/epic implementation
- **New Abstractions**: Adding >100 LOC or new design patterns
- **Major Refactoring**: Changes affecting >5 files or >500 LOC
- **API Changes**: Modifications to public APIs or module boundaries

### Process

1. **Before Implementation**: Create design review document from template
2. **Get Approval**: Run plan-auditor agent for architectural validation
3. **Document Decision**: Create ADR if introducing new patterns
4. **Implement**: Reference design review in commits

### Enforcement

- Pre-push hook reminds about design review for large changes
- Code review rejects PRs without design review link for Phase X Day 1 work
- plan-auditor agent accessible via `/plan-audit` command

**See**: [DESIGN_REVIEW_PROCESS.md](DESIGN_REVIEW_PROCESS.md) for complete design review guidelines and templates.

**Example**: See `.pm/reviews/review-20260111-multibubble-decomposition.md` for completed design review of Sprint B B1 (MultiBubbleSimulation god class decomposition).

---

## Architecture Decisions

### When to Document

Document architecture decisions when:
- Introducing new patterns or abstractions
- Making trade-offs between alternatives
- Establishing project-wide conventions
- Implementing complex algorithms

### Where to Document

- **lucien/doc/**: Spatial indexing architecture
- **simulation/doc/**: Distributed simulation architecture
- **render/doc/**: Rendering and ESVO architecture
- **Root docs/**: Project-wide decisions (README.md, CLAUDE.md)

### Decision Documentation Format

```markdown
## Decision: [Title]

**Date**: YYYY-MM-DD
**Status**: [Accepted | Superseded | Deprecated]
**Context**: Background and problem statement
**Decision**: What was decided and why
**Consequences**: Trade-offs and implications
**Alternatives Considered**: Other options and why rejected
```

---

## Key Architectural Principles

### Spatial Indexing

- **Generic design**: `AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>`
- **Type-safe keys**: `MortonKey` for Octree, `TetreeKey` for Tetree
- **Thread-safety**: ConcurrentSkipListMap for concurrent access
- **Multi-entity support**: Multiple entities per spatial location

### Distributed Simulation

- **Deterministic testing**: Clock interface for reproducible scenarios
- **Entity migration**: 2PC protocol for reliable cross-bubble migration
- **Ghost layer**: Boundary synchronization using Lucien's GhostZoneManager
- **Bucket scheduler**: Time-based coordination for distributed animation

### Performance Optimization

- **Memory efficiency**: FFM API for native memory management
- **Lock-free operations**: Optimistic concurrency control
- **Object pooling**: Reduce GC pressure for hot paths
- **SIMD operations**: Vectorized computations where applicable

---

## Questions or Issues?

- **Documentation**: Check [lucien/doc/LUCIEN_ARCHITECTURE.md](lucien/doc/LUCIEN_ARCHITECTURE.md)
- **Simulation**: Check [simulation/doc/CONSOLIDATION_MASTER_OVERVIEW.md](simulation/doc/CONSOLIDATION_MASTER_OVERVIEW.md)
- **Issues**: Open a GitHub issue with detailed description
- **Contact**: hal.hildebrand@gmail.com

---

## License

All contributions are licensed under AGPL v3.0. By submitting a pull request, you agree to license your contribution under the same terms.

---

**Thank you for contributing to Luciferase!**
