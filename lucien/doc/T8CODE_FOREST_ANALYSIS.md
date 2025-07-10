# t8code Forest Scheme Analysis

## Overview

This document provides a detailed technical analysis of t8code's forest implementation, focusing on how it manages collections of adaptive mesh refinement trees to handle complex spatial domains.

## Core Concepts

### 1. Forest Definition

In t8code, a forest is:
- A collection of multiple connected adaptive space-trees
- Built on top of a coarse mesh (cmesh)
- Each coarse mesh element becomes a tree root
- Trees can be of different element types (hex, tet, prism, etc.)

### 2. Key Data Structures

#### Forest Structure (t8_forest_struct_t)
```c
typedef struct t8_forest {
    t8_refcount_t       rc;                    // Reference counter
    sc_MPI_Comm         mpicomm;               // MPI communicator
    t8_cmesh_t          cmesh;                 // Coarse mesh foundation
    t8_scheme_cxx_t    *scheme_cxx;            // Element refinement schemes
    
    // Tree management
    t8_gloidx_t         first_local_tree;      // First tree on this process
    t8_gloidx_t         last_local_tree;       // Last tree on this process
    t8_gloidx_t         global_num_trees;      // Total trees across all processes
    sc_array_t         *trees;                 // Array of local trees
    
    // Element counts
    t8_locidx_t         local_num_elements;    // Elements on this process
    t8_gloidx_t         global_num_elements;   // Total elements globally
    
    // Ghost layer
    t8_forest_ghost_t   ghosts;                // Ghost elements for communication
} t8_forest_struct_t;
```

#### Tree Structure (t8_tree_struct_t)
```c
typedef struct t8_tree {
    t8_element_array_t  elements;              // Locally stored elements
    t8_eclass_t         eclass;                // Element class (hex, tet, etc.)
    t8_element_t       *first_desc;            // First local descendant
    t8_element_t       *last_desc;             // Last local descendant
    t8_locidx_t         elements_offset;       // Cumulative element count
} t8_tree_struct_t;
```

### 3. Forest Creation Process

#### Step 1: Create Coarse Mesh (cmesh)
```c
// Example: Create a brick of hexahedral elements
t8_cmesh_t cmesh = t8_cmesh_new_brick(num_x, num_y, num_z, 
                                      periodic_x, periodic_y, periodic_z, comm);
```

#### Step 2: Initialize Forest
```c
t8_forest_t forest;
t8_forest_init(&forest);
t8_forest_set_cmesh(forest, cmesh, comm);
t8_forest_set_scheme(forest, t8_scheme_new_default_cxx());
```

#### Step 3: Set Initial Refinement
```c
// Uniform refinement
t8_forest_set_level(forest, initial_level);

// Or adaptive refinement
t8_forest_set_adapt(forest, NULL, adapt_callback, recursive);
```

#### Step 4: Commit Forest
```c
t8_forest_commit(forest);
```

### 4. Tree Connectivity

Trees maintain connectivity through:
- **Face connections**: Inherited from cmesh
- **Edge connections**: For 3D meshes
- **Vertex connections**: For complete neighbor information

Example of setting face connections:
```c
t8_cmesh_set_join(cmesh, tree1_id, tree2_id, face1, face2, orientation);
```

### 5. Distributed Forest Management

#### Partitioning
- Trees are distributed across MPI processes
- Each process owns a contiguous range of trees
- Load balancing redistributes elements

```c
// Partition forest for load balance
t8_forest_set_partition(forest, set_from, 0);
```

#### Ghost Layer
- Ghost elements facilitate inter-process communication
- Face, edge, or vertex ghosts supported
- Essential for parallel algorithms

```c
// Enable ghost layer
t8_forest_set_ghost(forest, 1, T8_GHOST_FACES);
```

### 6. Forest Operations

#### Adaptation
```c
// Adapt forest based on callback
int adapt_callback(t8_forest_t forest, t8_forest_t forest_from,
                   t8_locidx_t which_tree, t8_locidx_t lelement_id,
                   t8_eclass_scheme_c *ts, int num_elements,
                   t8_element_t *elements[]) {
    // Return > 0 to refine, < 0 to coarsen, 0 to keep
    return should_refine ? 1 : 0;
}
```

