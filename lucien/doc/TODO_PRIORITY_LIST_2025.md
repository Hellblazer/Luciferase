# TODO Priority List - June 2025

This document tracks prioritized TODO items found in the Luciferase lucien module codebase.

## 🔴 High Priority (Functional Correctness)

1. **Fix CollisionSystem hardcoded level 10**
   - **File**: `CollisionSystem.java` (2 occurrences)
   - **Issue**: Currently using hardcoded `(byte) 10` for entity updates
   - **Impact**: Could cause incorrect spatial placement of entities
   - **Fix**: Use actual entity levels from the spatial index

2. **Implement proper SAT test in BoxShape**
   - **File**: `BoxShape.java`
   - **Issue**: Collision detection incomplete without Separating Axis Theorem
   - **Impact**: Inaccurate physics and collision detection
   - **Fix**: Implement full SAT algorithm for box-box collisions

3. **Fix spatial range query optimization for Tetree**
   - **File**: `AbstractSpatialIndex.java`
   - **Issue**: Tetree spatial queries may not be optimized
   - **Impact**: Poor performance for spatial range queries
   - **Fix**: Optimize the spatial range query implementation for Tetree

## 🟡 Medium Priority (Important but not blocking)

4. **Implement proper 128-bit arithmetic for gap calculation**
   - **File**: `Tetree.java`
   - **Issue**: Gap calculation needs proper 128-bit math
   - **Impact**: Potential accuracy issues in spatial operations

5. **Fix round-trip test in TetreeKeyTest**
   - **File**: `TetreeKeyTest.java`
   - **Issue**: Tests failing due to coordinate system understanding
   - **Impact**: Test validation incomplete

6. **Check type differences at level in TetreeBits**
   - **File**: `TetreeBits.java`
   - **Issue**: Missing t8code parity check
   - **Impact**: Could affect tetrahedral operations accuracy

## 🟢 Low Priority (Nice to have)

7. **Implement bottom-up construction in StackBasedTreeBuilder**
   - **File**: `StackBasedTreeBuilder.java`
   - **Issue**: Alternative construction method not implemented

8. **Implement hybrid construction in StackBasedTreeBuilder**
   - **File**: `StackBasedTreeBuilder.java`
   - **Issue**: Hybrid construction variant not implemented

9. **Implement frustumCullVisible method**
   - **File**: `SpatialIndexQueryPerformanceTest.java`
   - **Issue**: Method needed for performance tests

## Notes

- The "root tet type hardcoded to 0" TODO was excluded as requested
- Priority based on impact to core functionality and correctness
- High priority items affect spatial operations and collision detection accuracy