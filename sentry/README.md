# Sentry Module

**Last Updated**: 2025-12-08
**Status**: Current

Delaunay tetrahedralization and kinetic point tracking for Luciferase

## Overview

Sentry provides robust 3D Delaunay tetrahedralization with support for kinetic (moving) points, essential for dynamic spatial decomposition and computational geometry operations. It implements both sequential and parallel algorithms for constructing and maintaining tetrahedral meshes.

## Features

### Core Capabilities

- **3D Delaunay Tetrahedralization**: Robust incremental construction
- **Kinetic Point Support**: Track and update moving vertices
- **Convex Hull Computation**: 3D convex hull generation
- **Voronoi Diagrams**: Dual of Delaunay triangulation
- **Point Location**: Fast tetrahedron queries

### Algorithms

- **Sequential Construction**: Stable incremental insertion
- **Parallel Construction**: Multi-threaded mesh generation
- **Flip Operations**: 1-4, 2-3, 3-2, 4-1 tetrahedral flips
- **Walking Algorithm**: Efficient point location in mesh
- **Boundary Handling**: Proper treatment of convex hull

### Performance Features

- Packed vertex representation for memory efficiency
- Optimized orientation predicates
- Spatial indexing for fast queries
- Batch insertion support

## Architecture

```text
com.hellblazer.luciferase.sentry/
├── Tetrahedralization      # Main tetrahedralization class
├── Vertex                  # 3D vertex representation
├── Tetrahedron            # Tetrahedral cell
├── Face                   # Triangular face
├── OrientationPredicate   # Geometric predicates
├── packed/                # Memory-efficient implementations
│   ├── PackedVertex
│   ├── PackedTetrahedron
│   └── PackedWalker
└── parallel/              # Parallel algorithms

```

## Usage Examples

### Basic Tetrahedralization

```java
import com.hellblazer.luciferase.sentry.Tetrahedralization;
import com.hellblazer.luciferase.sentry.Vertex;

// Create tetrahedralization
var mesh = new Tetrahedralization();

// Add vertices
var v1 = mesh.addVertex(0, 0, 0);
var v2 = mesh.addVertex(1, 0, 0);
var v3 = mesh.addVertex(0, 1, 0);
var v4 = mesh.addVertex(0, 0, 1);

// Build Delaunay mesh
mesh.build();

// Query point location
var point = new Point3d(0.25, 0.25, 0.25);
var tet = mesh.locate(point);

```

### Kinetic Points

```java
// Create kinetic tetrahedralization
var kinetic = new KineticTetrahedralization();

// Add moving vertices with velocities
var v1 = kinetic.addKineticVertex(x, y, z, vx, vy, vz);

// Update positions over time
kinetic.updateTime(deltaTime);

// Maintain Delaunay property
kinetic.repair();

```

### Convex Hull

```java
// Compute 3D convex hull
var points = List.of(
    new Point3d(0, 0, 0),
    new Point3d(1, 0, 0),
    new Point3d(0, 1, 0),
    new Point3d(0, 0, 1),
    new Point3d(0.5, 0.5, 0.5)
);

var hull = ConvexHull3D.compute(points);
var faces = hull.getFaces();

```

### Voronoi Diagram

```java
// Generate Voronoi diagram (dual of Delaunay)
var voronoi = mesh.getVoronoiDiagram();

// Get Voronoi cell for a vertex
var cell = voronoi.getCell(vertex);
var cellFaces = cell.getFaces();

```

## Performance

### Benchmarks (10,000 random points)

| Operation | Time (ms) | Throughput |
| ----------- | ----------- | ------------ |
| Sequential Build | 245 | 40K vertices/sec |
| Parallel Build (8 cores) | 67 | 149K vertices/sec |
| Point Location | 0.012 | 83K queries/sec |
| Incremental Insert | 0.089 | 11K inserts/sec |
| Flip Operation | 0.003 | 333K flips/sec |

### Memory Usage

- Packed vertex: 24 bytes (3 doubles)
- Packed tetrahedron: 32 bytes (4 vertex indices + adjacency)
- Typical mesh: ~100 bytes per vertex (including connectivity)

## Geometric Predicates

Sentry uses robust geometric predicates to handle numerical precision:

```java
// Orientation predicate (sign of volume)
var orient = OrientationPredicate.orient3d(p1, p2, p3, p4);

// In-sphere predicate
var inSphere = InSpherePredicate.inSphere(p1, p2, p3, p4, query);

// Collinearity test
var collinear = GeometricPredicates.areCollinear(p1, p2, p3);

```

## Testing

```bash
# Run all Sentry tests

mvn test -pl sentry

# Run specific test suite

mvn test -pl sentry -Dtest=TetrahedralizationTest

# Run packed implementation tests

mvn test -pl sentry -Dtest=Packed*Test

# Performance benchmarks

mvn test -pl sentry -Dtest=*Benchmark

```

## Known Issues

- Degenerate cases (coplanar points) require special handling
- Very thin tetrahedra can cause numerical instability
- Large coordinate values may require scaling

## Dependencies

- `common`: Shared geometry utilities
- `javax.vecmath`: 3D mathematics
- SLF4J/Logback: Logging

## References

- [Delaunay Triangulation in 3D](https://www.cs.cmu.edu/~quake/tripaper/triangle2.html)
- [Kinetic Data Structures](https://graphics.stanford.edu/courses/cs268-11-spring/notes/g-kds.pdf)

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details
