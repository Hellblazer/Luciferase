# Sentry Module Architecture

**Last Updated**: 2026-01-04
**Status**: Current

## Overview

The Sentry module implements a 3D Delaunay tetrahedralization data structure optimized for kinetic point tracking and spatial awareness systems. It provides a spatial publish/subscribe framework for higher-level applications.

## Module Structure

```text

sentry/
├── src/main/java/com/hellblazer/sentry/
│   ├── cast/              # Spatial publish/subscribe framework
│   └── (core classes)     # Base Delaunay tetrahedralization
└── doc/                   # Documentation

```text

## Core Architecture

### 1. Base Delaunay Tetrahedralization

The module implements incremental 3D Delaunay tetrahedralization with dynamic updates:

#### Core Classes

- **Grid**: Immutable base class representing a Delaunay tetrahedralization
  - Maintains a "Big Tetrahedron" defining universe bounds: {-32768, +32768}
  - Uses randomized jump-and-walk algorithm for point location
  - Provides tetrahedra traversal and Voronoi dual extraction

- **MutableGrid**: Dynamic extension supporting insertions and deletions
  - Implements incremental Delaunay algorithm
  - Maintains Delaunay property through bistellar flips
  - Supports complete rebuilding when necessary

- **Tetrahedron**: Single tetrahedron with 4 vertices
  - Maintains neighbor relationships (4 adjacent tetrahedra)
  - Implements geometric predicates (orientation, insphere tests)
  - Supports Voronoi region computation

- **Vertex**: 3D point extending Vector3f
  - Linked list node for vertex management
  - Reference to one adjacent tetrahedron
  - Implements Cursor interface for movement tracking

- **OrientedFace**: Abstract triangular face representation
  - Manages incident/adjacent tetrahedra relationships
  - Implements bistellar flip operations
  - Four concrete implementations for each orientation

### 2. Bistellar Flip Operations

The Delaunay property is maintained through local topological operations:

#### Flip Types

1. **1→4 flip**: Point insertion inside tetrahedron
2. **2→3 flip**: Edge-case handling during insertion
3. **3→2 flip**: Complex edge-case handling
4. **4→1 flip**: Vertex removal (star collapse)

#### Flip Algorithm

```mermaid
flowchart TD
    A["1. Insert point<br/>creating initial tetrahedra"]
    B["2. Check Delaunay property<br/>for each new face"]
    C{"Non-Delaunay<br/>faces?"}
    D["3. Flip non-Delaunay faces<br/>recursively"]
    E["4. Done - all faces satisfy<br/>Delaunay property"]

    A --> B
    B --> C
    C -->|Yes| D
    D --> B
    C -->|No| E
```text

### 3. Spatial Publish/Subscribe Framework

The `cast` package provides spatial awareness abstractions:

#### Components

- **AbstractSpatial**: Base class with 3D location tracking
- **SpatialPublish**: Entities publishing spatial presence
- **SpatialSubscription**: Entities subscribing to spatial regions
- **SphericalPublish/Subscription**: Radius-based variants

#### Design Pattern

Publishers announce their location/influence zones while subscribers monitor regions of interest. The framework enables proximity-based interactions and spatial queries.

## Key Algorithms

### Point Location

Uses a randomized jump-and-walk algorithm:

```mermaid
flowchart TD
    A["1. Start from random/known<br/>tetrahedron"]
    B["2. Test which face the target<br/>point is outside"]
    C{"Containing<br/>tetrahedron<br/>found?"}
    D["3. Move to adjacent tetrahedron<br/>through that face"]
    E["4. Return containing<br/>tetrahedron"]

    A --> B
    B --> C
    C -->|No| D
    D --> B
    C -->|Yes| E

    F["Expected time: O(n^1/3)<br/>for uniformly distributed points"]
    E --> F
```text

### Incremental Construction

```mermaid
flowchart TD
    A["1. Create initial Big Tetrahedron<br/>containing universe"]
    B["2. For each point to insert:"]
    C["a. Locate containing<br/>tetrahedron"]
    D["b. Split tetrahedron<br/>1→4 flip"]
    E["c. Restore Delaunay property<br/>via flips"]
    F{"All flips<br/>converged?"}
    G["d. Propagate flips<br/>until convergence"]
    H["Done - next point"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F -->|No| G
    G --> F
    F -->|Yes| H
```text

### Vertex Deletion

Uses ear-based algorithm:

```mermaid
flowchart TD
    A["1. Find star set<br/>all tetrahedra incident to vertex"]
    B["2. Identify ears<br/>convex boundary triangles"]
    C["3. Remove ears<br/>iteratively"]
    D{"More than<br/>4 faces?"}
    E["4. Fill final hole<br/>when only 4 faces remain"]
    F["Done - vertex deleted"]

    A --> B
    B --> C
    C --> D
    D -->|Yes| C
    D -->|No| E
    E --> F
```text

## Performance Characteristics

### Time Complexity

- Point location: O(n^(1/3)) expected
- Insertion: O(1) expected for random points
- Deletion: O(k) where k is star size
- Nearest neighbor: O(n^(1/3)) expected

### Space Complexity

- Object-oriented: O(n) objects, ~200 bytes per vertex
- Neighbor storage: 4 references per tetrahedron

## Design Decisions

### Float vs Double Precision

- Uses float coordinates for memory efficiency
- Universe bounded to prevent precision issues
- Suitable for spatial indexing, not exact geometry

### No Spatial Index

- Relies on walking from known vertices
- Assumes locality of reference in queries
- Avoids index maintenance overhead

### Incremental vs Batch

- Optimized for dynamic updates
- Supports streaming point insertion
- Local repairs maintain consistency

## Usage Patterns

### Basic Tetrahedralization

```java

MutableGrid grid = new MutableGrid();
Vertex v = grid.add(new Point3f(x, y, z));
grid.delete(v);

```text

### Spatial Awareness

```java

SphericalPublish publisher = new SphericalPublish();
publisher.setLocation(new Point3f(0, 0, 0));
publisher.setRadius(10.0f);

SphericalSubscription subscriber = new SphericalSubscription();
subscriber.setLocation(new Point3f(5, 5, 5));
subscriber.setRadius(5.0f);

```text

## Integration with Luciferase

The Sentry module provides:

1. Foundation for Voronoi-based spatial partitioning
2. Dynamic neighbor queries for moving entities
3. Topology maintenance for spatial relationships
4. Efficient proximity detection via Delaunay properties
5. Kinetic data structure support for motion tracking

## Future Enhancements

1. Parallel construction algorithms
2. GPU acceleration for predicates
3. Hierarchical representations for massive datasets
4. Exact predicate options for robustness
5. Persistence and serialization support
