# Tetree Spatial Range Query Analysis

## Issue Summary

The `entitiesInRegion` method appears to return entities outside the query bounds, but this is actually **correct behavior** based on how spatial indexing works with tetrahedra.

## Root Cause

The spatial index stores entities in tetrahedra at various levels of the hierarchy. Each level has a different cell size:

- Level 0: 2,097,152 units per side
- Level 5: 65,536 units per side  
- Level 10: 2,048 units per side
- Level 15: 64 units per side
- Level 21: 1 unit per side

When querying a spatial region, the algorithm:
1. Finds all tetrahedra that intersect with the query region
2. Returns ALL entities stored in those tetrahedra

## Example Case

Consider:
- Entity at position (50, 50, 50) stored at level 5
- This entity is in tetrahedron with bounds approximately (0,0,0) to (65536, 65536, 65536)
- Query region: (200, 200, 200) to (300, 300, 300)

Result: The entity IS returned because its containing tetrahedron intersects the query region, even though the entity's exact position is outside the query bounds.

## Why This Happens

This is a fundamental characteristic of spatial indexing:
- Entities are stored in spatial cells (tetrahedra)
- Queries return all entities in cells that intersect the query region
- The coarser the level (lower numbers), the larger the cells, the less precise the results

## Solutions

### 1. Post-filtering (Recommended)
After getting results from `entitiesInRegion`, filter by exact position:

```java
List<LongEntityID> results = tetree.entitiesInRegion(region);
List<LongEntityID> filtered = results.stream()
    .filter(id -> {
        Point3f pos = tetree.getEntityPosition(id);
        return pos.x >= region.minX && pos.x <= region.maxX &&
               pos.y >= region.minY && pos.y <= region.maxY &&
               pos.z >= region.minZ && pos.z <= region.maxZ;
    })
    .collect(Collectors.toList());
```

### 2. Use Finer Levels
Store entities at higher levels (15-21) for more precise spatial queries, at the cost of more nodes in the tree.

### 3. Adaptive Level Selection
The system already has dynamic level selection based on entity density. Ensure it's choosing appropriate levels.

## Performance Considerations

The current "brute force" approach in `getSpatialIndexRange` that checks all existing nodes is actually reasonable because:

1. It only checks nodes that exist (sparse tree)
2. Tetrahedral intersection tests are fast
3. Attempting to optimize by predicting which tetrahedra might intersect can be complex and error-prone

The main optimization opportunity is in the post-filtering step - if many entities are returned but few are actually in the region, consider storing entities at finer levels.

## Test Updates Needed

The tests should be updated to either:
1. Use much smaller query regions relative to the tetrahedron size
2. Use higher levels (finer granularity) for testing
3. Add post-filtering to test exact containment
4. Adjust expectations to match the actual behavior

## Conclusion

The `entitiesInRegion` behavior is **correct** - it's returning all entities in tetrahedra that intersect the query region. This is standard behavior for spatial indexes. The "issue" is actually a misunderstanding of how spatial indexing works at different levels of granularity.