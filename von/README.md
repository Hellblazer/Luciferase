# Von Module

**Last Updated**: 2026-01-04
**Status**: Current

Voronoi-based area-of-interest perception framework for spatial simulation

## Overview

Von (Voronoi Overlay Network) provides area-of-interest (AOI) management for spatial simulations. It implements a sphere-of-interaction pattern using k-nearest neighbor queries to efficiently manage which entities perceive each other in a spatial environment.

This module is based on the Thoth Interest Management and Load Balancing Framework and provides the foundation for distributed spatial perception in the simulation module.

## Features

### Spatial Perception

- **Sphere of Interaction (SoI)**: Area-of-interest management using spatial proximity
- **k-NN Based Discovery**: Find nearby entities using efficient spatial queries
- **Node Management**: Insert, update, remove, and query spatial participants
- **Overlap Detection**: Check if nodes' areas of interest overlap
- **Enclosing Neighbors**: Find all neighbors within a node's perception radius

### Implementation Strategies

- **SpatialSoI**: k-NN based implementation using Octree spatial index
- **GridSoI**: Grid-based partitioning for uniform distributions
- **Perceptron**: Abstract base class for perception implementations

## Architecture

```text
com.hellblazer.luciferase.lucien.von/
├── Node.java                    # Node interface (participant in overlay)
├── SphereOfInteraction.java     # SoI interface for AOI management
├── Perceiving.java              # Perception callback interface
└── impl/
    ├── AbstractNode.java        # Base implementation for nodes
    ├── Perceptron.java          # Abstract perception implementation
    ├── SpatialSoI.java          # k-NN based SoI using spatial index
    └── GridSoI.java             # Grid-based SoI implementation
```

## Core Concepts

### Node

A `Node` represents a participant in the spatial overlay network. It has:

- A position in 3D space
- An area-of-interest (AOI) radius defining perception range
- Perception callbacks for detecting nearby nodes

### Sphere of Interaction (SoI)

The `SphereOfInteraction` manages spatial relationships between nodes:

- Tracks which nodes are within perception range of each other
- Provides efficient queries for finding nearby nodes
- Maintains spatial index for fast updates and lookups

### Perceiving

The `Perceiving` interface provides callbacks for perception events:

- Node enters perception range
- Node moves within perception range
- Node leaves perception range

## Usage Example

### Creating a Spatial SoI

```java
import com.hellblazer.luciferase.lucien.von.impl.SpatialSoI;
import com.hellblazer.luciferase.lucien.von.Node;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;

// Create spatial index
var octree = new Octree<LongEntityID, Node>(
    new SequentialLongIDGenerator(),
    16,      // maxEntitiesPerNode
    (byte)21 // maxDepth
);

// Create sphere of interaction with k=5 neighbors, radius=100
var soi = new SpatialSoI<>(octree, 5, 100f);

// Insert nodes
var node1 = createNode(new Point3f(10, 20, 30));
soi.insert(node1, new Point3f(10, 20, 30));

// Find closest node to a position
var closest = soi.closestTo(new Point3f(15, 25, 35));

// Get neighbors within AOI radius
var neighbors = soi.getEnclosingNeighbors(node1);

// Update node position
soi.update(node1, new Point3f(20, 30, 40));

// Check if nodes' AOIs overlap
boolean overlaps = soi.overlaps(node1, new Point3f(25, 35, 45), 5f);

// Remove node
soi.remove(node1);
```

## Integration with Simulation Module

VON is used by the simulation module's bubble discovery mechanism:

- **Bubble Discovery**: Nodes use SoI to find nearby simulation bubbles
- **Interest Management**: Bubbles track which entities are within their perception range
- **Dynamic Updates**: As entities move, VON efficiently updates spatial relationships

See the [simulation module](../simulation/README.md) for details on how VON enables distributed animation.

## Implementation Details

### SpatialSoI Parameters

- **k**: Number of nearest neighbors to consider (typically 5-10)
- **radius**: Maximum perception distance for area-of-interest
- **spatialIndex**: Underlying Octree or other spatial index implementation

### Performance Characteristics

Based on the underlying spatial index (Octree):

| Operation | Complexity | Notes |
| ----------- | ------------ | ------- |
| Insert | O(log n) | Inserts into Octree |
| Update | O(log n) | Remove + Insert |
| Remove | O(log n) | Removes from Octree |
| closestTo | O(k log n) | k-NN query |
| getEnclosingNeighbors | O(k log n) | k-NN query |

Where:

- n = total number of nodes
- k = number of neighbors to find

## Testing

```bash
# Unit tests
mvn test -pl von

# Specific test class
mvn test -pl von -Dtest=SpatialSoITest
```

## Dependencies

- **lucien**: Core spatial data structures (Octree for spatial indexing)
- **sentry**: Delaunay tetrahedralization (Cursor, Vertex interfaces)
- **common**: Shared utilities

## Historical Context

This module is based on the **Thoth Interest Management and Load Balancing Framework** (copyright 2008-2009), which provided foundational work on Voronoi-based overlay networks for distributed virtual environments.

The original Thoth framework used Voronoi diagrams computed via Delaunay tetrahedralization for managing spatial interest. The current implementation uses k-NN queries on spatial indices for improved performance.

## Future Work

- [ ] Performance benchmarks for different k values
- [ ] Grid-based SoI optimization for uniform distributions
- [ ] Hierarchical AOI management for multi-scale perception
- [ ] Integration with ghost layer for distributed simulations

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details
