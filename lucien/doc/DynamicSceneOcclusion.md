# Dynamic Scene Occlusion Culling - Technical Implementation Guide

## Overview

Dynamic Scene Occlusion Culling (DSOC) is an algorithm for efficiently rendering 3D scenes containing both static and dynamic (moving) objects. It extends traditional occlusion culling to handle object movement without rebuilding spatial data structures.

## Core Concepts

### 1. Temporal Bounding Volume (TBV)
A bounding volume that encompasses all possible positions of a dynamic object over a specified time period.

**Definition:**
```
TBV = {
    object_id: unique identifier
    bounding_volume: AABB/OBB/Sphere containing all possible positions
    validity_start_time: frame/time when TBV was created
    validity_duration: how long the TBV remains valid
    last_known_position: object's position when TBV was created
    velocity_bounds: maximum velocity in each direction (optional)
}
```

### 2. Visibility States
Objects can be in one of three states:
- **VISIBLE**: Currently being rendered
- **HIDDEN_WITH_TBV**: Not visible, position tracked by TBV
- **HIDDEN_EXPIRED**: TBV expired, needs position update

## Main Algorithm

### Dynamic Scene Occlusion Culling Algorithm

```pseudocode
function DSOC_RenderFrame(static_objects, dynamic_objects, camera):
    // Step 1: Perform occlusion culling on static geometry
    visible_static = OcclusionCull(static_objects, camera)
    
    // Step 2: Update spatial structure for visible dynamic objects only
    for each obj in dynamic_objects:
        if obj.state == VISIBLE or obj.tbv_expired():
            UpdateSpatialStructure(obj, obj.current_position)
            obj.state = UNKNOWN
    
    // Step 3: Perform occlusion culling including TBVs
    visible_list = OcclusionCullWithTBVs(spatial_structure, camera)
    
    // Step 4: Process results
    for each item in visible_list:
        if item.is_tbv:
            // TBV is visible - update actual object position
            obj = GetObject(item.object_id)
            UpdateSpatialStructure(obj, obj.current_position)
            obj.state = VISIBLE
            RenderObject(obj)
        else:
            // Regular object
            item.state = VISIBLE
            RenderObject(item)
    
    // Step 5: Create TBVs for newly hidden objects
    for each obj in dynamic_objects:
        if obj.state == UNKNOWN:
            obj.state = HIDDEN_WITH_TBV
            CreateTBV(obj)
```

## Spatial Structure Updates

### Octree-Based Implementation

#### Least Common Ancestor (LCA) Update
Instead of rebuilding the entire octree when an object moves, only update nodes under the LCA of the old and new positions.

```pseudocode
function UpdateOctreeForMovement(octree, object, old_pos, new_pos):
    lca_node = FindLCA(octree, old_pos, new_pos)
    
    // Remove from old position
    RemoveFromSubtree(lca_node, object, old_pos)
    
    // Insert at new position
    InsertIntoSubtree(lca_node, object, new_pos)
    
    // Update node bounds if necessary
    UpdateBoundsBottomUp(lca_node)

function FindLCA(octree, pos1, pos2):
    node = octree.root
    while node is not leaf:
        child1 = GetChildContaining(node, pos1)
        child2 = GetChildContaining(node, pos2)
        if child1 == child2:
            node = child1
        else:
            return node
    return node
```

### BSP-Tree Based Implementation

For BSP trees, the update process differs as BSP trees represent geometry directly:

```pseudocode
function UpdateBSPForMovement(bsp_tree, object, old_pos, new_pos):
    // Remove object's polygons from old leaves
    old_leaves = FindLeavesContaining(bsp_tree, object.bounds(old_pos))
    for leaf in old_leaves:
        RemoveObjectFromLeaf(leaf, object)
    
    // Add object's polygons to new leaves
    new_leaves = FindLeavesContaining(bsp_tree, object.bounds(new_pos))
    for leaf in new_leaves:
        AddObjectToLeaf(leaf, object)
```