#### Balancing
```c
// 2:1 balance constraint
t8_forest_set_balance(forest, set_from, no_repartition);
```

### 7. Multiple Disconnected Regions

#### Using t8_cmesh_new_disjoint_bricks
```c
// Each process gets its own brick of trees
t8_cmesh_t cmesh = t8_cmesh_new_disjoint_bricks(
    local_num_x, local_num_y, local_num_z,
    periodic_x, periodic_y, periodic_z, comm);
```

#### Manual Construction
```c
t8_cmesh_init(&cmesh);

// Add trees for region 1
for (int i = 0; i < region1_trees; i++) {
    t8_cmesh_set_tree_class(cmesh, i, T8_ECLASS_HEX);
    t8_cmesh_set_tree_vertices(cmesh, i, vertices_region1[i], 8);
}

// Add trees for region 2 (disconnected)
for (int i = 0; i < region2_trees; i++) {
    int tree_id = region1_trees + i;
    t8_cmesh_set_tree_class(cmesh, tree_id, T8_ECLASS_TET);
    t8_cmesh_set_tree_vertices(cmesh, tree_id, vertices_region2[i], 4);
}

// No connections between regions = disconnected
t8_cmesh_commit(cmesh, comm);
```

### 8. Key Algorithms

#### Tree Traversal
```c
// Iterate over all local trees
t8_locidx_t num_local_trees = t8_forest_get_num_local_trees(forest);
for (t8_locidx_t itree = 0; itree < num_local_trees; itree++) {
    t8_tree_t tree = t8_forest_get_tree(forest, itree);
    // Process tree
}
```

#### Element Iteration
```c
// Iterate over elements in a tree
t8_locidx_t num_elements = t8_forest_get_tree_num_elements(forest, itree);
for (t8_locidx_t ielement = 0; ielement < num_elements; ielement++) {
    t8_element_t *element = t8_forest_get_element_in_tree(forest, itree, ielement);
    // Process element
}
```

### 9. Memory Management

- **Reference counting**: Automatic memory management
- **Lazy allocation**: Trees allocated only when needed
- **Shared memory arrays**: For distributed metadata

### 10. Performance Optimizations

1. **SFC ordering**: Elements ordered along space-filling curves
2. **Tree-local operations**: Most operations are tree-local
3. **Collective communication**: Efficient MPI patterns
4. **Memory pools**: Reduced allocation overhead

## Key Insights for Lucien Implementation

### 1. Tree Independence
- Trees can be processed independently
- No global data structures needed
- Natural parallelization

### 2. Flexibility
- Mixed element types in same forest
- Arbitrary connectivity patterns
- Dynamic adaptation

### 3. Scalability
- Designed for distributed memory
- Efficient ghost layer communication
- Load balancing built-in

### 4. Simplicity
- Disconnected regions need no special handling
- Forest = collection of trees
- Connectivity is optional

## Differences from Lucien Requirements

| Aspect | t8code | Lucien Needs |
|--------|---------|--------------|
| Purpose | AMR for FEM/CFD | Spatial indexing |
| Elements | Mesh elements | Point entities |
| Refinement | Element subdivision | Spatial decomposition |
| Connectivity | Face/edge/vertex | Optional adjacency |
| Ghost layer | For FEM stencils | For nearby queries |

## Recommendations for Lucien

1. **Adopt tree-based architecture**: Each spatial region = one tree
2. **Support mixed types**: Allow Octree and Tetree in same forest
3. **Keep connectivity optional**: Many use cases don't need it
4. **Focus on spatial queries**: Cross-tree k-NN, range queries
5. **Simplify ghost layer**: Only for boundary entities

## Conclusion

T8code's forest implementation provides an elegant solution for managing multiple adaptive trees. Its key strength is treating disconnected regions naturally through its tree-based architecture. Lucien can adopt similar concepts while focusing on spatial indexing rather than mesh refinement.