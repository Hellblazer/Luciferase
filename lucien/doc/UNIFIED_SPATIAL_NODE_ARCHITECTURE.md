# Unified Spatial Node Architecture

## Overview

As of January 2025, the spatial node implementations have been unified under a common architecture that converges on the
Octree's approach for entity storage and child tracking. This document describes the unified architecture and its
benefits.

## Architecture Design

### Base Class: AbstractSpatialNode

The `AbstractSpatialNode` class now contains all common functionality for both Octree and Tetree nodes:

```java
public abstract class AbstractSpatialNode<ID extends EntityID> implements SpatialNodeStorage<ID> {
    protected final List<ID> entityIds;              // Entity storage
    protected byte childrenMask = 0;                 // Fine-grained child tracking
    protected final int maxEntitiesBeforeSplit;      // Split threshold
}
```

### Key Design Decisions

1. **Entity Storage**: Uses `List<ID>` for O(1) append operations and ordered iteration
    - Previous: OctreeNode used List, TetreeNodeImpl used Set
    - Unified: Both now use List for consistency

2. **Child Tracking**: Uses byte bitmask (childrenMask) for fine-grained child tracking
    - Previous: OctreeNode had childrenMask, TetreeNodeImpl had boolean hasChildren
    - Unified: Both now use childrenMask for O(1) child existence checking
    - Supports tracking exactly which of the 8 children exist (bits 0-7)

3. **Thread Safety**: Relies on external synchronization from AbstractSpatialIndex
    - No internal synchronization overhead
    - All access protected by read-write locks in the parent index

## Implementation Details

### Child Management Methods

```java
// Check if a specific child exists (O(1))
public boolean hasChild(int childIndex) {
    return (childrenMask & (1 << childIndex)) != 0;
}

// Set child bit when creating a child (O(1))
public void setChildBit(int childIndex) {
    childrenMask |= (1 << childIndex);
}

// Clear child bit when removing a child (O(1))
public void clearChildBit(int childIndex) {
    childrenMask &= ~(1 << childIndex);
}

// Check if any children exist (O(1))
public boolean hasChildren() {
    return childrenMask != 0;
}
```

### Entity Management

All entity operations are now unified in the base class:

- `addEntity(ID)` - Add entity and check split threshold
- `removeEntity(ID)` - Remove entity
- `containsEntity(ID)` - Check entity existence
- `getEntityIds()` - Get immutable view of entities
- `clearEntities()` - Remove all entities

## Subclass Simplification

### OctreeNode

Now only provides backward compatibility methods:

```java
public class OctreeNode<ID> extends AbstractSpatialNode<ID> {
    // Octant-specific naming for backward compatibility
    public boolean hasChild(int octant) { return super.hasChild(octant); }
    public void setChildBit(int octant) { super.setChildBit(octant); }
    public void clearChildBit(int octant) { super.clearChildBit(octant); }
}
```

### TetreeNodeImpl

Now only provides Set view for backward compatibility:

```java
public class TetreeNodeImpl<ID> extends AbstractSpatialNode<ID> {
    // Set view for code expecting Set-based interface
    public Set<ID> getEntityIdsAsSet() {
        return Collections.unmodifiableSet(new HashSet<>(entityIds));
    }
}
```

## Benefits of Unification

1. **Performance Consistency**: Both implementations now have identical performance characteristics
    - O(1) child existence checking
    - O(1) append for entity addition
    - O(n) for entity removal (acceptable trade-off)

2. **Code Reuse**: ~90% of node functionality now in base class
    - Reduced code duplication
    - Easier maintenance
    - Consistent behavior

3. **Fine-Grained Child Tracking**: Essential for efficient spatial operations
    - Know exactly which children exist without iteration
    - Efficient traversal planning
    - Better cache locality

4. **Memory Efficiency**: Byte mask uses only 8 bits vs boolean flags or collections
    - Minimal overhead per node
    - Better cache line utilization

## Migration Notes

### For Octree Code

- No changes needed - all existing APIs maintained
- Internal optimization transparent to callers

### For Tetree Code

- Must call `setChildBit()` when creating children
- Use `getEntityIdsAsSet()` if Set interface required
- Update to use child index (0-7) instead of boolean hasChildren

### Example: Tetree Child Creation

```java
// In Tetree.handleNodeSubdivision()
for(int i = 0;
i< 8;i++){
Tet childTet = tet.child(i);
    if(

shouldCreateChild(childTet, entitiesToRedistribute)){
TetreeNodeImpl<ID> childNode = new TetreeNodeImpl<>(maxEntitiesPerNode);
        spatialIndex.

put(childTet.index(),childNode);

// Critical: Set the child bit in parent
parent.

setChildBit(i);
    }
    }
```

## Performance Characteristics

| Operation            | Complexity | Notes                |
|----------------------|------------|----------------------|
| hasChild(index)      | O(1)       | Bit test operation   |
| setChildBit(index)   | O(1)       | Bit set operation    |
| clearChildBit(index) | O(1)       | Bit clear operation  |
| hasChildren()        | O(1)       | Zero test on byte    |
| addEntity()          | O(1)       | List append          |
| removeEntity()       | O(n)       | List scan and remove |
| containsEntity()     | O(n)       | List contains        |
| getEntityCount()     | O(1)       | List size            |

## Future Considerations

1. **Entity Storage Optimization**: Could add indexed storage for O(1) removal if needed
2. **Child Mask Extension**: Could extend to 16 or 32 bits for higher-degree trees
3. **Specialized Nodes**: Could create specialized leaf/internal node types if profiling shows benefits

## Conclusion

The unified spatial node architecture successfully converges both Octree and Tetree implementations on a common,
efficient design. The fine-grained child tracking via bitmask provides optimal performance for spatial operations while
maintaining a clean, maintainable codebase.