## TBV Creation Strategies

### 1. Explicit Expiration (Fixed Duration)
```pseudocode
function CreateTBV_Explicit(object, duration_frames):
    tbv = new TBV()
    tbv.object_id = object.id
    tbv.validity_start = current_frame
    tbv.validity_duration = duration_frames  // e.g., 30 frames
    tbv.bounding_volume = object.current_bounds
    
    // Expand bounds based on maximum possible movement
    max_displacement = object.max_velocity * duration_frames
    tbv.bounding_volume.expand(max_displacement)
    
    return tbv
```

### 2. Implicit Expiration (Adaptive)
```pseudocode
function CreateTBV_Implicit(object):
    tbv = new TBV()
    tbv.object_id = object.id
    tbv.validity_start = current_frame
    
    // Calculate duration based on object properties
    if object.velocity.magnitude() < STATIONARY_THRESHOLD:
        tbv.validity_duration = LONG_DURATION  // e.g., 120 frames
    else:
        // Faster objects get shorter validity periods
        tbv.validity_duration = MIN_DURATION + 
            (MAX_DURATION - MIN_DURATION) * 
            (1.0 - object.velocity.magnitude() / MAX_VELOCITY)
    
    // Calculate bounds based on predicted movement
    tbv.bounding_volume = PredictMovementBounds(object, tbv.validity_duration)
    
    return tbv
```

### 3. Fuzzy TBV (Probabilistic)
For objects with predictable movement patterns:

```pseudocode
function CreateTBV_Fuzzy(object, confidence_level):
    tbv = new TBV()
    tbv.object_id = object.id
    
    // Predict future positions based on movement history
    predictions = PredictPositions(object.movement_history, num_frames)
    
    // Create bounding volume containing confidence_level% of predictions
    tbv.bounding_volume = ComputeConfidenceBounds(predictions, confidence_level)
    
    // Duration based on prediction confidence
    tbv.validity_duration = CalculateDurationFromConfidence(predictions)
    
    return tbv
```

## Occlusion Culling with TBVs

### Modified Hierarchical Z-Buffer Algorithm

```pseudocode
function HierarchicalZBufferWithTBVs(octree_node, z_pyramid, result_list):
    if not IsVisibleInZPyramid(octree_node.bounds, z_pyramid):
        return
    
    if octree_node.is_leaf:
        // Check objects in this leaf
        for item in octree_node.items:
            if item.is_tbv:
                if IsVisibleInZPyramid(item.bounding_volume, z_pyramid):
                    result_list.add(item)
            else:
                if IsVisibleInZPyramid(item.bounds, z_pyramid):
                    result_list.add(item)
                    UpdateZPyramid(z_pyramid, item)
    else:
        // Recursive traversal (front-to-back order)
        children = SortFrontToBack(octree_node.children, camera)
        for child in children:
            HierarchicalZBufferWithTBVs(child, z_pyramid, result_list)
```

## Implementation Data Structures

### Object State Management
```cpp
struct DynamicObject {
    int id;
    Transform current_transform;
    Mesh* geometry;
    
    // DSOC specific
    VisibilityState state;
    TBV* active_tbv;
    Vector3 velocity;
    float max_velocity;
    
    // Movement prediction (optional)
    CircularBuffer<Transform> movement_history;
};

enum VisibilityState {
    VISIBLE,
    HIDDEN_WITH_TBV,
    HIDDEN_EXPIRED,
    UNKNOWN
};
```

### Spatial Structure Node Extension
```cpp
struct OctreeNode {
    AABB bounds;
    OctreeNode* children[8];
    
    // Regular objects
    vector<Object*> static_objects;
    vector<DynamicObject*> dynamic_objects;
    
    // TBVs
    vector<TBV*> temporal_volumes;
    
    // Optimization: track if subtree has any dynamic content
    bool has_dynamic_content;
};
```

## Performance Optimizations

