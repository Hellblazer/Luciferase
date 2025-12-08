# Tree Traversal API Documentation

**Last Updated**: 2025-12-08
**Status**: Current

## Overview

The Tree Traversal API provides flexible and efficient methods for visiting nodes and entities in spatial tree
structures. This API supports multiple traversal strategies and is designed for tasks like spatial analysis, debugging,
serialization, and tree visualization.

## Core Concepts

### TreeVisitor

The `TreeVisitor` interface defines callbacks for tree traversal events:

```java
public interface TreeVisitor<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    // Called at start of traversal
    void beginTraversal(int totalNodes, int totalEntities);
    
    // Called for each node (return false to skip children)
    boolean visitNode(SpatialNode<Key, ID> node, int level, Key parentIndex);
    
    // Called for each entity in a node
    void visitEntity(ID entityId, Content content, Key nodeIndex, int level);
    
    // Called when leaving a node
    void leaveNode(SpatialNode<Key, ID> node, int level, int childCount);
    
    // Called at end of traversal
    void endTraversal(int nodesVisited, int entitiesVisited);
    
    // Configuration
    boolean shouldVisitEntities();
    int getMaxDepth();
}
```

### TraversalStrategy

Supported traversal strategies:

- **DEPTH_FIRST**: Visit nodes depth-first (pre-order by default)
- **BREADTH_FIRST**: Visit all nodes at each level before descending
- **PRE_ORDER**: Process node before its children
- **POST_ORDER**: Process node after its children
- **IN_ORDER**: Process node between children (treated as pre-order for spatial trees)
- **LEVEL_ORDER**: Similar to breadth-first, level by level

### TraversalContext

Internal context tracking traversal state:

- Visited nodes to prevent cycles
- Queue for breadth-first traversal
- Cancellation support
- Statistics collection

## API Methods

### 1. Full Tree Traversal

```java
void traverse(TreeVisitor<ID, Content> visitor, TraversalStrategy strategy)
```

Traverses the entire spatial tree using the specified strategy.

**Example:**

```java
spatialIndex.traverse(new TreeVisitor<Key, LongEntityID, String>() {
    @Override
    public void beginTraversal(int totalNodes, int totalEntities) {
        System.out.println("Starting traversal: " + totalNodes + " nodes");
    }

    @Override
    public boolean visitNode(SpatialNode<Key, LongEntityID> node, int level, Key parentIndex) {
        System.out.println("Node at level " + level + ": " + node.getEntityIds().size() + " entities");
        return true; // Continue to children
    }

    @Override public void visitEntity (LongEntityID id, String content,long nodeIndex, int level){
        System.out.println("  Entity: " + id + " - " + content);
    }

    @Override public void endTraversal ( int nodesVisited, int entitiesVisited){
        System.out.println("Traversed " + nodesVisited + " nodes, " + entitiesVisited + " entities");
    }
},TraversalStrategy.DEPTH_FIRST);
```

### 2. Subtree Traversal

```java
void traverseFrom(TreeVisitor<ID, Content> visitor, TraversalStrategy strategy, long startNodeIndex)
```

Traverses a subtree starting from a specific node.

**Example:**

```java
// Start from a specific spatial index
long nodeIndex = 0x1234567890L;
spatialIndex.

traverseFrom(visitor, TraversalStrategy.BREADTH_FIRST, nodeIndex);
```

### 3. Region Traversal

```java
void traverseRegion(TreeVisitor<ID, Content> visitor, Spatial region, TraversalStrategy strategy)
```

Traverses only nodes that intersect with the specified region.

**Example:**

```java
Spatial.Sphere region = new Spatial.Sphere(100, 100, 100, 50); // Center (100,100,100), radius 50
spatialIndex.

traverseRegion(visitor, region, TraversalStrategy.DEPTH_FIRST);
```

## Visitor Patterns

### Statistics Collector

