# Forest Management API

**Last Updated**: 2025-12-08
**Status**: Current

The Forest Management API provides multi-tree spatial indexing capabilities, enabling coordinated operations across multiple spatial index trees. This API supports distributed spatial indexing, load balancing, and advanced spatial partitioning strategies.

## Core Forest Classes

### Forest<Key, ID, Content>

The main forest management class that coordinates multiple spatial index trees.

```java

// Create a forest
Forest<MortonKey, LongEntityID, String> forest = new Forest<>();

// Add trees to the forest
TreeMetadata metadata = TreeMetadata.builder()
    .name("region_north")
    .treeType(TreeMetadata.TreeType.OCTREE)
    .property("region", "north")
    .build();

String treeId1 = forest.addTree(octree1, metadata);
String treeId2 = forest.addTree(octree2);

// Forest-wide operations
List<LongEntityID> results = forest.findKNearestNeighbors(position, 10);
forest.insertEntity(entityId, position, content);
forest.removeEntity(entityId);

```text

**Key Methods:**
- `addTree(AbstractSpatialIndex, TreeMetadata)` - Add a tree with metadata
- `removeTree(String treeId)` - Remove a tree from the forest
- `findKNearestNeighbors(Point3f, int)` - Cross-tree k-NN search
- `entitiesInRegion(Spatial.Region)` - Multi-tree spatial queries
- `insertEntity(ID, Point3f, Content)` - Insert entity into appropriate tree
- `updateEntity(ID, Point3f)` - Move entity across trees if needed
- `getTree(String treeId)` - Get specific tree by ID
- `getAllTrees()` - Get all trees in the forest

### GridForest

Specialized forest implementation for creating uniform grids of spatial index trees.

```java

// Create a 4x4x4 grid of octrees
GridForest<MortonKey, LongEntityID, String> gridForest = 
    GridForest.createOctreeGrid(
        new Point3f(0, 0, 0),           // origin
        new Vector3f(1000, 1000, 1000), // total size
        4, 4, 4                         // grid dimensions
    );

// Create tetree grid
GridForest<TetreeKey, LongEntityID, String> tetGrid = 
    GridForest.createTetreeGrid(origin, size, 2, 2, 2);

```text

**Factory Methods:**
- `createOctreeGrid(Point3f origin, Vector3f size, int x, int y, int z)` - Uniform octree grid
- `createTetreeGrid(Point3f origin, Vector3f size, int x, int y, int z)` - Uniform tetree grid

### AdaptiveForest

Dynamic forest that automatically adapts tree structure based on entity density.

```java

// Create adaptive forest with density-based subdivision
AdaptiveForest<MortonKey, LongEntityID, String> adaptiveForest = 
    new AdaptiveForest<>(config, () -> new Octree<>(idGen, 10, (byte)20));

// Configure adaptation parameters
adaptiveForest.setDensityThreshold(100);        // Split when >100 entities
adaptiveForest.setVarianceThreshold(0.15f);     // Split when variance >15%
adaptiveForest.setMergeThreshold(20);           // Merge when <20 entities
adaptiveForest.enableBackgroundAdaptation(30000); // Check every 30 seconds

// The forest automatically handles subdivision and merging
adaptiveForest.insertEntity(entityId, position, content);

```text

**Key Features:**
- **Automatic Subdivision**: Creates new trees when density exceeds thresholds
- **Tree Merging**: Combines low-density adjacent trees
- **Multiple Strategies**: Octant, Binary X/Y/Z, K-means, Adaptive subdivision
- **Background Processing**: Non-blocking adaptation with configurable intervals

### HierarchicalForest

Multi-level forest for level-of-detail (LOD) management based on viewer distance.

