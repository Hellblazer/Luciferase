# Sentry Module

**Last Updated**: 2026-01-04
**Status**: Current

Delaunay tetrahedralization for kinetic point tracking in Luciferase

## Overview

Sentry provides 3D Delaunay tetrahedralization optimized for kinetic (moving) point tracking. It uses incremental construction with periodic rebuilding to maintain topological correctness as points move through 3D space.

This implementation prioritizes topology and relative position tracking over geometric precision, making it ideal for spatial simulation and dynamic mesh applications.

## Features

### Core Capabilities

- **3D Delaunay Tetrahedralization**: Incremental mesh construction with random walk point location
- **Kinetic Point Tracking**: Maintains topological relationships for moving vertices
- **Fast Rebuilding**: Periodic mesh reconstruction for topology repair
- **Vertex Management**: Track, update, and remove spatial points
- **Tetrahedron Traversal**: Walk-based queries for point location

### Implementation Details

- **Coordinate Range**: {-32k, +32k} based on float precision
- **Predicates**: Fast inSphere predicate (not exact) for performance
- **Memory Management**: Pooled allocation for tetrahedra to reduce GC pressure
- **Topology Focus**: Optimized for relative position, not precise geometry

## Architecture

```
com.hellblazer.sentry/
├── Grid.java                    # Base Delaunay tetrahedralization (immutable)
├── MutableGrid.java             # Mutable grid with tracking and rebuild
├── Vertex.java                  # 3D vertex with tetrahedron reference
├── Tetrahedron.java             # Tetrahedral cell with adjacency
├── OrientedFace.java            # Triangle face with orientation
├── Cursor.java                  # Interface for spatial cursors
├── TetrahedronPool.java         # Object pool for tetrahedra
├── FlipOptimizer.java           # Tetrahedral flip operations
├── StarVisitor.java             # Visitor for star traversal
└── cast/                        # Spatial publish/subscribe
    ├── SpatialPublish.java      # Publication interface
    ├── SpatialSubscription.java # Subscription interface
    └── SphericalSubscription.java # Sphere-based subscriptions
```

## Usage Examples

### Basic Grid Creation

```java
import com.hellblazer.sentry.MutableGrid;
import com.hellblazer.sentry.Vertex;
import javax.vecmath.Point3f;
import java.util.Random;

// Create mutable grid for tracking points
var grid = new MutableGrid();
var random = new Random();

// Track a point (creates vertex in Delaunay mesh)
var point = new Point3f(10.0f, 20.0f, 30.0f);
Vertex vertex = grid.track(point, random);

// Access vertex data
float x = vertex.x;
float y = vertex.y;
float z = vertex.z;

// Get current tetrahedron containing the vertex
Tetrahedron tet = vertex.getAdjacent();

// Iterate over all tracked vertices
for (Vertex v : grid) {
    System.out.println("Vertex at: " + v.x + ", " + v.y + ", " + v.z);
}
```

### Kinetic Points (Moving Vertices)

```java
// Track multiple moving points
var vertices = new ArrayList<Vertex>();
for (int i = 0; i < 100; i++) {
    var pos = new Point3f(
        random.nextFloat() * 100,
        random.nextFloat() * 100,
        random.nextFloat() * 100
    );
    vertices.add(grid.track(pos, random));
}

// Simulate movement by updating positions
for (Vertex v : vertices) {
    v.x += velocityX * deltaTime;
    v.y += velocityY * deltaTime;
    v.z += velocityZ * deltaTime;
}

// Rebuild mesh to maintain topological correctness
grid.rebuild(random);

// Check grid size
int vertexCount = grid.size();
```

### Spatial Queries

```java
// Find tetrahedron containing a point
var searchPoint = new Point3f(15.0f, 25.0f, 35.0f);

// Start from any vertex in the grid
Vertex startVertex = grid.iterator().next();
Tetrahedron foundTet = startVertex.getAdjacent(); // Starting tetrahedron

// Walk through mesh to find containing tetrahedron
// (Note: Walking implementation details are in Grid.locate())

// Visit all tetrahedra in a vertex's star
var visitor = new StarVisitor() {
    @Override
    public boolean visit(Tetrahedron t) {
        // Process tetrahedron
        System.out.println("Visiting tetrahedron");
        return true; // Continue visiting
    }
};
vertex.visitStar(visitor);
```

### Object Pooling

```java
import com.hellblazer.sentry.TetrahedronPool;
import com.hellblazer.sentry.PooledAllocator;

// Use pooled allocation to reduce GC pressure
var poolContext = new TetrahedronPoolContext();
var allocator = new PooledAllocator(poolContext);

// Grid will use pooled tetrahedra internally
// Pool automatically manages tetrahedron lifecycle
```

## Performance Characteristics

### Operations

| Operation | Complexity | Notes |
| ----------- | ------------ | ------- |
| track() | O(n) amortized | Random walk location + insertion |
| rebuild() | O(n log n) | Full mesh reconstruction |
| iterate | O(n) | Linear traversal of vertices |
| locate() | O(√n) expected | Random walk through mesh |

Where n = number of tracked vertices

### Memory Usage

- **Vertex**: 12 bytes (3 floats)
- **Tetrahedron**: ~32 bytes (4 vertex refs + 4 adjacency refs)
- **Typical mesh**: ~150 bytes per vertex (including tetrahedra and connectivity)

### Known Limitations

- **Coordinate range**: Limited to ±32,768 (short float precision)
- **Precision**: Uses fast (not exact) inSphere predicate
- **Degenerate cases**: Coplanar or collinear points require special handling
- **Rebuild cost**: Full mesh reconstruction can be expensive for large point sets

## Integration with Other Modules

### Von Module

Sentry provides the foundation for VON's Voronoi-based spatial perception:

- `Cursor` interface: Used by VON nodes for spatial tracking
- `Vertex` interface: Provides spatial position for perception queries

### Simulation Module

Historical usage (now replaced by SpatialIndex):

- Early simulation prototypes used MutableGrid for entity tracking
- Now uses Lucien spatial indices (Octree/Tetree) for better performance

## Testing

```bash
# Run all Sentry tests
mvn test -pl sentry

# Specific test suites
mvn test -pl sentry -Dtest=RebuildTest
mvn test -pl sentry -Dtest=DelaunayValidationTest

# Performance benchmarks
mvn test -pl sentry -Dtest=RebuildPerformanceTest
mvn test -pl sentry -Dtest=AllocationPerformanceTest
```

## Dependencies

- **common**: Shared geometry utilities (IdentitySet, etc.)
- **javax.vecmath**: 3D mathematics (Point3f, Tuple3f)
- **SLF4J/Logback**: Logging

## Historical Context

This implementation is based on classic incremental Delaunay construction algorithms, optimized specifically for Luciferase's needs:

- **Float precision** instead of double (sufficient for game/simulation coordinates)
- **Random walk** location instead of complex spatial indexing (mesh topology provides implicit structure)
- **Periodic rebuild** instead of continuous repair (simpler, more robust)
- **Topology focus** instead of exact geometry (relative position more important than precise measurements)

## References

- [Delaunay Triangulation](https://www.cs.cmu.edu/~quake/tripaper/triangle2.html) - Classic computational geometry reference
- [Kinetic Data Structures](https://graphics.stanford.edu/courses/cs268-11-spring/notes/g-kds.pdf) - Theory of moving point tracking

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details