```java
public class StatisticsVisitor implements TreeVisitor<ID, Content> {
    private       int                   maxDepth         = 0;
    private       int                   totalNodes       = 0;
    private       int                   leafNodes        = 0;
    private       Map<Integer, Integer> nodesPerLevel    = new HashMap<>();
    private final Map<Integer, Integer> entitiesPerLevel = new HashMap<>();

    @Override
    public boolean visitNode(SpatialNode<ID> node, int level, long parentIndex) {
        totalNodes++;
        maxDepth = Math.max(maxDepth, level);
        nodesPerLevel.merge(level, 1, Integer::sum);
        return true;
    }

    @Override
    public void visitEntity(ID entityId, Content content, long nodeIndex, int level) {
        entitiesPerLevel.merge(level, 1, Integer::sum);
    }

    @Override
    public void leaveNode(SpatialNode<ID> node, int level, int childCount) {
        if (childCount == 0) {
            leafNodes++;
        }
    }

    public void printStatistics() {
        System.out.println("Tree Statistics:");
        System.out.println("  Max depth: " + maxDepth);
        System.out.println("  Total nodes: " + totalNodes);
        System.out.println("  Leaf nodes: " + leafNodes);
        System.out.println("  Nodes per level: " + nodesPerLevel);
        System.out.println("  Entities per level: " + entitiesPerLevel);
    }
}
```

### Tree Serializer

```java
public class TreeSerializer implements TreeVisitor<ID, Content> {
    private final JsonWriter         writer;
    private final Stack<JsonContext> contextStack = new Stack<>();

    @Override
    public void beginTraversal(int totalNodes, int totalEntities) {
        writer.beginObject().name("totalNodes").value(totalNodes).name("totalEntities").value(totalEntities).name(
        "root");
    }

    @Override
    public boolean visitNode(SpatialNode<ID> node, int level, long parentIndex) {
        writer.beginObject()
              .name("index")
              .value(node.getSpatialIndex())
              .name("level")
              .value(level)
              .name("entityCount")
              .value(node.getEntityIds().size());

        if (shouldVisitEntities() && !node.getEntityIds().isEmpty()) {
            writer.name("entities").beginArray();
        }

        contextStack.push(new JsonContext(node));
        return true;
    }

    @Override
    public void visitEntity(ID entityId, Content content, long nodeIndex, int level) {
        writer.beginObject()
              .name("id")
              .value(entityId.toString())
              .name("content")
              .value(content.toString())
              .endObject();
    }

    @Override
    public void leaveNode(SpatialNode<ID> node, int level, int childCount) {
        JsonContext context = contextStack.pop();

        if (context.hasEntities) {
            writer.endArray(); // entities
        }

        if (childCount > 0) {
            writer.name("children").beginArray();
            // Children were already written
            writer.endArray();
        }

        writer.endObject(); // node
    }
}
```

### Tree Validator

```java
public class TreeValidator implements TreeVisitor<ID, Content> {
    private final Set<ID>                   seenEntities = new HashSet<>();
    private final List<String>              errors       = new ArrayList<>();
    private final SpatialIndex<ID, Content> index;

    @Override
    public boolean visitNode(SpatialNode<ID> node, int level, long parentIndex) {
        // Validate node properties
        if (level > index.getMaxDepth()) {
            errors.add("Node exceeds max depth: " + node.getSpatialIndex());
        }

        // Check for duplicate nodes
        if (!visitedNodes.add(node.getSpatialIndex())) {
            errors.add("Duplicate node found: " + node.getSpatialIndex());
        }

        return true;
    }

    @Override
    public void visitEntity(ID entityId, Content content, long nodeIndex, int level) {
        // Check for duplicate entities across nodes
        if (!seenEntities.add(entityId)) {
            errors.add("Entity appears in multiple nodes: " + entityId);
        }

        // Validate entity exists in entity manager
        if (index.getEntity(entityId) == null) {
            errors.add("Entity in tree but not in entity manager: " + entityId);
        }
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
```

## Advanced Usage

### Conditional Traversal

```java
public class ConditionalVisitor implements TreeVisitor<ID, Content> {
    private final Predicate<SpatialNode<ID>> nodePredicate;
    private final Predicate<Content> entityPredicate;
    
    @Override
    public boolean visitNode(SpatialNode<ID> node, int level, long parentIndex) {
        // Only continue if node matches predicate
        return nodePredicate.test(node);
    }
    
    @Override
    public void visitEntity(ID entityId, Content content, long nodeIndex, int level) {
        if (entityPredicate.test(content)) {
            processEntity(entityId, content);
        }
    }
}
```

### Performance Analysis

