# Prism Spatial Index Tests

**Last Updated**: 2025-12-08
**Status**: Current

This directory contains comprehensive tests for the prism spatial index implementation.

## Test Organization

### Functional Tests (Stable)

These tests validate correctness and should be included in CI:

- `LineTest.java` - 1D linear element tests (10 tests)
- `TriangleTest.java` - 2D triangular element tests (11 tests)  
- `PrismKeyTest.java` - Composite spatial key tests (14 tests)
- `PrismGeometryTest.java` - Geometric operations tests (9 tests)

**Total:** 44 functional tests

### Performance Tests (Brittle)

These tests measure performance and can be flaky due to system conditions:

- `PrismPerformanceTest.java` - Performance benchmarks (9 tests)

**Note:** Performance tests are tagged with `@Tag("performance")` and should be run separately from CI to avoid flakiness.

## Running Tests

### Run All Functional Tests

```bash
mvn test -Dtest="*Line*Test,*Triangle*Test,*PrismKey*Test,*PrismGeometry*Test" -DexcludedGroups=performance

```

### Run Only Performance Tests

```bash
mvn test -Dtest=PrismPerformanceTest

```

### Run All Tests (Including Performance)

```bash
mvn test -Dtest="com.hellblazer.luciferase.lucien.prism.*Test"

```

### Exclude Performance Tests from CI

```bash
mvn test -DexcludedGroups=performance

```

## Performance Expectations

Current performance benchmarks (approximate):

- Line SFC computation: ~10ns per call
- Triangle SFC computation: ~10ns per call  
- Prism SFC computation: ~18ns per call
- Parent/child operations: ~53-93ns per call
- Geometric operations: ~12-68ns per call

**Note:** These are informational benchmarks. Performance tests use very loose bounds (10-100x slower than typical) to avoid CI failures due to system load variations.

## Test Philosophy

1. **Functional tests** validate correctness with precise assertions
2. **Performance tests** provide benchmarks with loose sanity checks
3. **Separation** prevents performance flakiness from blocking functional changes
4. **Tagging** allows selective test execution based on context

This approach follows the same pattern used throughout the Luciferase codebase to handle brittle performance tests like `TetreeLocateMethodTest.testPerformanceImprovement`.
