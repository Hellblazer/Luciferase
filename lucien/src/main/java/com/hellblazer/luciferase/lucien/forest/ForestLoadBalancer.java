package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Handles load balancing across trees in a spatial forest.
 * Monitors tree load metrics and performs entity migrations to maintain balance.
 *
 * <p><b>Relationship to {@link com.hellblazer.luciferase.lucien.balancing.ShapeWeightPartitioner}
 * (Luciferase-fhc9).</b> {@code ShapeWeightPartitioner} computes the <em>initial</em> contiguous,
 * shape-weighted SFC partition of trees across ranks (a one-shot, weight-balanced assignment). This balancer
 * handles <em>runtime drift</em>: as entities accumulate unevenly it migrates entities between trees to
 * restore balance. To stay consistent with the partitioner's contiguous-SFC intent, migration moves an
 * SFC-contiguous block (see {@code selectEntitiesToMigrate}) rather than a scattered random sample, and
 * preserves each entity's refinement level. Ghost layers are rebuilt after a migration batch via the
 * caller-supplied hook on {@link #executeMigration(MigrationPlan, Map, java.util.function.BiConsumer, Runnable)}.
 *
 * @param <Key>     The type of spatial key used by the trees
 * @param <ID>      The type of entity identifier
 * @param <Content> The type of entity content
 */
public class ForestLoadBalancer<Key extends SpatialKey<Key>, ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> {
    private static final Logger log = LoggerFactory.getLogger(ForestLoadBalancer.class);

    /**
     * Load metrics for a single tree
     */
    public static class TreeLoadMetrics {
        private final AtomicInteger entityCount = new AtomicInteger(0);
        private final AtomicLong queryCount = new AtomicLong(0);
        private final AtomicLong totalQueryTimeNanos = new AtomicLong(0);
        private final AtomicLong memoryUsageBytes = new AtomicLong(0);
        private final long timestamp = System.currentTimeMillis();

        public int getEntityCount() {
            return entityCount.get();
        }

        public long getQueryCount() {
            return queryCount.get();
        }

        public long getTotalQueryTimeNanos() {
            return totalQueryTimeNanos.get();
        }

        public long getMemoryUsageBytes() {
            return memoryUsageBytes.get();
        }

        public double getAverageQueryTimeNanos() {
            var count = queryCount.get();
            return count > 0 ? (double) totalQueryTimeNanos.get() / count : 0;
        }

        public double getQueryRate() {
            var ageSeconds = (System.currentTimeMillis() - timestamp) / 1000.0;
            return ageSeconds > 0 ? queryCount.get() / ageSeconds : 0;
        }

        public void updateEntityCount(int count) {
            entityCount.set(count);
        }

        public void recordQuery(long timeNanos) {
            queryCount.incrementAndGet();
            totalQueryTimeNanos.addAndGet(timeNanos);
        }

        public void updateMemoryUsage(long bytes) {
            memoryUsageBytes.set(bytes);
        }

        public double getLoadScore(LoadBalancingStrategy strategy) {
            return switch (strategy) {
                case ENTITY_COUNT -> entityCount.get();
                case QUERY_RATE -> getQueryRate();
                case MEMORY_USAGE -> memoryUsageBytes.get();
                case COMPOSITE -> entityCount.get() * 0.4 + getQueryRate() * 0.4 + memoryUsageBytes.get() / 1_000_000.0 * 0.2;
            };
        }
    }

    /**
     * Load balancing strategies
     */
    public enum LoadBalancingStrategy {
        ENTITY_COUNT,    // Balance based on entity count
        QUERY_RATE,      // Balance based on query rate
        MEMORY_USAGE,    // Balance based on memory usage
        COMPOSITE        // Balance using weighted combination
    }

    /**
     * Configuration for load balancing thresholds
     */
    public static class LoadBalancerConfig {
        private double overloadThreshold = 1.5;      // Tree is overloaded if load > avg * threshold
        private double underloadThreshold = 0.5;     // Tree is underloaded if load < avg * threshold
        private int minEntitiesForMigration = 100;   // Minimum entities to consider migration
        private int maxMigrationBatchSize = 1000;    // Maximum entities to migrate in one batch
        private double targetLoadRatio = 1.0;        // Target load ratio after migration
        private LoadBalancingStrategy strategy = LoadBalancingStrategy.COMPOSITE;

        public double getOverloadThreshold() {
            return overloadThreshold;
        }

        public void setOverloadThreshold(double overloadThreshold) {
            this.overloadThreshold = overloadThreshold;
        }

        public double getUnderloadThreshold() {
            return underloadThreshold;
        }

        public void setUnderloadThreshold(double underloadThreshold) {
            this.underloadThreshold = underloadThreshold;
        }

        public int getMinEntitiesForMigration() {
            return minEntitiesForMigration;
        }

        public void setMinEntitiesForMigration(int minEntitiesForMigration) {
            this.minEntitiesForMigration = minEntitiesForMigration;
        }

        public int getMaxMigrationBatchSize() {
            return maxMigrationBatchSize;
        }

        public void setMaxMigrationBatchSize(int maxMigrationBatchSize) {
            this.maxMigrationBatchSize = maxMigrationBatchSize;
        }

        public double getTargetLoadRatio() {
            return targetLoadRatio;
        }

        public void setTargetLoadRatio(double targetLoadRatio) {
            this.targetLoadRatio = targetLoadRatio;
        }

        public LoadBalancingStrategy getStrategy() {
            return strategy;
        }

        public void setStrategy(LoadBalancingStrategy strategy) {
            this.strategy = strategy;
        }
    }

    /**
     * Migration plan for moving entities between trees
     */
    public static class MigrationPlan<ID> {
        private final int sourceTreeId;
        private final int targetTreeId;
        private final Set<ID> entityIds;
        private final double expectedLoadReduction;

        public MigrationPlan(int sourceTreeId, int targetTreeId, Set<ID> entityIds, double expectedLoadReduction) {
            this.sourceTreeId = sourceTreeId;
            this.targetTreeId = targetTreeId;
            this.entityIds = new HashSet<>(entityIds);
            this.expectedLoadReduction = expectedLoadReduction;
        }

        public int getSourceTreeId() {
            return sourceTreeId;
        }

        public int getTargetTreeId() {
            return targetTreeId;
        }

        public Set<ID> getEntityIds() {
            return Collections.unmodifiableSet(entityIds);
        }

        public double getExpectedLoadReduction() {
            return expectedLoadReduction;
        }
    }

    private final Map<Integer, TreeLoadMetrics> treeMetrics = new ConcurrentHashMap<>();
    private final LoadBalancerConfig config;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public ForestLoadBalancer() {
        this(new LoadBalancerConfig());
    }

    public ForestLoadBalancer(LoadBalancerConfig config) {
        this.config = config;
    }

    /**
     * Collect load metrics from all trees
     */
    public void collectMetrics(Map<Integer, SpatialIndex<Key, ID, Content>> trees) {
        lock.writeLock().lock();
        try {
            trees.forEach((treeId, tree) -> {
                var metrics = treeMetrics.computeIfAbsent(treeId, k -> new TreeLoadMetrics());
                metrics.updateEntityCount(tree.entityCount());
                // Memory usage estimation (simplified - in practice would use more sophisticated measurement)
                metrics.updateMemoryUsage(tree.entityCount() * 1024L); // Rough estimate
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Record a query for a specific tree
     */
    public void recordQuery(int treeId, long queryTimeNanos) {
        lock.readLock().lock();
        try {
            var metrics = treeMetrics.get(treeId);
            if (metrics != null) {
                metrics.recordQuery(queryTimeNanos);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get current metrics for a tree
     */
    public TreeLoadMetrics getMetrics(int treeId) {
        lock.readLock().lock();
        try {
            return treeMetrics.get(treeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Identify overloaded trees based on current metrics
     */
    public List<Integer> identifyOverloadedTrees() {
        lock.readLock().lock();
        try {
            var avgLoad = calculateAverageLoad();
            var threshold = avgLoad * config.getOverloadThreshold();
            
            return treeMetrics.entrySet().stream()
                .filter(entry -> entry.getValue().getLoadScore(config.getStrategy()) > threshold)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Identify underloaded trees based on current metrics
     */
    public List<Integer> identifyUnderloadedTrees() {
        lock.readLock().lock();
        try {
            var avgLoad = calculateAverageLoad();
            var threshold = avgLoad * config.getUnderloadThreshold();
            
            return treeMetrics.entrySet().stream()
                .filter(entry -> entry.getValue().getLoadScore(config.getStrategy()) < threshold)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Create migration plans to balance load across trees
     */
    public List<MigrationPlan<ID>> createMigrationPlans(Map<Integer, SpatialIndex<Key, ID, Content>> trees) {
        lock.readLock().lock();
        try {
            var overloaded = identifyOverloadedTrees();
            var underloaded = identifyUnderloadedTrees();
            
            if (overloaded.isEmpty() || underloaded.isEmpty()) {
                return Collections.emptyList();
            }
            
            var plans = new ArrayList<MigrationPlan<ID>>();
            var avgLoad = calculateAverageLoad();
            
            for (var sourceTreeId : overloaded) {
                var sourceTree = trees.get(sourceTreeId);
                var sourceMetrics = treeMetrics.get(sourceTreeId);
                
                if (sourceTree == null || sourceMetrics == null || 
                    sourceMetrics.getEntityCount() < config.getMinEntitiesForMigration()) {
                    continue;
                }
                
                // Find best target tree
                var targetTreeId = selectBestTargetTree(sourceTreeId, underloaded, avgLoad);
                if (targetTreeId == -1) {
                    continue;
                }
                
                // Select entities to migrate
                var entitiesToMigrate = selectEntitiesToMigrate(sourceTree, sourceMetrics, avgLoad);
                if (!entitiesToMigrate.isEmpty()) {
                    var expectedReduction = (double) entitiesToMigrate.size() / sourceMetrics.getEntityCount();
                    plans.add(new MigrationPlan<>(sourceTreeId, targetTreeId, entitiesToMigrate, expectedReduction));
                }
            }
            
            return plans;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Execute a migration plan
     */
    public void executeMigration(MigrationPlan<ID> plan,
                                Map<Integer, SpatialIndex<Key, ID, Content>> trees,
                                BiConsumer<ID, Point3f> entityPositionProvider) {
        executeMigration(plan, trees, entityPositionProvider, () -> { });
    }

    /**
     * Execute a migration, then run {@code ghostRebuildHook} once after the batch (Luciferase-fhc9).
     *
     * <p>Moving entities between trees invalidates both trees' ghost layers (a migrated entity's old and new
     * neighbors change), so the caller must rebuild ghosts afterward. This balancer does not own the ghost
     * subsystem, so the rebuild is supplied as a hook the orchestrator wires to its
     * {@code GhostCoordinator}/{@code DistributedGhostManager}. The hook runs only when at least one entity
     * actually moved.
     *
     * <p>Entities are re-inserted at their <em>source refinement level</em> (recovered from
     * {@link SpatialIndex#getEntityLocations}), not a hardcoded level 0 — preserving each entity's level
     * across the migration.
     *
     * @param plan                  the migration plan
     * @param trees                 the tree id → index map
     * @param entityPositionProvider supplies each entity's position
     * @param ghostRebuildHook      invoked once after a non-empty migration batch to rebuild stale ghosts
     */
    public void executeMigration(MigrationPlan<ID> plan,
                                Map<Integer, SpatialIndex<Key, ID, Content>> trees,
                                BiConsumer<ID, Point3f> entityPositionProvider,
                                Runnable ghostRebuildHook) {
        var sourceTree = trees.get(plan.getSourceTreeId());
        var targetTree = trees.get(plan.getTargetTreeId());

        if (sourceTree == null || targetTree == null) {
            log.warn("Cannot execute migration: source or target tree not found");
            return;
        }

        var migratedCount = 0;
        for (var entityId : plan.getEntityIds()) {
            try {
                var content = sourceTree.getEntity(entityId);
                if (content != null) {
                    var position = new Point3f();
                    entityPositionProvider.accept(entityId, position);

                    // Preserve the entity's refinement level across the migration (Luciferase-fhc9): re-insert
                    // at the source level rather than a hardcoded 0, which would coarsen every migrated entity.
                    var level = entityLevel(sourceTree, entityId);

                    sourceTree.removeEntity(entityId);
                    targetTree.insert(entityId, position, level, content);
                    migratedCount++;
                }
            } catch (Exception e) {
                log.error("Failed to migrate entity {}: {}", entityId, e.getMessage());
            }
        }

        log.info("Migrated {} entities from tree {} to tree {}",
                migratedCount, plan.getSourceTreeId(), plan.getTargetTreeId());

        // Ghosts on both trees are stale after entities moved; rebuild once for the batch (Luciferase-fhc9).
        if (migratedCount > 0) {
            ghostRebuildHook.run();
        }
    }

    /**
     * The refinement level at which {@code entityId} is stored in {@code tree} (Luciferase-fhc9). Uses the
     * coarsest (minimum) location level for a spanning entity; falls back to level 0 only if the entity has no
     * recorded location (logged), preserving the prior behaviour for that degenerate case.
     */
    private byte entityLevel(SpatialIndex<Key, ID, Content> tree, ID entityId) {
        byte level = Byte.MAX_VALUE;
        for (var key : tree.getEntityLocations(entityId)) {
            level = (byte) Math.min(level, key.getLevel());
        }
        if (level == Byte.MAX_VALUE) {
            log.warn("Entity {} has no recorded location; migrating at level 0", entityId);
            return 0;
        }
        return level;
    }

    /**
     * Calculate average load across all trees
     */
    private double calculateAverageLoad() {
        if (treeMetrics.isEmpty()) {
            return 0;
        }
        
        var totalLoad = treeMetrics.values().stream()
            .mapToDouble(m -> m.getLoadScore(config.getStrategy()))
            .sum();
        
        return totalLoad / treeMetrics.size();
    }

    /**
     * Select the best target tree for migration
     */
    private int selectBestTargetTree(int sourceTreeId, List<Integer> underloadedTrees, double avgLoad) {
        var targetLoad = avgLoad * config.getTargetLoadRatio();
        
        return underloadedTrees.stream()
            .filter(id -> id != sourceTreeId)
            .min(Comparator.comparingDouble(id -> 
                Math.abs(treeMetrics.get(id).getLoadScore(config.getStrategy()) - targetLoad)))
            .orElse(-1);
    }

    /**
     * Select entities to migrate from overloaded tree
     */
    private Set<ID> selectEntitiesToMigrate(SpatialIndex<Key, ID, Content> tree, 
                                           TreeLoadMetrics metrics, 
                                           double avgLoad) {
        var currentLoad = metrics.getLoadScore(config.getStrategy());
        var targetLoad = avgLoad * config.getTargetLoadRatio();
        var loadToReduce = currentLoad - targetLoad;
        
        if (loadToReduce <= 0) {
            return Collections.emptySet();
        }
        
        var entitiesToMigrate = (int) Math.min(
            config.getMaxMigrationBatchSize(),
            metrics.getEntityCount() * (loadToReduce / currentLoad)
        );
        
        // SFC-local selection (Luciferase-fhc9): migrate a CONTIGUOUS space-filling-curve block rather than a
        // random sample. Random selection (the prior Collections.shuffle) scatters the migrated entities across
        // the tree, destroying spatial locality and producing a fragmented, ghost-heavy partition. Sorting by
        // each entity's primary (coarsest) location key and taking the lowest-SFC run keeps the migrated set
        // spatially clustered, so it lands as a compact region in the target tree.
        return tree.getEntitiesWithPositions().keySet().stream()
            .map(id -> Map.entry(id, tree.getEntityLocations(id).stream().min(Comparator.naturalOrder())))
            .filter(e -> e.getValue().isPresent())
            .sorted(Comparator.comparing(e -> e.getValue().get()))
            .limit(entitiesToMigrate)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Get current configuration
     */
    public LoadBalancerConfig getConfig() {
        return config;
    }

    /**
     * Clear all metrics
     */
    public void clearMetrics() {
        lock.writeLock().lock();
        try {
            treeMetrics.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
}