```java

// Create hierarchical forest with 3 LOD levels
HierarchicalForest<MortonKey, LongEntityID, String> hierForest = 
    new HierarchicalForest<>(Arrays.asList(10.0f, 50.0f, 200.0f), treeFactory);

// Set viewer position for distance-based LOD
hierForest.setViewerPosition(new Point3f(100, 100, 100));

// Entities automatically promoted/demoted between levels
hierForest.insertEntity(entityId, position, content);

// Query with different LOD strategies
List<LongEntityID> currentLOD = hierForest.findKNearestNeighbors(
    position, 10, QueryMode.CURRENT_LOD);
List<LongEntityID> allLevels = hierForest.findKNearestNeighbors(
    position, 10, QueryMode.ALL_LEVELS);

```text

**Query Modes:**
- `CURRENT_LOD` - Query only current level based on viewer distance
- `ALL_LEVELS` - Query across all hierarchical levels
- `PROGRESSIVE` - Start with current level, expand if needed
- `ADAPTIVE` - Dynamically choose based on query requirements

## Entity Management

### ForestEntityManager

Manages entity lifecycle across multiple trees with automatic tree assignment.

```java

ForestEntityManager<LongEntityID, String> entityManager = 
    new ForestEntityManager<>(forest);

// Configure assignment strategy
entityManager.setAssignmentStrategy(AssignmentStrategy.SPATIAL_BOUNDS);

// Entity operations with automatic tree selection
LongEntityID entityId = entityManager.insertEntity(position, content);
entityManager.updateEntityPosition(entityId, newPosition); // Migrates if needed
entityManager.removeEntity(entityId);

// Query entities across all trees
List<LongEntityID> nearby = entityManager.findKNearestNeighbors(position, 5);

```text

**Assignment Strategies:**
- `ROUND_ROBIN` - Distribute evenly across trees
- `SPATIAL_BOUNDS` - Assign based on spatial location
- `LOAD_BALANCED` - Assign to least loaded tree

### AdaptiveForestEntityManager

Enhanced entity manager for adaptive forests with density tracking.

```java

AdaptiveForestEntityManager<LongEntityID, String> adaptiveEM = 
    new AdaptiveForestEntityManager<>(adaptiveForest);

// Automatically tracks entity density for adaptation decisions
adaptiveEM.insertEntity(position, content); // Triggers adaptation if needed

```text

## Dynamic Management

### DynamicForestManager

Manages runtime forest operations including tree splitting, merging, and load balancing.

```java

DynamicForestManager<MortonKey, LongEntityID, String> manager = 
    new DynamicForestManager<>(forest, entityManager, treeFactory);

// Enable automatic management
manager.enableAutoManagement(60000); // Check every minute

// Manual operations
manager.splitTree(treeId, SplitStrategy.OCTANT);
manager.mergeTrees(Arrays.asList(treeId1, treeId2));
manager.rebalanceForest();

```text

**Features:**
- **Automatic Splitting**: Split overloaded trees
- **Tree Merging**: Combine underutilized trees  
- **Load Balancing**: Redistribute entities for optimal performance
- **Background Processing**: Non-blocking management operations

### ForestLoadBalancer

Implements load balancing strategies for optimal entity distribution.

```java

ForestLoadBalancer<MortonKey, LongEntityID, String> balancer = 
    new ForestLoadBalancer<>(forest);

// Configure load thresholds
balancer.setEntityCountThreshold(1000);
balancer.setMemoryThreshold(100 * 1024 * 1024); // 100MB
balancer.setQueryLoadThreshold(100); // queries/second

// Rebalance based on current load
balancer.rebalance();

// Get load metrics
LoadMetrics metrics = balancer.getLoadMetrics(treeId);

```text

## Connectivity and Ghost Zones

### TreeConnectivityManager

Manages spatial relationships between trees in the forest.

```java

TreeConnectivityManager<MortonKey, LongEntityID, String> connectivity = 
    new TreeConnectivityManager<>(forest);

// Discover adjacent trees
List<String> neighbors = connectivity.findAdjacentTrees(treeId);

// Check if trees share boundaries
boolean adjacent = connectivity.areAdjacent(treeId1, treeId2);

// Get shared boundary information
BoundaryInfo boundary = connectivity.getSharedBoundary(treeId1, treeId2);

```text

