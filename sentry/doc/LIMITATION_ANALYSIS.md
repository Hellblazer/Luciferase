# Limitation Analysis

## 1. Fast vs Exact Predicates Trade-off

### Analysis

The current implementation prioritizes speed over correctness by using fast floating-point predicates. This is a common trade-off in computational geometry.

### Why This Matters

- Delaunay triangulation correctness depends on exact orientation and in-sphere tests
- Floating-point roundoff can cause incorrect predicate results
- Cascading errors: one wrong predicate can corrupt the entire triangulation

### Industry Standard Solutions

1. **Shewchuk's Robust Predicates**: Adaptive precision arithmetic that's exact when needed
2. **CGAL's Exact Predicates**: Uses interval arithmetic with exact computation fallback
3. **Simulation of Simplicity**: Handles degeneracies through symbolic perturbation

### Performance Impact

- Exact predicates are 2-10x slower for easy cases
- But prevent catastrophic failures and infinite loops
- Adaptive precision minimizes overhead for well-conditioned inputs

## 2. Vertex-Tetrahedron Bidirectional References

### Analysis

The data structure maintains bidirectional links between vertices and tetrahedra, but these aren't properly maintained during structural modifications.

### Why This Happens

1. **Complexity**: Each vertex can be in many tetrahedra
2. **Updates**: Every tetrahedron change requires updating 4 vertex references
3. **Rebuild**: The rebuild operation recreates tetrahedra without updating vertices

### Design Alternatives

1. **Unidirectional**: Only store tetrahedron→vertex, compute vertex→tetrahedron on demand
2. **Lazy Updates**: Mark vertices dirty and update on access
3. **Index-based**: Use integer indices instead of object references

## 3. Memory Management Architecture

### Analysis

The untrack operation is incomplete because the data structure wasn't designed for dynamic vertex removal.

### Challenges

1. **Reference Counting**: Need to track when a vertex is truly unreferenced
2. **Hole Management**: Removing vertices creates gaps in the data structure
3. **Tetrahedron Updates**: Must update all tetrahedra referencing removed vertices

### Standard Approaches

1. **Garbage Collection**: Mark-and-sweep for unreferenced vertices
2. **Compaction**: Periodically rebuild to remove gaps
3. **Free Lists**: Reuse slots from removed vertices

## 4. Degenerate Case Philosophy

### Analysis

The code allows degenerate tetrahedra to exist rather than preventing or removing them.

### Two Schools of Thought

1. **Prevention**: Use exact predicates and symbolic perturbation to avoid degeneracies
2. **Tolerance**: Allow degeneracies but handle them gracefully in algorithms

### Current Issues

- No consistent epsilon for "near-degenerate" detection
- Degenerate tetrahedra participate in Delaunay checks
- Can cause numerical instability in downstream calculations

## 5. Concurrency Model

### Analysis

The code has no clear concurrency model - it's neither explicitly thread-safe nor explicitly single-threaded.

### Options

1. **Single-threaded**: Document as not thread-safe, simplest approach
2. **Read-Write Locks**: Allow concurrent reads, exclusive writes
3. **Lock-Free**: Use atomic operations and immutable data structures
4. **Thread-Local**: Each thread maintains its own triangulation

### Current State

- No synchronization primitives
- Mutable shared state
- Tests suggest single-threaded usage assumed

## Key Insight: Architectural Coupling

Many limitations stem from tight coupling between components:

- Vertices know about tetrahedra (bidirectional references)
- Tetrahedra directly reference vertices (not indices)
- Geometric predicates embedded throughout (not abstracted)
- No clear separation between topology and geometry

This coupling makes fixes difficult without major refactoring.