### 1. Temporal Coherence Exploitation
```pseudocode
function OptimizeTraversal(node, previous_visible_set):
    // Start with nodes that were visible last frame
    priority_queue = InitializeWithPreviouslyVisible(previous_visible_set)
    
    while not priority_queue.empty():
        current = priority_queue.pop()
        if IsVisible(current):
            ProcessNode(current)
            // Add neighbors with high probability of visibility
            AddLikelyVisibleNeighbors(priority_queue, current)
```

### 2. TBV Merging
Combine multiple small TBVs in the same region:

```pseudocode
function MergeTBVs(tbv_list, merge_threshold):
    merged_list = []
    
    for tbv in tbv_list:
        merged = false
        for existing in merged_list:
            if Distance(tbv.center, existing.center) < merge_threshold:
                existing.bounding_volume = Union(existing.bounding_volume, tbv.bounding_volume)
                existing.object_ids.add(tbv.object_id)
                merged = true
                break
        
        if not merged:
            merged_list.add(tbv)
    
    return merged_list
```

### 3. Level-of-Detail Integration
```pseudocode
function SelectLODWithTBV(object, camera):
    if object.has_active_tbv:
        // Use conservative LOD based on TBV bounds
        distance = MinDistance(camera.position, object.active_tbv.bounding_volume)
    else:
        distance = Distance(camera.position, object.position)
    
    return SelectLODByDistance(object, distance)
```

## Implementation Considerations

### Memory Management
- Pool allocate TBVs to avoid fragmentation
- Reuse expired TBVs instead of deallocating
- Maintain separate pools for different TBV sizes

### Thread Safety
```cpp
class ThreadSafeDSOC {
    // Separate locks for different operations
    mutex spatial_structure_mutex;
    mutex tbv_list_mutex;
    mutex visibility_state_mutex;
    
    // Double-buffering for frame coherence
    VisibilityData current_frame_data;
    VisibilityData previous_frame_data;
};
```

### Error Handling
- Validate TBV bounds don't exceed scene bounds
- Handle numerical precision issues in LCA computation
- Gracefully degrade when too many TBVs exist

## Configuration Parameters

```cpp
struct DSOCConfig {
    // TBV Creation
    int default_tbv_duration = 30;          // frames
    float tbv_expansion_factor = 1.2f;      // safety margin
    int max_tbvs_per_object = 3;           // prevent TBV explosion
    
    // Adaptive Expiration
    float velocity_threshold = 0.1f;        // units/frame
    int min_adaptive_duration = 10;         // frames
    int max_adaptive_duration = 120;        // frames
    
    // Performance
    int max_spatial_updates_per_frame = 100;
    float tbv_merge_distance = 5.0f;        // world units
    
    // Occlusion Culling
    int z_pyramid_levels = 6;
    float z_buffer_bias = 0.001f;
};
```

## Edge Cases and Solutions

1. **Teleporting Objects**: Detect large position changes and force immediate update
2. **Clustered Movement**: Use group TBVs for flocking behavior
3. **Oscillating Objects**: Increase TBV duration to encompass full motion range
4. **Scene Loading**: Batch-create TBVs for all initially hidden objects
5. **Memory Pressure**: Implement TBV eviction policy (LRU or priority-based)

## Validation and Debugging

```pseudocode
function ValidateDSOCState():
    // Ensure no object is both visible and has active TBV
    for obj in all_dynamic_objects:
        assert(not (obj.state == VISIBLE and obj.active_tbv != null))
    
    // Verify spatial structure consistency
    for node in spatial_structure:
        for obj in node.dynamic_objects:
            assert(node.bounds.contains(obj.current_bounds))
    
    // Check TBV validity
    for tbv in all_tbvs:
        assert(tbv.validity_start <= current_frame)
        assert(tbv.bounding_volume.is_valid())
```

This implementation guide provides the complete technical foundation for implementing Dynamic Scene Occlusion Culling as described in the paper.