### GhostZoneManager

Manages ghost entities across tree boundaries for seamless operations.

```java

GhostZoneManager<LongEntityID, String> ghostManager = 
    new GhostZoneManager<>(forest, 5.0f); // 5-unit ghost zone width

// Ghost zones automatically synchronized
ghostManager.insertGhostEntity(sourceTreeId, targetTreeId, entityId);
ghostManager.updateGhostZones(); // Sync all ghost zones

// Query including ghost zones
List<LongEntityID> withGhosts = ghostManager.queryWithGhostZones(
    treeId, position, radius);

```text

## Configuration

### ForestConfig

Configuration object for forest behavior and policies.

```java

ForestConfig config = new ForestConfig()
    .withOverlappingTrees(false)           // No tree overlap
    .withGhostZones(true, 10.0f)          // 10-unit ghost zones
    .withDefaultAssignmentStrategy(AssignmentStrategy.SPATIAL_BOUNDS)
    .withLoadBalancing(true)               // Enable load balancing
    .withBackgroundManagement(true, 30000) // Background ops every 30s
    .build();

Forest<MortonKey, LongEntityID, String> forest = new Forest<>(config);

```text

## Performance Considerations

### Concurrent Operations

All forest operations are thread-safe with optimized concurrent access:

```java

// Thread-safe forest operations
ExecutorService executor = Executors.newFixedThreadPool(8);

List<CompletableFuture<Void>> futures = IntStream.range(0, 1000)
    .mapToObj(i -> CompletableFuture.runAsync(() -> {
        forest.insertEntity(generateId(), generatePosition(), generateContent());
    }, executor))
    .collect(Collectors.toList());

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

```text

### Memory Efficiency

Forest implementations use optimized data structures:

- `ConcurrentSkipListMap` for tree collections
- `CopyOnWriteArrayList` for entity lists
- Object pooling for temporary objects
- Lazy evaluation for large-scale operations

### Query Optimization

Cross-tree queries are automatically parallelized:

```java

// Parallel execution across trees (default: available processors)
forest.setParallelism(Runtime.getRuntime().availableProcessors());

// Queries automatically use parallel streams for multi-tree operations
List<LongEntityID> results = forest.findKNearestNeighbors(position, 10);

```text

## Best Practices

### 1. Tree Sizing

- Keep individual trees between 100-10,000 entities for optimal performance
- Use adaptive forests for dynamic workloads
- Consider hierarchical forests for LOD scenarios

### 2. Ghost Zone Configuration

- Set ghost zone width to 2-3x your typical query radius
- Enable ghost zones for seamless cross-tree operations
- Monitor ghost zone overhead in memory-constrained environments

### 3. Load Balancing

- Enable automatic load balancing for dynamic workloads
- Monitor tree load metrics regularly
- Consider manual rebalancing for predictable load patterns

### 4. Assignment Strategies

- Use `SPATIAL_BOUNDS` for spatially coherent data
- Use `LOAD_BALANCED` for uniform distribution
- Use `ROUND_ROBIN` for simple even distribution

## Error Handling

Forest operations include comprehensive error handling:

```java

try {
    forest.insertEntity(entityId, position, content);
} catch (TreeNotFoundException e) {
    // Handle missing tree
} catch (EntityAlreadyExistsException e) {
    // Handle duplicate entity
} catch (ForestOperationException e) {
    // Handle general forest errors
}

```text

## Integration with Single Trees

Forest APIs are compatible with single tree operations:

```java

// Get specific tree for direct operations
AbstractSpatialIndex<MortonKey, LongEntityID, String> tree = forest.getTree(treeId);

// Use tree directly when needed
tree.insert(entityId, position, level, content);

```text

This provides flexibility to use forest-wide operations or direct tree access as needed.
