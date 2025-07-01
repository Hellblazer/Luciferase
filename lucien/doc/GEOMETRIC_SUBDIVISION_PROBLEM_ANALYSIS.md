# Geometric Tetrahedral Subdivision: Problem Analysis

## Executive Summary

We need a method to subdivide any tetrahedral cell in the Space-Filling Curve (SFC) into 8 child tetrahedra that are:
1. 100% geometrically contained within the parent
2. Correctly indexed on the SFC relative to the parent
3. Properly oriented with correct types
4. Positioned to completely fill the parent's volume

This is fundamentally different from the existing `child()` method, which navigates a grid-based hierarchy.

## The Core Problem

### What We Have vs What We Need

**Current `child()` method:**
- Grid-based navigation in discrete space
- Children positioned at grid coordinates
- May extend outside parent's geometric bounds
- Purpose: SFC traversal and indexing

**Required `geometricSubdivide()` method:**
- True geometric subdivision in continuous space
- Children strictly contained within parent
- Must maintain SFC indexing properties
- Purpose: Spatial operations requiring containment

### The Fundamental Challenge

The challenge is bridging two different representations:

1. **SFC Representation**: Discrete grid positions with types (0-5)
2. **Geometric Reality**: Continuous 3D tetrahedra with exact vertices

We must create children that satisfy BOTH representations simultaneously.

## Detailed Problem Decomposition

### 1. Geometric Constraints

**Parent Tetrahedron:**
- 4 vertices (V0, V1, V2, V3) in 3D space
- 6 edges connecting vertices
- 4 triangular faces
- Bounded volume

**Child Requirements:**
- Each child is also a tetrahedron
- All 4 vertices of each child must be inside or on parent boundary
- No child vertex can be outside parent
- Children must tile parent volume (no gaps, minimal overlap)

### 2. SFC Index Constraints

**TM-Index Properties:**
- Each child has a specific index (0-7) relative to parent
- Index encodes position in hierarchical decomposition
- Must maintain parent-child relationships
- Type (0-5) determines tetrahedron orientation

**Consistency Requirements:**
- `child[i].parent()` must return the original parent
- `parent.child(i)` relationship must be preserved
- TM-index ordering must be maintained

### 3. Bey Refinement Constraints

**True Bey Refinement:**
- Places vertices at edge midpoints (6 new vertices)
- Creates octahedron from edge midpoints
- Splits octahedron along one axis (3 choices)
- Results in 4 corner + 4 octahedral tetrahedra

**Type Consistency:**
- Parent type determines splitting axis
- Child types follow connectivity tables
- Spatial position and orientation relative to parent (not vertex winding order)

## The Key Insight: Dual Representation

### Grid Space vs Geometric Space

**Grid Space (SFC Domain):**
- Discrete coordinates (x, y, z) at level L
- Type determines which tetrahedron in cube
- Anchor point defines position
- Integer arithmetic

**Geometric Space (3D Domain):**
- Continuous coordinates
- Exact vertex positions
- Floating-point arithmetic
- True geometric relationships

### The Mapping Problem

We need a bijection between:
- Grid-space Tet(x, y, z, level, type)
- Geometric tetrahedron with 4 vertices

This mapping must preserve:
1. Containment relationships
2. Parent-child indices
3. Type orientations
4. Volume conservation

## Critical Observations

### 1. Grid Quantization Effects

When we quantize geometric positions to grid:
- Rounding can place children outside parent
- Grid resolution limits precision
- Type assignment affects apparent position

### 2. The Octahedron Splitting Decision

The choice of octahedron splitting axis:
- Must match connectivity table expectations
- Affects which edges become internal
- Determines child 4-7 configurations
- Parent type dependent

### 3. Vertex Ordering Matters

Tetrahedron orientation depends on vertex order:
- Different types have different vertex orderings
- Child vertex order must match expected type
- Incorrect ordering breaks type consistency

## Algorithmic Challenges

### 1. Finding Grid Position from Geometry

Given geometric tetrahedron with 4 vertices:
- Which grid cell contains it?
- What type best represents it?
- How to handle boundary cases?

### 2. Ensuring Containment

After quantization to grid:
- Child grid cell may extend outside parent
- Need to adjust or validate
- May need sub-cell precision

### 3. Maintaining Consistency

The subdivision must ensure:
- All 8 children found by `geometricSubdivide()`
- Match the 8 children accessible via `child(i)`
- But with guaranteed containment

## Solution Requirements

### Functional Requirements

1. **Input**: Any valid Tet object
2. **Output**: Array of 8 Tet objects
3. **Guarantees**:
   - All children 100% inside parent
   - Correct tm-indices
   - Proper types/orientations
   - Volume conservation

### Non-Functional Requirements

1. **Correctness**: Mathematical precision
2. **Robustness**: Handle edge cases
3. **Performance**: Reasonable speed
4. **Verifiability**: Testable properties

## Key Questions to Resolve

### 1. Exact Vertex Computation

- How does `coordinates()` compute vertices?
- Does it account for type correctly?
- Are vertices in correct order?

### 2. Octahedron Splitting Rules

- Which axis for each parent type?
- How encoded in connectivity tables?
- Vertex assignment for children 4-7?

### 3. Grid Fitting Strategy

- How to find best grid position?
- Handle cells spanning grid boundaries?
- Precision vs grid resolution?

### 4. Validation Approach

- How to verify containment?
- Check volume conservation?
- Test tm-index consistency?

## Next Steps

1. Deep dive into `coordinates()` method
2. Analyze connectivity tables for splitting rules
3. Design grid-fitting algorithm
4. Implement with extensive validation
5. Create comprehensive test suite

---

*Analysis completed: July 1, 2025*
*Status: PROBLEM FULLY ANALYZED*