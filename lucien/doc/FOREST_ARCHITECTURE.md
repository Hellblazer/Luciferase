# Forest Architecture for Lucien

## Overview

A "forest" in spatial indexing represents a collection of multiple spatial index trees that together cover a larger or disconnected spatial domain. This document describes the forest architecture for Lucien, inspired by t8code's forest implementation but adapted for our spatial index framework.

## Concepts

### Forest vs Single Tree

- **Single Tree**: A single spatial index (Octree or Tetree) that covers one continuous spatial region
- **Forest**: A collection of spatial index trees that can cover:
  - Multiple disconnected spatial regions
  - A single large region subdivided for better load balancing
  - Different regions with different properties or resolutions

### Trees in a Forest

Each tree in the forest:
- Has its own spatial bounds and origin
- Can be refined independently
- Maintains its own entity storage
- Can have different maximum refinement levels
- Can use different spatial index types (Octree vs Tetree)

### Connectivity

Trees in a forest can be:
- **Disconnected**: No spatial relationship between trees
- **Adjacent**: Trees share boundaries but maintain separate indices
- **Overlapping**: Trees cover overlapping regions (requires special handling)

## Architecture Design

### Forest Class Structure

```java
public class Forest<Key extends SpatialKey<Key>, ID, Content> {
    // Collection of spatial index trees
    private List<TreeNode<Key, ID, Content>> trees;
    
    // Tree connectivity information
    private TreeConnectivity connectivity;
    
    // Global entity management
    private ForestEntityManager<ID, Content> entityManager;
    
    // Tree metadata
    private TreeMetadata[] treeMetadata;
}
```

### Tree Node Structure

```java
public class TreeNode<Key extends SpatialKey<Key>, ID, Content> {
    // The spatial index for this tree
    private AbstractSpatialIndex<Key, ID, Content> spatialIndex;
    
    // Tree bounds in global coordinates
    private BoundingBox globalBounds;
    
    // Tree ID within the forest
    private int treeId;
    
    // Neighbor information
    private List<TreeNeighbor> neighbors;
}
```

### Key Features

1. **Unified Entity Management**: Entities can be tracked across all trees with global IDs
2. **Cross-Tree Queries**: Support for queries that span multiple trees
3. **Dynamic Tree Creation**: Trees can be added/removed dynamically
4. **Load Balancing**: Entities can be migrated between trees
5. **Mixed Index Types**: Support both Octree and Tetree within the same forest

## Use Cases

### 1. Large-Scale Simulations

For simulations covering vast areas, divide the space into multiple trees:
- Each tree covers a manageable region
- Trees can be distributed across compute nodes
- Dynamic load balancing between trees

### 2. Multi-Resolution Modeling

Different regions with different detail requirements:
- High-resolution trees for areas of interest
- Coarse trees for background regions
- Adaptive refinement within each tree

### 3. Disconnected Domains

For simulations with spatially separated regions:
- Each region gets its own tree
- No wasted space indexing empty regions
- Efficient queries within each region

### 4. Temporal Forests

Trees representing different time steps:
- Each tree captures a snapshot in time
- Efficient temporal queries
- Space-time indexing capabilities

## Operations

### Forest Creation

```java
// Create a forest with multiple disconnected regions
Forest<MortonKey, Long, Entity> forest = new Forest<>();

// Add trees for different regions
forest.addTree(new Octree(origin1, size1));
forest.addTree(new Octree(origin2, size2));
forest.addTree(new Tetree(origin3, size3));
```

### Entity Management

```java
// Insert entity - forest determines appropriate tree
forest.insert(entityId, position, content);

// Query across all trees
List<Entity> nearby = forest.findNeighborsWithinDistance(position, radius);

// Move entity between trees
forest.relocateEntity(entityId, newPosition);
```

### Cross-Tree Operations

```java
// Ray traversal across multiple trees
List<Entity> hits = forest.rayIntersection(rayOrigin, rayDirection);

// Frustum culling across forest
List<Entity> visible = forest.frustumCull(frustum);

// Global k-NN search
List<Entity> nearest = forest.findKNearestNeighbors(position, k);
```

## Implementation Considerations

### 1. Tree Boundaries

- Trees can have overlapping or gap regions
- Boundary handling for entities near tree edges
- Ghost zones for smooth transitions

### 2. Load Balancing

- Monitor tree loads (entity count, query frequency)
- Dynamic tree splitting/merging
- Entity migration strategies

### 3. Parallel Processing

- Each tree can be processed independently
- Parallel queries across trees
- Distributed forest across multiple nodes

### 4. Memory Management

- Lazy tree initialization
- Tree pruning for empty regions
- Shared entity storage optimization

## Comparison with t8code

| Feature | t8code | Lucien Forest |
|---------|---------|---------------|
| Tree Types | Multiple element types | Octree/Tetree |
| Connectivity | Face/edge/vertex | Configurable |
| Refinement | Adaptive per element | Adaptive per node |
| Distribution | MPI-based | Thread/process flexible |
| Use Case | FEM/CFD meshes | Spatial indexing |

## Future Extensions

1. **Hierarchical Forests**: Forests of forests for extreme scales
2. **Dynamic Topology**: Runtime tree addition/removal
3. **Specialized Trees**: Custom tree types for specific regions
4. **Persistent Forests**: Efficient serialization/deserialization
5. **GPU Forests**: GPU-accelerated forest operations

## Conclusion

The forest architecture extends Lucien's spatial indexing capabilities to handle large-scale, complex spatial domains efficiently. By managing multiple spatial index trees as a cohesive unit, forests enable scalable spatial indexing for demanding applications while maintaining the performance benefits of the underlying tree structures.