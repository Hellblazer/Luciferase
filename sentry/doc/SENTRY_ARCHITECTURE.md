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

```text

1. Insert point, creating initial tetrahedra
2. Check Delaunay property for each new face
3. Flip non-Delaunay faces recursively
4. Continue until all faces satisfy Delaunay property

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

```text

1. Start from random/known tetrahedron
2. Test which face the target point is outside
3. Move to adjacent tetrahedron through that face
4. Repeat until containing tetrahedron found

```text

Expected time: O(n^(1/3)) for uniformly distributed points

### Incremental Construction

```text

1. Create initial "Big Tetrahedron" containing universe
2. For each point to insert:

   a. Locate containing tetrahedron
   b. Split tetrahedron (1→4 flip)
   c. Restore Delaunay property via flips
   d. Propagate flips until convergence

```text

### Vertex Deletion

Uses ear-based algorithm:

```text

1. Find star set (all tetrahedra incident to vertex)
2. Identify "ears" (convex boundary triangles)
3. Remove ears iteratively
4. Fill final hole when only 4 faces remain

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
