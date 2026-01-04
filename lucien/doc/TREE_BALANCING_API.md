# Tree Balancing API Documentation

**Last Updated**: 2026-01-04
**Status**: Current

## Overview

The Tree Balancing API provides automated and manual tree optimization capabilities to maintain efficient spatial index
performance. This API helps prevent tree degradation due to entity movement, insertions, and deletions by redistributing
entities across nodes.

## Core Concepts

### TreeBalancingStrategy

Defines when and how the tree should be rebalanced:

```java

public interface TreeBalancingStrategy<ID extends EntityID> {
    // Thresholds for node operations
    int getSplitThreshold(int level, int maxEntitiesPerNode);
    int getMergeThreshold(int level, int maxEntitiesPerNode);
    
    // Decision methods
    boolean shouldSplit(int entityCount, int level, int maxEntitiesPerNode);
    boolean shouldMerge(int entityCount, int level, int[] siblingCounts);
    boolean shouldRebalanceTree(TreeBalancingStats stats);
    
    // Configuration
    long getMinRebalancingInterval();
    
    // Statistics
    record TreeBalancingStats(
        int totalNodes,
        int underpopulatedNodes,
        int overpopulatedNodes,
        int emptyNodes,
        int maxDepth,
        double averageLoad,
        double loadVariance
    )
}

```text

### TreeBalancer

Performs the actual rebalancing operations:

```java

public interface TreeBalancer<ID extends EntityID> {
    // Node operations
    BalancingAction checkNodeBalance(long nodeIndex);

    List<Long> splitNode(long nodeIndex, byte nodeLevel);

    boolean mergeNodes(Set<Long> nodeIndices, long parentIndex);

    // Tree operations
    RebalancingResult rebalanceTree();

    int rebalanceSubtree(long rootNodeIndex);

    // Configuration
    void setBalancingStrategy(TreeBalancingStrategy<ID> strategy);

    void setAutoBalancingEnabled(boolean enabled);

    boolean isAutoBalancingEnabled();

    // Results
    record RebalancingResult(int nodesCreated, int nodesRemoved, int nodesMerged, int nodesSplit, int entitiesRelocated,
                             long timeTaken, boolean success)

}

```text

## API Methods

### 1. Enable Auto-Balancing

```java

void setAutoBalancingEnabled(boolean enabled)

boolean isAutoBalancingEnabled()

```text

Enables automatic rebalancing after insert/remove operations.

**Example:**

```java

// Enable automatic balancing
spatialIndex.setAutoBalancingEnabled(true);

// Now insertions and removals will trigger rebalancing checks
spatialIndex.

insert(position, level, content);
// Automatic rebalancing may occur here

```text

### 2. Manual Rebalancing

```java

TreeBalancer.RebalancingResult rebalanceTree()

```text

Manually triggers a full tree rebalancing operation.

**Example:**

```java

// Perform manual rebalancing
TreeBalancer.RebalancingResult result = spatialIndex.rebalanceTree();

System.out.

println("Rebalancing complete:");
System.out.

println("  Nodes created: "+result.nodesCreated());
System.out.

println("  Nodes removed: "+result.nodesRemoved());
System.out.

println("  Entities relocated: "+result.entitiesRelocated());
System.out.

println("  Time taken: "+result.timeTaken() +"ms");

```text

### 3. Set Balancing Strategy

```java

void setBalancingStrategy(TreeBalancingStrategy<ID> strategy)

```text

Configures the balancing strategy used by the tree.

**Example:**

```java

// Use aggressive balancing for better query performance
spatialIndex.setBalancingStrategy(new AggressiveBalancingStrategy<>());

// Use conservative balancing for better insertion performance
spatialIndex.

setBalancingStrategy(new ConservativeBalancingStrategy<>());

```text

### 4. Get Balancing Statistics

```java

TreeBalancingStrategy.TreeBalancingStats getBalancingStats()

```text

Retrieves current tree balance statistics.

**Example:**

```java

TreeBalancingStrategy.TreeBalancingStats stats = spatialIndex.getBalancingStats();

if(stats.

loadVariance() >100){
System.out.

println("Tree is imbalanced, manual rebalancing recommended");
    spatialIndex.

rebalanceTree();
}

```text

## Built-in Strategies

### DefaultBalancingStrategy

Balanced approach suitable for most use cases:

```java

public class DefaultBalancingStrategy<ID extends EntityID> implements TreeBalancingStrategy<ID> {
    @Override
    public int getSplitThreshold(int level, int maxEntitiesPerNode) {
        // Split when node is 80% full
        return (int) (maxEntitiesPerNode * 0.8);
    }
    
    @Override
    public int getMergeThreshold(int level, int maxEntitiesPerNode) {
        // Merge when node is less than 20% full
        return (int) (maxEntitiesPerNode * 0.2);
    }
    
    @Override
    public boolean shouldRebalanceTree(TreeBalancingStats stats) {
        // Rebalance if variance is high or many empty nodes
        return stats.loadVariance() > 50 || 
               stats.emptyNodes() > stats.totalNodes() * 0.3;
    }
    
    @Override
    public long getMinRebalancingInterval() {
        return 5000; // 5 seconds between auto-rebalances
    }
}

```text

### AggressiveBalancingStrategy

Maintains tight balance for optimal query performance:

```java

public class AggressiveBalancingStrategy<ID extends EntityID> extends DefaultBalancingStrategy<ID> {
    @Override
    public int getSplitThreshold(int level, int maxEntitiesPerNode) {
        // Split earlier for better distribution
        return (int) (maxEntitiesPerNode * 0.6);
    }
    
    @Override
    public boolean shouldRebalanceTree(TreeBalancingStats stats) {
        // More aggressive rebalancing
        return stats.loadVariance() > 25 || 
               stats.overpopulatedNodes() > 0 ||
               stats.underpopulatedNodes() > stats.totalNodes() * 0.1;
    }
    
    @Override
    public long getMinRebalancingInterval() {
        return 1000; // More frequent checks
    }
}

```text

### ConservativeBalancingStrategy

Minimizes rebalancing overhead:

```java

public class ConservativeBalancingStrategy<ID extends EntityID> extends DefaultBalancingStrategy<ID> {
    @Override
    public int getSplitThreshold(int level, int maxEntitiesPerNode) {
        // Only split when absolutely necessary
        return maxEntitiesPerNode - 1;
    }
    
    @Override
    public int getMergeThreshold(int level, int maxEntitiesPerNode) {
        // Only merge nearly empty nodes
        return Math.min(2, maxEntitiesPerNode / 10);
    }
    
    @Override
    public boolean shouldRebalanceTree(TreeBalancingStats stats) {
        // Only rebalance in extreme cases
        return stats.loadVariance() > 200 || 
               stats.emptyNodes() > stats.totalNodes() * 0.5;
    }
}

```text

## Custom Strategies

### Level-Aware Strategy

```java

public class LevelAwareStrategy<ID extends EntityID> implements TreeBalancingStrategy<ID> {
    @Override
    public int getSplitThreshold(int level, int maxEntitiesPerNode) {
        // Higher levels (coarser) can hold more entities
        if (level < 5) {
            return maxEntitiesPerNode;
        } else if (level < 10) {
            return (int) (maxEntitiesPerNode * 0.8);
        } else {
            return (int) (maxEntitiesPerNode * 0.6);
        }
    }

    @Override
    public boolean shouldMerge(int entityCount, int level, int[] siblingCounts) {
        // Consider sibling loads for merging decision
        int totalSiblingEntities = Arrays.stream(siblingCounts).sum();
        int avgSiblingLoad = siblingCounts.length > 0 ? totalSiblingEntities / siblingCounts.length : 0;

        // Merge if this node and siblings are all underpopulated
        return entityCount < getMergeThreshold(level, maxEntitiesPerNode) && avgSiblingLoad < getMergeThreshold(level,
                                                                                                                maxEntitiesPerNode);
    }
}

```text

### Time-Based Strategy

```java

public class TimeBasedStrategy<ID extends EntityID> implements TreeBalancingStrategy<ID> {
    private final Map<Integer, Long> lastBalanceTime = new HashMap<>();
    private final long[] rebalanceIntervals = {
        60000,    // Level 0-5: 1 minute
        300000,   // Level 6-10: 5 minutes
        900000,   // Level 11-15: 15 minutes
        3600000   // Level 16+: 1 hour
    };
    
    @Override
    public boolean shouldRebalanceTree(TreeBalancingStats stats) {
        long currentTime = System.currentTimeMillis();
        
        // Check each level independently
        for (int level = 0; level <= stats.maxDepth(); level++) {
            long lastBalance = lastBalanceTime.getOrDefault(level, 0L);
            long interval = getIntervalForLevel(level);
            
            if (currentTime - lastBalance > interval) {
                lastBalanceTime.put(level, currentTime);
                return true;
            }
        }
        
        return false;
    }
    
    private long getIntervalForLevel(int level) {
        int index = Math.min(level / 5, rebalanceIntervals.length - 1);
        return rebalanceIntervals[index];
    }
}

```text

## Balancing Operations

### Node Splitting

When a node exceeds the split threshold:

