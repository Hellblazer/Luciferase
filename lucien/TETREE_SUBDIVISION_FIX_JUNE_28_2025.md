# Tetree Subdivision Fix - June 28, 2025

## Problem Identified

The memory usage investigation revealed that Tetree was creating only 2 nodes for 1000 entities, while Octree created 6,430 nodes - a 3,215:1 ratio. This indicated that Tetree was not properly subdividing cells when they exceeded the threshold of 10 entities per node.

## Root Cause

Tetree was using the generic `insertAtPosition` method from `AbstractSpatialIndex`, while Octree had its own override that included critical logic:

```java
// If the node has been subdivided, insert into the appropriate child node
if (node.hasChildren() || node.isEmpty()) {
    var childLevel = (byte) (level + 1);
    if (childLevel <= maxDepth) {
        insertAtPosition(entityId, position, childLevel);
        return;
    }
}
```

This logic ensures that when inserting into an empty node or a node that has already been subdivided, the insertion automatically goes to a finer level. Without this, all entities at a given level would accumulate in the same few nodes.

## Solution Implemented

Added an override of `insertAtPosition` in Tetree.java that mirrors the Octree implementation:

```java
@Override
protected void insertAtPosition(ID entityId, Point3f position, byte level) {
    var tetIndex = calculateSpatialIndex(position, level);
    
    // Get or create node
    var node = spatialIndex.computeIfAbsent(tetIndex, k -> {
        sortedSpatialIndices.add(tetIndex);
        return nodePool.acquire();
    });

    // If the node has been subdivided, insert into the appropriate child node
    // This ensures proper spatial distribution by automatically going deeper
    if (node.hasChildren() || node.isEmpty()) {
        var childLevel = (byte) (level + 1);
        if (childLevel <= maxDepth) {
            insertAtPosition(entityId, position, childLevel);
            return;
        }
    }

    // Add entity to node
    var shouldSplit = node.addEntity(entityId);

    // Track entity location
    entityManager.addEntityLocation(entityId, tetIndex);

    // Handle subdivision if needed
    if (shouldSplit && level < maxDepth) {
        if (bulkLoadingMode) {
            // Defer subdivision during bulk loading
            subdivisionManager.deferSubdivision(tetIndex, node, node.getEntityCount(), level);
        } else {
            // Immediate subdivision
            handleNodeSubdivision(tetIndex, level, node);
        }
    }

    // Check for auto-balancing after insertion
    checkAutoBalance();
}
```

## Results

### Before Fix (OctreeVsTetreeBenchmark):
- **Insertion**: Octree 9.7x to 770x faster
- **Memory**: Tetree used 20% of Octree memory (due to having 500x fewer nodes)
- **Node Count**: ~2 nodes for Tetree vs ~6,430 for Octree

### After Fix (OctreeVsTetreeBenchmark):
- **Insertion**: Octree 6.0x to 34.6x faster (massive improvement)
- **Memory**: Tetree uses 93-103% of Octree memory (proper node distribution)
- **k-NN Performance**: Tetree only 1.16x slower (was likely much worse with 2 huge nodes)

## Performance Improvements

| Dataset | Before Fix | After Fix | Improvement |
|---------|------------|-----------|-------------|
| 100     | 9.7x slower | 6.0x slower | 38% better |
| 1K      | 57.6x slower | 9.2x slower | 84% better |
| 10K     | 770x slower | 34.6x slower | 96% better |

## Conclusion

The fix ensures that Tetree properly subdivides space like Octree does, creating a balanced tree structure instead of accumulating all entities in a few large nodes. This dramatically improves:

1. **Insertion performance** - No longer checking 500 entities per node
2. **Query performance** - Better spatial locality with properly subdivided nodes
3. **Memory efficiency** - Now comparable to Octree instead of misleadingly low
4. **Overall correctness** - Tetree now behaves as designed

The remaining performance gap (Tetree 6-35x slower for insertions) is due to the fundamental algorithmic difference: Tetree's O(level) tmIndex() computation vs Octree's O(1) Morton encoding.