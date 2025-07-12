# T8code Forest Management for Multiple Disconnected Spatial Regions

Based on the analysis of the t8code source code, here's how t8code manages spatially disconnected regions:

## Key Concepts

### 1. **CMesh (Coarse Mesh)**
The cmesh is the foundational structure that defines the connectivity and spatial relationships between "trees" (coarse elements). Each tree represents a spatial region that can be independently refined.

### 2. **Forest**
A forest is a collection of refined trees built on top of a cmesh. The forest manages the adaptive refinement of each tree independently.

## Creating Disconnected Regions

### Method 1: Using `t8_cmesh_new_disjoint_bricks`

This function creates a cmesh where each MPI process has its own disconnected brick of trees:

```c
// From time_partition.c example
t8_cmesh_t cmesh = t8_cmesh_new_disjoint_bricks(
    x,          // Number of trees in x direction
    y,          // Number of trees in y direction  
    z,          // Number of trees in z direction (0 for 2D)
    x_periodic, // Periodic boundary in x
    y_periodic, // Periodic boundary in y
    z_periodic, // Periodic boundary in z
    comm        // MPI communicator
);
```

Key features:
- Each MPI process gets its own spatially disconnected brick
- Trees within each brick can be connected or periodic
- No connectivity between bricks on different processes

### Method 2: Building Custom CMesh with Disconnected Components

You can manually create a cmesh with multiple disconnected regions:

```c
// Initialize cmesh
t8_cmesh_t cmesh;
t8_cmesh_init(&cmesh);

// Add trees for region 1
for (int i = 0; i < num_trees_region1; i++) {
    t8_cmesh_set_tree_class(cmesh, tree_id, T8_ECLASS_TET);
    t8_cmesh_set_tree_vertices(cmesh, tree_id, vertices_region1[i], 4);
    tree_id++;
}

// Add trees for region 2 (disconnected from region 1)
for (int i = 0; i < num_trees_region2; i++) {
    t8_cmesh_set_tree_class(cmesh, tree_id, T8_ECLASS_TET);
    t8_cmesh_set_tree_vertices(cmesh, tree_id, vertices_region2[i], 4);
    tree_id++;
}

// Don't set face connections between regions - they remain disconnected

// Commit the cmesh
t8_cmesh_commit(cmesh, comm);
```

## Creating a Forest from Disconnected CMesh

Once you have a cmesh with disconnected regions, create a forest:

```c
// Initialize forest
t8_forest_t forest;
t8_forest_init(&forest);

// Set the cmesh (forest takes ownership)
t8_forest_set_cmesh(forest, cmesh, comm);

// Set the element scheme
t8_scheme_cxx_t *scheme = t8_scheme_new_default_cxx();
t8_forest_set_scheme(forest, scheme);

// Set initial refinement level
t8_forest_set_level(forest, initial_level);

// Commit the forest
t8_forest_commit(forest);
```

## How T8code Manages Disconnected Regions

1. **Independent Tree Management**: Each tree in the cmesh can be refined independently. Trees that aren't connected don't influence each other's refinement.

2. **Tree Numbering**: Trees are numbered globally (0 to num_global_trees-1) regardless of connectivity. Disconnected regions are simply ranges of tree IDs without face connections.

3. **Parallel Distribution**: The forest can be partitioned across MPI processes. Each process manages a subset of trees, which may include trees from different disconnected regions.

4. **Local Operations**: Most operations (refinement, coarsening, element iteration) work on a per-tree basis, so disconnected regions don't require special handling.

## Example: Managing Multiple Spatial Domains

```c
// Example: Create 3 disconnected cubic regions
void create_three_disconnected_cubes(sc_MPI_Comm comm) {
    t8_cmesh_t cmesh;
    t8_cmesh_init(&cmesh);
    
    // Region 1: Cube at origin
    t8_cmesh_set_tree_class(cmesh, 0, T8_ECLASS_HEX);
    double vertices1[8][3] = {
        {0,0,0}, {1,0,0}, {0,1,0}, {1,1,0},
        {0,0,1}, {1,0,1}, {0,1,1}, {1,1,1}
    };
    t8_cmesh_set_tree_vertices(cmesh, 0, (double*)vertices1, 8);
    
    // Region 2: Cube at (5,0,0) - disconnected
    t8_cmesh_set_tree_class(cmesh, 1, T8_ECLASS_HEX);
    double vertices2[8][3] = {
        {5,0,0}, {6,0,0}, {5,1,0}, {6,1,0},
        {5,0,1}, {6,0,1}, {5,1,1}, {6,1,1}
    };
    t8_cmesh_set_tree_vertices(cmesh, 1, (double*)vertices2, 8);
    
    // Region 3: Cube at (0,5,0) - disconnected
    t8_cmesh_set_tree_class(cmesh, 2, T8_ECLASS_HEX);
    double vertices3[8][3] = {
        {0,5,0}, {1,5,0}, {0,6,0}, {1,6,0},
        {0,5,1}, {1,5,1}, {0,6,1}, {1,6,1}
    };
    t8_cmesh_set_tree_vertices(cmesh, 2, (double*)vertices3, 8);
    
    // No face connections set - all three cubes are disconnected
    
    // Commit cmesh
    t8_cmesh_commit(cmesh, comm);
    
    // Create forest
    t8_forest_t forest;
    t8_forest_init(&forest);
    t8_forest_set_cmesh(forest, cmesh, comm);
    t8_forest_set_scheme(forest, t8_scheme_new_default_cxx());
    t8_forest_set_level(forest, 3); // Refine to level 3
    t8_forest_commit(forest);
    
    // Each cube is now independently refined to level 3
    // They remain spatially disconnected
}
```

## Key Points

1. **No Special "Forest of Forests"**: T8code doesn't have a separate "forest of forests" concept. A single forest can contain multiple disconnected spatial regions.

2. **Tree-Based Architecture**: The fundamental unit is the "tree" (coarse element). Disconnected regions are simply groups of trees without face connections.

3. **Flexibility**: You can have any combination of connected and disconnected regions within a single forest.

4. **Parallel Scalability**: Disconnected regions can be naturally distributed across MPI processes for parallel computation.

5. **Independent Refinement**: Each disconnected region can be refined independently according to local criteria.

This design makes t8code well-suited for applications with multiple spatial domains, such as:
- Multi-body simulations
- Domain decomposition methods
- Adaptive mesh refinement for scattered objects
- Parallel load balancing across disconnected regions