1. Entities are grouped by their child node indices
2. Child nodes are created for each group
3. Entities are moved to appropriate children
4. Parent node may retain some entities

### Node Merging

When nodes fall below merge threshold:

1. Sibling nodes are identified
2. All entities from siblings are collected
3. Entities are consolidated into parent or single node
4. Empty nodes are removed

### Tree Rebalancing

Full tree rebalancing process:

1. Tree statistics are collected
2. Each node is evaluated for balance
3. Split/merge operations are performed
4. Entity redistributions are optimized
5. Empty nodes are cleaned up

## Performance Considerations

### Auto-Balancing Impact

```java

public class BalancingMetrics {
    private long totalRebalanceTime = 0;
    private int  rebalanceCount     = 0;

    public void monitorBalancing(SpatialIndex<ID, Content> index) {
        index.setBalancingStrategy(new MetricsStrategy<>(index.getBalancingStrategy()));
    }

    class MetricsStrategy<ID extends EntityID> implements TreeBalancingStrategy<ID> {
        private final TreeBalancingStrategy<ID> delegate;

        @Override
        public boolean shouldRebalanceTree(TreeBalancingStats stats) {
            long start = System.nanoTime();
            boolean result = delegate.shouldRebalanceTree(stats);

            if (result) {
                totalRebalanceTime += System.nanoTime() - start;
                rebalanceCount++;
            }

            return result;
        }
    }
}

```text

### Batch Operations

```java

public class BatchBalancing {
    public void performBatchInsert(List<EntityData> entities) {
        // Disable auto-balancing during batch insert
        boolean wasEnabled = spatialIndex.isAutoBalancingEnabled();
        spatialIndex.setAutoBalancingEnabled(false);

        try {
            // Insert all entities
            for (EntityData data : entities) {
                spatialIndex.insert(data.position, data.level, data.content);
            }

            // Single rebalance at the end
            spatialIndex.rebalanceTree();
        } finally {
            // Restore auto-balancing state
            spatialIndex.setAutoBalancingEnabled(wasEnabled);
        }
    }
}

```text

## Best Practices

1. **Strategy Selection**: Choose strategy based on workload:
    - Static scenes: Conservative strategy
    - Dynamic scenes: Default or aggressive strategy
    - Mixed workloads: Adaptive strategy

2. **Monitoring**: Regularly check balance statistics to tune strategy

3. **Batch Operations**: Disable auto-balancing for bulk operations

4. **Level Awareness**: Consider different thresholds for different tree levels

5. **Timing**: Avoid rebalancing during performance-critical operations

## Integration Example

```java

public class AdaptiveBalancingSystem {
    private final SpatialIndex<LongEntityID, GameObject> spatialIndex;
    private final BalancingMetrics                       metrics = new BalancingMetrics();

    public void initialize() {
        // Create adaptive strategy
        TreeBalancingStrategy<LongEntityID> strategy = new TreeBalancingStrategy<>() {
            private double targetVariance = 50.0;

            @Override
            public boolean shouldRebalanceTree(TreeBalancingStats stats) {
                // Adapt based on current performance
                if (metrics.getAverageQueryTime() > 10.0) {
                    // Queries are slow, be more aggressive
                    targetVariance = Math.max(25.0, targetVariance * 0.9);
                } else if (metrics.getAverageInsertTime() > 5.0) {
                    // Inserts are slow, be less aggressive
                    targetVariance = Math.min(100.0, targetVariance * 1.1);
                }

                return stats.loadVariance() > targetVariance;
            }

            @Override
            public int getSplitThreshold(int level, int maxEntitiesPerNode) {
                // Adjust based on tree depth
                double utilization = 0.8 - (level * 0.02);
                return (int) (maxEntitiesPerNode * Math.max(0.5, utilization));
            }
        };

        spatialIndex.setBalancingStrategy(strategy);
        spatialIndex.setAutoBalancingEnabled(true);
    }

    public void performMaintenance() {
        TreeBalancingStats stats = spatialIndex.getBalancingStats();

        System.out.println("Tree Health Report:");
        System.out.println("  Average load: " + stats.averageLoad());
        System.out.println("  Load variance: " + stats.loadVariance());
        System.out.println("  Empty nodes: " + stats.emptyNodes() + "/" + stats.totalNodes());

        if (stats.loadVariance() > 100) {
            System.out.println("Performing maintenance rebalancing...");
            TreeBalancer.RebalancingResult result = spatialIndex.rebalanceTree();
            System.out.println(
            "Rebalancing improved variance by " + (stats.loadVariance() - spatialIndex.getBalancingStats()
                                                                                      .loadVariance()));
        }
    }
}

```text
