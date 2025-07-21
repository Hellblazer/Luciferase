# Phase 3.3 Analysis: Spatial Indexing for Neighbor Queries

## Current Implementation Analysis

### Point Location Algorithm
The current implementation uses a "walking" algorithm:
1. Start from a known tetrahedron (usually the last one accessed)
2. Check which face the query point is outside of
3. Move to the neighbor on that side
4. Repeat until the containing tetrahedron is found

```java
public Tetrahedron locate(Tuple3f query, Random entropy) {
    // Check each face orientation
    for (V face : Grid.VERTICES) {
        if (orientationWrt(face, query) < 0.0d) {
            // Walk to neighbor
            tetrahedron = current.getNeighbor(o);
        }
    }
}
```

### Performance Characteristics
- **Average case**: O(n^(1/3)) for n tetrahedra
- **Worst case**: O(n) if walking across entire mesh
- **Best case**: O(1) if starting near target

### Where Neighbor Queries Occur
1. **Point location** (`locate()` method) - finding which tetrahedron contains a point
2. **Vertex insertion** (`track()` method) - locating where to insert new vertex
3. **Flip operations** - checking adjacent tetrahedra during flips

## Proposed Spatial Index Implementation

### Design Options

#### Option 1: Grid-Based Spatial Hash
```java
public class SpatialHashIndex {
    private final Map<Integer, List<Tetrahedron>> grid;
    private final double cellSize;
    
    private int hash(Point3f p) {
        int x = (int)(p.x / cellSize);
        int y = (int)(p.y / cellSize);
        int z = (int)(p.z / cellSize);
        return (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
    }
    
    public Tetrahedron locate(Point3f p) {
        // Get candidate tetrahedra from grid cell
        List<Tetrahedron> candidates = grid.get(hash(p));
        // Test each candidate
        for (Tetrahedron t : candidates) {
            if (t.contains(p)) return t;
        }
        return null;
    }
}
```

#### Option 2: Hierarchical Index (Octree)
```java
public class OctreeIndex {
    private class Node {
        Bounds bounds;
        List<Tetrahedron> tetrahedra;
        Node[] children; // 8 children for octree
    }
    
    public Tetrahedron locate(Point3f p) {
        Node current = root;
        while (current.children != null) {
            current = selectChild(current, p);
        }
        // Linear search in leaf
        for (Tetrahedron t : current.tetrahedra) {
            if (t.contains(p)) return t;
        }
        return null;
    }
}
```

#### Option 3: Jump-and-Walk
```java
public class JumpAndWalkIndex {
    // Sample of well-distributed tetrahedra
    private final Tetrahedron[] landmarks;
    private final int landmarkCount = 100;
    
    public Tetrahedron locate(Point3f p) {
        // Find nearest landmark
        Tetrahedron nearest = findNearestLandmark(p);
        // Walk from there
        return nearest.locate(p, entropy);
    }
}
```

## Implementation Challenges

### 1. Dynamic Updates
- Tetrahedra are created/destroyed during flips
- Index must be updated efficiently
- Bulk updates during flip cascades

### 2. Memory Overhead
- Each tetrahedron needs bounding box
- Index structures add memory
- Must balance granularity vs memory

### 3. Spatial Overlap
- Tetrahedra can have overlapping bounding boxes
- Makes precise indexing difficult
- May need to check multiple candidates

### 4. Integration Points
Where to add spatial indexing:
- `MutableGrid.locate()` - primary entry point
- `Vertex.locate()` - for nearby searches
- `Tetrahedron.locate()` - fallback to walking

## Recommendation

**Start with Option 3 (Jump-and-Walk)** because:
1. **Simplest to implement** - minimal changes to existing code
2. **Low memory overhead** - just array of landmark tetrahedra
3. **Works with dynamic updates** - landmarks can be lazily updated
4. **Proven effective** - used in many Delaunay implementations

### Implementation Plan
1. Add landmark selection during grid construction
2. Maintain array of ~sqrt(n) well-distributed tetrahedra
3. Modify `locate()` to find nearest landmark first
4. Measure improvement in average walk length

### Expected Performance
- Reduce average walk from O(n^(1/3)) to O(n^(1/6))
- 10-20% overall performance improvement
- Minimal memory overhead (<1% of total)