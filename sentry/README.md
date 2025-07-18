# Sentry Module

The Sentry module implements a 3D Delaunay tetrahedralization data structure optimized for kinetic point tracking and spatial awareness systems within the Luciferase framework. It provides both object-oriented and memory-efficient packed implementations.

## Features

- **Dynamic Delaunay Tetrahedralization**: Incremental construction with point insertion and deletion
- **Kinetic Point Tracking**: Efficient updates for moving points in 3D space
- **Voronoi Dual**: Extract Voronoi diagrams for spatial partitioning
- **Memory-Efficient Packed Implementation**: ~60 bytes per vertex with primitive arrays
- **Spatial Publish/Subscribe Framework**: High-level abstractions for spatial awareness

## Quick Start

### Basic Tetrahedralization

```java
import com.hellblazer.sentry.MutableGrid;
import javax.vecmath.Point3f;

// Create a mutable Delaunay tetrahedralization
MutableGrid grid = new MutableGrid();

// Add points
Vertex v1 = grid.add(new Point3f(0, 0, 0));
Vertex v2 = grid.add(new Point3f(1, 0, 0));
Vertex v3 = grid.add(new Point3f(0, 1, 0));

// Delete a point
grid.delete(v1);

// Find containing tetrahedron
Tetrahedron tet = grid.locate(new Point3f(0.5f, 0.5f, 0.5f));
```

### Packed Implementation

```java
import com.hellblazer.sentry.packed.PackedGrid;

// Create memory-efficient packed grid
PackedGrid packed = new PackedGrid(1000);

// Add points (returns vertex index)
int vertex = packed.add(0.5f, 0.5f, 0.5f);

// Stream through all tetrahedra
packed.tetrahedronStream().forEach(tet -> {
    // Process tetrahedron
});
```

### Spatial Awareness

```java
import com.hellblazer.sentry.cast.*;

// Create spatial publisher
SphericalPublish entity = new SphericalPublish();
entity.setLocation(new Point3f(0, 0, 0));
entity.setRadius(10.0f);

// Create spatial subscriber
SphericalSubscription sensor = new SphericalSubscription();
sensor.setLocation(new Point3f(5, 5, 5));
sensor.setRadius(5.0f);
```

## Documentation

Detailed documentation is available in the `sentry/doc/` directory:

- [**Sentry Architecture**](doc/SENTRY_ARCHITECTURE.md) - Complete module architecture and design
- [**Delaunay Algorithms**](doc/DELAUNAY_ALGORITHMS.md) - Detailed algorithm explanations and implementations
- [**Packed Implementation Guide**](doc/PACKED_IMPLEMENTATION_GUIDE.md) - Memory-efficient implementation details

## Module Structure

```
sentry/
├── src/main/java/com/hellblazer/sentry/
│   ├── cast/              # Spatial publish/subscribe framework
│   │   ├── AbstractSpatial.java
│   │   ├── SpatialPublish.java
│   │   ├── SpatialSubscription.java
│   │   ├── SphericalPublish.java
│   │   └── SphericalSubscription.java
│   ├── packed/            # Memory-efficient implementation
│   │   ├── PackedGrid.java
│   │   └── OrientedFace.java
│   └── (core classes)     # Base Delaunay implementation
│       ├── Grid.java
│       ├── MutableGrid.java
│       ├── Tetrahedron.java
│       ├── Vertex.java
│       ├── OrientedFace.java
│       └── V.java
└── doc/                   # Documentation
```

## Key Concepts

### Delaunay Property
The tetrahedralization maintains the Delaunay property where no vertex lies inside the circumsphere of any tetrahedron. This ensures optimal triangulation for interpolation and spatial queries.

### Bistellar Flips
The module uses local topological operations to maintain the Delaunay property:
- **1→4 flip**: Point insertion inside tetrahedron
- **2→3 flip**: Edge cases during insertion
- **3→2 flip**: Complex edge cases
- **4→1 flip**: Vertex removal (star collapse)

### Universe Bounds
The implementation uses a bounded universe {-32768, +32768} with float precision, suitable for spatial indexing applications where exact geometric computation is less critical than performance.

## Performance Characteristics

### Time Complexity
- Point location: O(n^(1/3)) expected
- Insertion: O(1) expected for random points
- Deletion: O(k) where k is star size
- Nearest neighbor: O(n^(1/3)) expected

### Memory Usage
- Object-oriented: ~200 bytes per vertex
- Packed implementation: ~60 bytes per vertex
- No garbage collection pressure in packed mode

## Design Decisions

1. **Float Precision**: Optimized for memory and speed over exactness
2. **Incremental Construction**: Supports dynamic updates vs batch building
3. **No Spatial Index**: Uses walking algorithms assuming locality of reference
4. **Bounded Universe**: Prevents precision issues with large coordinates

## Integration with Luciferase

The Sentry module provides foundational spatial data structures for:
- Voronoi-based spatial partitioning
- Dynamic neighbor queries for moving entities
- Topology maintenance for spatial relationships
- Efficient proximity detection
- Kinetic data structure support

## Examples

### Extract Voronoi Cell

```java
// Get Voronoi cell for a vertex
Set<Point3f> voronoiVertices = vertex.getVoronoiVertices();

// Get neighboring vertices
Set<Vertex> neighbors = vertex.getNeighbors();
```

### Kinetic Updates

```java
// Move a vertex efficiently
Vertex v = grid.add(new Point3f(0, 0, 0));
v.moveBy(new Vector3f(1, 0, 0));  // Incremental movement
```

### Walk Through Tetrahedra

```java
// Visit all tetrahedra incident to a vertex
vertex.visit((center, v0, v1, v2) -> {
    // Process tetrahedron with center vertex and three others
    return true;  // Continue visiting
});
```

## Testing

Run the test suite:
```bash
mvn test
```

Key test classes:
- `TetrahedralizationTest`: Core algorithm validation
- `PackedGridTest`: Packed implementation tests
- `MutableGridTest`: Dynamic operations
- `OrientedFaceTest`: Bistellar flip operations

## Future Enhancements

- Parallel construction algorithms
- GPU acceleration for predicates
- Hierarchical representations
- Exact arithmetic options
- Persistence support