```java
public class PerformanceAnalyzer implements TreeVisitor<ID, Content> {
    private final Map<Long, NodeMetrics> nodeMetrics = new HashMap<>();

    @Override
    public boolean visitNode(SpatialNode<ID> node, int level, long parentIndex) {
        long startTime = System.nanoTime();

        NodeMetrics metrics = new NodeMetrics();
        metrics.level = level;
        metrics.entityCount = node.getEntityIds().size();
        metrics.spatialIndex = node.getSpatialIndex();

        // Calculate node volume/size
        metrics.volume = calculateNodeVolume(node.getSpatialIndex());

        // Store metrics
        nodeMetrics.put(node.getSpatialIndex(), metrics);

        metrics.visitTime = System.nanoTime() - startTime;
        return true;
    }

    public void analyzeBalance() {
        // Analyze entity distribution
        Map<Integer, List<NodeMetrics>> byLevel = nodeMetrics.values().stream().collect(
        Collectors.groupingBy(m -> m.level));

        for (Map.Entry<Integer, List<NodeMetrics>> entry : byLevel.entrySet()) {
            double avgEntities = entry.getValue().stream().mapToInt(m -> m.entityCount).average().orElse(0);

            double variance = calculateVariance(entry.getValue(), avgEntities);

            System.out.println(
            "Level " + entry.getKey() + ": avg entities = " + avgEntities + ", variance = " + variance);
        }
    }
}
```

### Parallel Traversal

```java
public class ParallelVisitor implements TreeVisitor<ID, Content> {
    private final ForkJoinPool                                           pool    = new ForkJoinPool();
    private final ConcurrentHashMap<Long, CompletableFuture<NodeResult>> futures = new ConcurrentHashMap<>();

    @Override
    public boolean visitNode(SpatialNode<ID> node, int level, long parentIndex) {
        // Submit node processing to thread pool
        CompletableFuture<NodeResult> future = CompletableFuture.supplyAsync(() -> {
            return processNode(node, level);
        }, pool);

        futures.put(node.getSpatialIndex(), future);
        return true; // Continue traversal
    }

    @Override
    public void endTraversal(int nodesVisited, int entitiesVisited) {
        // Wait for all processing to complete
        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();

        // Aggregate results
        aggregateResults();
    }
}
```

## Best Practices

1. **Early Termination**: Return false from visitNode to skip subtrees
2. **Memory Efficiency**: Process data during traversal rather than collecting everything
3. **Traversal Strategy**: Choose strategy based on use case:
    - DEPTH_FIRST for most operations
    - BREADTH_FIRST for level-based analysis
    - POST_ORDER for bottom-up aggregation
4. **Entity Filtering**: Use shouldVisitEntities() to skip entity visits when not needed
5. **Max Depth**: Set getMaxDepth() to limit traversal depth

## Thread Safety

Tree traversal is thread-safe for concurrent reads. The traversal holds a read lock during the entire operation.
Multiple traversals can run concurrently.

## Integration Example

```java
public class SpatialDebugger {
    private final SpatialIndex<LongEntityID, GameObject> spatialIndex;

    public void visualizeTree(Graphics3D graphics) {
        spatialIndex.traverse(new TreeVisitor<LongEntityID, GameObject>() {
            private final Stack<BoundingBox> parentBounds = new Stack<>();

            @Override
            public boolean visitNode(SpatialNode<LongEntityID> node, int level, long parentIndex) {
                // Calculate and draw node bounds
                BoundingBox bounds = calculateNodeBounds(node.getSpatialIndex());

                Color color = getColorForLevel(level);
                graphics.drawWireframeBox(bounds, color);

                // Draw connection to parent
                if (!parentBounds.isEmpty()) {
                    graphics.drawLine(parentBounds.peek().getCenter(), bounds.getCenter(), color);
                }

                parentBounds.push(bounds);
                return true;
            }

            @Override
            public void visitEntity(LongEntityID id, GameObject obj, long nodeIndex, int level) {
                // Draw entity in context of its node
                graphics.drawEntity(obj, getColorForLevel(level));
            }

            @Override
            public void leaveNode(SpatialNode<LongEntityID> node, int level, int childCount) {
                parentBounds.pop();
            }
        }, TraversalStrategy.DEPTH_FIRST);
    }
}
```
