/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stack-based tree builder for efficient bulk construction of spatial indices. Uses iterative depth-first construction
 * to avoid recursion overhead and improve cache locality.
 *
 * Key features: - Iterative construction avoiding stack overflow on deep trees - Pre-sorted entity processing for
 * better cache locality - Efficient node allocation and reuse - Support for both bottom-up and top-down construction -
 * Progress tracking for large builds
 *
 * @param <ID>       The type of EntityID used
 * @param <Content>  The type of content stored
 * @author hal.hildebrand
 */
public class StackBasedTreeBuilder<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger           log               = LoggerFactory.getLogger(StackBasedTreeBuilder.class);
    // Configuration
    private final        BuildConfig      config;
    // Statistics
    private final        AtomicInteger    nodesCreated      = new AtomicInteger(0);
    private final        AtomicInteger    entitiesProcessed = new AtomicInteger(0);
    private final        List<ID>         insertedIds       = Collections.synchronizedList(new ArrayList<>());
    private              ProgressListener progressListener;
    private              int              maxDepthReached   = 0;

    public StackBasedTreeBuilder(BuildConfig config) {
        this.config = config;
    }

    public static BuildConfig defaultConfig() {
        return new BuildConfig();
    }

    public static BuildConfig highPerformanceConfig() {
        return new BuildConfig().withStrategy(BuildStrategy.TOP_DOWN)
                                .withPreSortEntities(true)
                                .withNodePool(true)
                                .withTrackInsertedIds(false)  // Disable for performance tests
                                .withMaxStackDepth(50000)  // High limit for very large datasets
                                .withAdaptiveSubdivision(true);  // Enable adaptive subdivision for better performance
    }

    public static BuildConfig memoryEfficientConfig() {
        return new BuildConfig().withStrategy(BuildStrategy.TOP_DOWN).withMaxStackDepth(500).withTrackInsertedIds(
                                false)  // Disable for memory efficiency
                                .withNodePool(true);
    }

    /**
     * Build tree using stack-based approach
     */
    public BuildResult buildTree(AbstractSpatialIndex<Key, ID, Content> index, List<Point3f> positions,
                                 List<Content> contents, byte startLevel) {
        var startTime = System.currentTimeMillis();
        var phaseTimes = new HashMap<String, Long>();

        // Clear previous build state
        insertedIds.clear();
        nodesCreated.set(0);
        entitiesProcessed.set(0);
        maxDepthReached = 0;

        // Phase 1: Prepare entities
        var phaseStart = System.currentTimeMillis();
        var mortonEntities = prepareEntities(index, positions, contents, startLevel);
        phaseTimes.put("preparation", System.currentTimeMillis() - phaseStart);

        // Debug output for large builds
        if (positions.size() > 1000) {
            log.debug("StackBasedTreeBuilder: prepared {} morton entities from {} positions", mortonEntities.size(),
                      positions.size());
        }

        // Phase 2: Build tree based on strategy
        phaseStart = System.currentTimeMillis();
        switch (config.getStrategy()) {
            case TOP_DOWN:
                buildTopDown(index, mortonEntities, startLevel);
                break;
            case BOTTOM_UP:
                buildBottomUp(index, mortonEntities, startLevel);
                break;
            case HYBRID:
                buildHybrid(index, mortonEntities, startLevel);
                break;
        }
        phaseTimes.put("construction", System.currentTimeMillis() - phaseStart);

        var totalTime = System.currentTimeMillis() - startTime;

        return new BuildResult<>(nodesCreated.get(), entitiesProcessed.get(), totalTime, maxDepthReached, phaseTimes,
                                 insertedIds);
    }

    // Setters
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Build tree bottom-up: create all leaf nodes first, then merge upwards
     */
    private void buildBottomUp(AbstractSpatialIndex<Key, ID, Content> index,
                               List<BulkOperationProcessor.SfcEntity<Key, Content>> entities, byte startLevel) {
        if (entities.isEmpty()) {
            return;
        }

        // Phase 1: Find the maximum level needed (leaf level)
        var maxLevel = startLevel;
        var leafNodes = new HashMap<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>>();

        // Group entities by their leaf nodes
        for (var entity : entities) {
            // Find the deepest level where this entity would be placed
            var entityLevel = determineEntityLevel(index, entity.position, startLevel);
            maxLevel = (byte) Math.max(maxLevel, entityLevel);

            var leafIndex = index.calculateSpatialIndex(entity.position, entityLevel);
            leafNodes.computeIfAbsent(leafIndex, k -> new ArrayList<>()).add(entity);
        }

        // Phase 2: Create all leaf nodes
        for (var entry : leafNodes.entrySet()) {
            var nodeIndex = entry.getKey();
            var nodeEntities = entry.getValue();

            // Create the node and insert entities
            var node = index.getSpatialIndex().computeIfAbsent(nodeIndex, k -> {
                nodesCreated.incrementAndGet();
                return index.createNode();
            });

            // Add entities to the node
            for (var entity : nodeEntities) {
                var id = index.getEntityManager().generateEntityId();
                if (config.isTrackInsertedIds()) {
                    insertedIds.add(id);
                }
                node.addEntity(id);
                index.getEntityManager().createOrUpdateEntity(id, entity.content, entity.position, null);
                index.getEntityManager().addEntityLocation(id, nodeIndex);
                entitiesProcessed.incrementAndGet();
            }

            // Keys are automatically sorted in ConcurrentSkipListMap
        }

        // Phase 3: Build internal nodes from bottom to top
        var currentLevelNodes = new HashSet<>(leafNodes.keySet());
        var currentLevel = maxLevel;

        while (currentLevel > startLevel && !currentLevelNodes.isEmpty()) {
            var parentNodes = new HashSet<Key>();

            // For each node at current level, ensure its parent exists
            for (var childIndex : currentLevelNodes) {
                var parentIndex = childIndex.parent();
                if (parentIndex != null && !index.getSpatialIndex().containsKey(parentIndex)) {
                    // Create parent node
                    var parentNode = index.createNode();
                    index.getSpatialIndex().put(parentIndex, parentNode);
                    // Keys are automatically sorted in ConcurrentSkipListMap
                    nodesCreated.incrementAndGet();

                    // Mark parent as having children
                    if (parentNode instanceof SpatialNodeImpl) {
                        ((SpatialNodeImpl<ID>) parentNode).setHasChildren(true);
                    }

                    parentNodes.add(parentIndex);
                }
            }

            // Move up one level
            currentLevelNodes = parentNodes;
            currentLevel--;
        }

        // Update max depth reached
        maxDepthReached = maxLevel;

        // Report progress
        if (config.isEnableProgressTracking() && progressListener != null) {
            progressListener.onProgress(entitiesProcessed.get(), entities.size(), nodesCreated.get());
        }
    }

    /**
     * Build tree using hybrid approach: bulk load at a specific level, then build internal nodes
     */
    private void buildHybrid(AbstractSpatialIndex<Key, ID, Content> index,
                             List<BulkOperationProcessor.SfcEntity<Key, Content>> entities, byte startLevel) {
        if (entities.isEmpty()) {
            return;
        }

        // Determine the bulk loading level (typically a few levels down from root)
        var bulkLoadLevel = (byte) Math.min(startLevel + 3, index.getMaxDepth() - 2);

        // Phase 1: Bulk load entities at the bulk load level
        var bulkNodes = new HashMap<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>>();

        // Group entities by their bulk load level nodes
        for (var entity : entities) {
            var bulkIndex = index.calculateSpatialIndex(entity.position, bulkLoadLevel);
            bulkNodes.computeIfAbsent(bulkIndex, k -> new ArrayList<>()).add(entity);
        }

        // Phase 2: Create bulk nodes and process their contents
        var stack = new ArrayDeque<BuildStackFrame<Key, ID, Content>>();

        for (var entry : bulkNodes.entrySet()) {
            var nodeIndex = entry.getKey();
            var nodeEntities = entry.getValue();

            // If this bulk node has too many entities, use top-down subdivision
            if (nodeEntities.size() > index.getMaxEntitiesPerNode() && bulkLoadLevel < index.getMaxDepth()) {
                // Push frame for top-down processing of this subtree
                stack.push(new BuildStackFrame<>(nodeIndex, bulkLoadLevel, nodeEntities, 0, nodeEntities.size(),
                                                 BuildPhase.PROCESS_NODE));
            } else {
                // Direct insertion for small nodes
                createAndPopulateNode(index, nodeIndex, nodeEntities);
            }
        }

        // Phase 3: Process stack for nodes that need subdivision
        processStackFrames(index, stack, entities.size());

        // Phase 4: Build internal nodes from bulk load level up to root
        buildInternalNodesUpward(index, bulkNodes.keySet(), bulkLoadLevel, startLevel);

        // Update statistics
        maxDepthReached = Math.max(maxDepthReached, bulkLoadLevel);

        // Report final progress
        if (config.isEnableProgressTracking() && progressListener != null) {
            progressListener.onProgress(entitiesProcessed.get(), entities.size(), nodesCreated.get());
        }
    }

    /**
     * Build internal nodes from a set of child nodes up to the root level
     */
    private void buildInternalNodesUpward(AbstractSpatialIndex<Key, ID, Content> index, Set<Key> childNodes,
                                          byte fromLevel, byte toLevel) {
        var currentLevelNodes = new HashSet<>(childNodes);
        var currentLevel = fromLevel;

        while (currentLevel > toLevel && !currentLevelNodes.isEmpty()) {
            var parentNodes = new HashSet<Key>();

            for (var childIndex : currentLevelNodes) {
                var parentIndex = childIndex.parent();
                if (parentIndex != null && !index.getSpatialIndex().containsKey(parentIndex)) {
                    var parentNode = index.createNode();
                    index.getSpatialIndex().put(parentIndex, parentNode);
                    // Keys are automatically sorted in ConcurrentSkipListMap
                    nodesCreated.incrementAndGet();

                    if (parentNode != null) {
                        parentNode.setHasChildren(true);
                    }

                    parentNodes.add(parentIndex);
                }
            }

            currentLevelNodes = parentNodes;
            currentLevel--;
        }
    }

    /**
     * Build tree top-down using iterative stack approach
     */
    private void buildTopDown(AbstractSpatialIndex<Key, ID, Content> index,
                              List<BulkOperationProcessor.SfcEntity<Key, Content>> entities, byte startLevel) {

        if (entities.isEmpty()) {
            return;
        }

        // Initialize stack with root frame
        var stack = new ArrayDeque<BuildStackFrame<Key, ID, Content>>();
        // Get the root key for this spatial index type
        var rootKey = entities.getFirst().sfcIndex.root();
        stack.push(new BuildStackFrame<>(rootKey, startLevel, entities, 0, entities.size(), BuildPhase.PROCESS_NODE));

        // Process stack
        while (!stack.isEmpty()) {
            // Check stack depth to prevent stack overflow
            if (stack.size() > config.getMaxStackDepth()) {
                log.debug("StackBasedTreeBuilder: Stack depth exceeded {}, processing remaining {} frames in batches",
                          config.getMaxStackDepth(), stack.size());
                // Process frames in smaller batches to avoid stack overflow
                var batchSize = Math.min(stack.size(), config.getMaxStackDepth() / 2);
                for (int i = 0; i < batchSize && !stack.isEmpty(); i++) {
                    var frame = stack.pop();
                    processSingleFrame(index, frame, stack);
                }
                continue;
            }

            var frame = stack.pop();

            switch (frame.phase) {
                case PROCESS_NODE:
                    processNode(index, frame, stack);
                    break;

                case CREATE_CHILDREN:
                    createChildren(index, frame, stack);
                    break;

                case FINALIZE_NODE:
                    finalizeNode(index, frame);
                    break;
            }

            // Update progress
            if (config.isEnableProgressTracking() && progressListener != null) {
                progressListener.onProgress(entitiesProcessed.get(), entities.size(), nodesCreated.get());
            }
        }
    }

    /**
     * Create and populate a node with entities
     */
    private void createAndPopulateNode(AbstractSpatialIndex<Key, ID, Content> index, Key nodeIndex,
                                       List<BulkOperationProcessor.SfcEntity<Key, Content>> entities) {
        var node = index.getSpatialIndex().computeIfAbsent(nodeIndex, k -> {
            nodesCreated.incrementAndGet();
            return index.createNode();
        });

        for (var entity : entities) {
            var id = index.getEntityManager().generateEntityId();
            if (config.isTrackInsertedIds()) {
                insertedIds.add(id);
            }
            node.addEntity(id);
            index.getEntityManager().createOrUpdateEntity(id, entity.content, entity.position, null);
            index.getEntityManager().addEntityLocation(id, nodeIndex);
            entitiesProcessed.incrementAndGet();
        }

        // Keys are automatically sorted in ConcurrentSkipListMap
    }

    /**
     * Create child nodes for subdivision
     */
    private void createChildren(AbstractSpatialIndex<Key, ID, Content> index,
                                BuildStackFrame<Key, ID, Content> frame,
                                Deque<BuildStackFrame<Key, ID, Content>> stack) {

        // Group entities by child node
        var childGroups = groupByChildNode(index, frame);

        // Debug output for large builds
        if (frame.getEntityCount() > 1000) {
            log.debug("StackBasedTreeBuilder.createChildren: frame has {} entities, created {} child groups",
                      frame.getEntityCount(), childGroups.size());
            for (var entry : childGroups.entrySet()) {
                log.debug("  Child {}: {} entities", entry.getKey(), entry.getValue().size());
            }
        }

        // Create frames for each child (in reverse order for DFS)
        var childIndices = new ArrayList<>(childGroups.keySet());
        Collections.reverse(childIndices);

        for (var childIndex : childIndices) {
            var childEntities = childGroups.get(childIndex);
            if (!childEntities.isEmpty()) {
                stack.push(
                new BuildStackFrame<>(childIndex, (byte) (frame.level + 1), childEntities, 0, childEntities.size(),
                                      BuildPhase.PROCESS_NODE));
            }
        }

        // Finalize parent after children
        stack.push(new BuildStackFrame<>(frame.nodeIndex, frame.level, frame.entities, frame.startIdx, frame.endIdx,
                                         BuildPhase.FINALIZE_NODE));
    }

    /**
     * Determine the appropriate level for an entity based on spatial density
     */
    private byte determineEntityLevel(AbstractSpatialIndex<Key, ID, Content> index, Point3f position,
                                      byte startLevel) {
        // Start at the given level and potentially go deeper based on density
        var level = startLevel;

        // Simple heuristic: use adaptive level selection if available
        if (config.isUseAdaptiveSubdivision()) {
            // Could be enhanced with density-based logic
            level = (byte) Math.min(startLevel + 2, index.getMaxDepth());
        }

        return level;
    }

    /**
     * Finalize a node after children have been created
     */
    private void finalizeNode(AbstractSpatialIndex<Key, ID, Content> index,
                              BuildStackFrame<Key, ID, Content> frame) {
        // Mark node as having children if applicable
        var node = index.getSpatialIndex().get(frame.nodeIndex);
        if (node != null && node instanceof SpatialNodeImpl) {
            ((SpatialNodeImpl<ID>) node).setHasChildren(true);
        }
    }

    /**
     * Group entities by their child node
     */
    private Map<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> groupByChildNode(
        AbstractSpatialIndex<Key, ID, Content> index, BuildStackFrame<Key, ID, Content> frame) {

        var groups = new HashMap<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>>();
        var childLevel = (byte) (frame.level + 1);

        for (var i = frame.startIdx; i < frame.endIdx; i++) {
            var entity = frame.entities.get(i);

            // Calculate child node index
            var childIndex = index.calculateSpatialIndex(entity.position, childLevel);

            groups.computeIfAbsent(childIndex, _ -> new ArrayList<>()).add(entity);
        }

        return groups;
    }

    /**
     * Insert entities directly into a node
     */
    private void insertEntitiesIntoNode(AbstractSpatialIndex<Key, ID, Content> index,
                                        BuildStackFrame<Key, ID, Content> frame) {
        // Get or create node
        SpatialNodeImpl<ID> node = index.getSpatialIndex().computeIfAbsent(frame.nodeIndex, k -> {
            nodesCreated.incrementAndGet();
            return index.createNode();
        });

        // Add entities to node
        for (var i = frame.startIdx; i < frame.endIdx; i++) {
            var entity = frame.entities.get(i);

            // Generate ID for entity
            var id = index.getEntityManager().generateEntityId();

            // Track inserted ID (only if configured to do so)
            if (config.isTrackInsertedIds()) {
                insertedIds.add(id);
            }

            // Add to node
            node.addEntity(id);

            // Create or update entity with content and position
            index.getEntityManager().createOrUpdateEntity(id, entity.content, entity.position, null);

            // Add entity location
            index.getEntityManager().addEntityLocation(id, frame.nodeIndex);

            entitiesProcessed.incrementAndGet();
        }

        // No need to add to sorted indices - already added when node was created
    }

    /**
     * Prepare entities with Morton codes
     */
    private List<BulkOperationProcessor.SfcEntity<Key, Content>> prepareEntities(
        AbstractSpatialIndex<Key, ID, Content> index, List<Point3f> positions, List<Content> contents,
        byte level) {

        var entities = new ArrayList<BulkOperationProcessor.SfcEntity<Key, Content>>(positions.size());

        for (var i = 0; i < positions.size(); i++) {
            var pos = positions.get(i);
            var content = contents.get(i);

            // Calculate Morton code
            var mortonCode = index.calculateSpatialIndex(pos, level);

            entities.add(new BulkOperationProcessor.SfcEntity<>(i, mortonCode, pos, content));
        }

        // Sort by Morton code for better cache locality
        if (config.isPreSortEntities()) {
            entities.sort(Comparator.comparing(e -> e.sfcIndex));
        }

        return entities;
    }

    /**
     * Process a node - decide whether to subdivide or insert entities
     */
    private void processNode(AbstractSpatialIndex<Key, ID, Content> index,
                             BuildStackFrame<Key, ID, Content> frame, Deque<BuildStackFrame<Key, ID, Content>> stack) {

        var entityCount = frame.getEntityCount();

        // Update max depth
        maxDepthReached = Math.max(maxDepthReached, frame.level);

        // Get adaptive subdivision threshold if enabled
        var subdivisionThreshold = config.getMinEntitiesForSubdivision();
        if (config.isUseAdaptiveSubdivision()) {
            subdivisionThreshold = LevelSelector.getAdaptiveSubdivisionThreshold(frame.level,
                                                                                 config.getMinEntitiesForSubdivision());
        }

        // Check if we should subdivide
        var shouldSubdivide = entityCount > index.getMaxEntitiesPerNode() && frame.level < index.getMaxDepth()
        && entityCount >= subdivisionThreshold;

        // Debug output for large builds
        if (entityCount > 1000) {
            log.debug(
            "StackBasedTreeBuilder.processNode: entityCount={}, maxEntitiesPerNode={}, level={}, maxDepth={}, subdivisionThreshold={}, shouldSubdivide={}",
            entityCount, index.getMaxEntitiesPerNode(), frame.level, index.getMaxDepth(), subdivisionThreshold,
            shouldSubdivide);
        }

        if (shouldSubdivide) {
            // Push frames for subdivision
            stack.push(new BuildStackFrame<>(frame.nodeIndex, frame.level, frame.entities, frame.startIdx, frame.endIdx,
                                             BuildPhase.CREATE_CHILDREN));
        } else {
            // Insert entities directly into this node
            insertEntitiesIntoNode(index, frame);
        }
    }

    /**
     * Process a single frame (helper method for batch processing)
     */
    private void processSingleFrame(AbstractSpatialIndex<Key, ID, Content> index,
                                    BuildStackFrame<Key, ID, Content> frame,
                                    Deque<BuildStackFrame<Key, ID, Content>> stack) {
        switch (frame.phase) {
            case PROCESS_NODE:
                processNode(index, frame, stack);
                break;

            case CREATE_CHILDREN:
                createChildren(index, frame, stack);
                break;

            case FINALIZE_NODE:
                finalizeNode(index, frame);
                break;
        }
    }

    /**
     * Process stack frames for subdivision
     */
    private void processStackFrames(AbstractSpatialIndex<Key, ID, Content> index,
                                    Deque<BuildStackFrame<Key, ID, Content>> stack, int totalEntities) {
        while (!stack.isEmpty()) {
            if (stack.size() > config.getMaxStackDepth()) {
                log.debug("Hybrid builder: Stack depth exceeded {}, processing in batches", config.getMaxStackDepth());
                var batchSize = Math.min(stack.size(), config.getMaxStackDepth() / 2);
                for (int i = 0; i < batchSize && !stack.isEmpty(); i++) {
                    var frame = stack.pop();
                    processSingleFrame(index, frame, stack);
                }
                continue;
            }

            var frame = stack.pop();
            processSingleFrame(index, frame, stack);

            if (config.isEnableProgressTracking() && progressListener != null) {
                progressListener.onProgress(entitiesProcessed.get(), totalEntities, nodesCreated.get());
            }
        }
    }

    /**
     * Build strategies
     */
    public enum BuildStrategy {
        /**
         * Build tree from root to leaves, subdividing as needed
         */
        TOP_DOWN,

        /**
         * Build tree from leaves to root, merging as needed
         */
        BOTTOM_UP,

        /**
         * Hybrid approach: bulk load leaves, then build internal nodes
         */
        HYBRID
    }

    /**
     * Build phases for stack frames
     */
    public enum BuildPhase {
        PROCESS_NODE, CREATE_CHILDREN, FINALIZE_NODE
    }

    // Factory methods

    /**
     * Progress listener for monitoring large builds
     */
    public interface ProgressListener {
        void onPhaseComplete(String phaseName, long timeTaken);

        void onProgress(int entitiesProcessed, int totalEntities, int nodesCreated);
    }

    /**
     * Build configuration
     */
    public static class BuildConfig {
        private BuildStrategy strategy                  = BuildStrategy.TOP_DOWN;
        private int           maxStackDepth             = 20000;  // Increased for large datasets
        private int           minEntitiesForSubdivision = 4;
        private boolean       enableProgressTracking    = false;
        private boolean       preSortEntities           = true;
        private boolean       useNodePool               = true;
        private boolean       trackInsertedIds          = true;
        private boolean       useAdaptiveSubdivision    = false;

        public int getMaxStackDepth() {
            return maxStackDepth;
        }

        public int getMinEntitiesForSubdivision() {
            return minEntitiesForSubdivision;
        }

        // Getters
        public BuildStrategy getStrategy() {
            return strategy;
        }

        public boolean isEnableProgressTracking() {
            return enableProgressTracking;
        }

        public boolean isPreSortEntities() {
            return preSortEntities;
        }

        public boolean isTrackInsertedIds() {
            return trackInsertedIds;
        }

        public boolean isUseAdaptiveSubdivision() {
            return useAdaptiveSubdivision;
        }

        public boolean isUseNodePool() {
            return useNodePool;
        }

        public BuildConfig withAdaptiveSubdivision(boolean enable) {
            this.useAdaptiveSubdivision = enable;
            return this;
        }

        public BuildConfig withMaxStackDepth(int depth) {
            this.maxStackDepth = depth;
            return this;
        }

        public BuildConfig withMinEntitiesForSubdivision(int min) {
            this.minEntitiesForSubdivision = min;
            return this;
        }

        public BuildConfig withNodePool(boolean enable) {
            this.useNodePool = enable;
            return this;
        }

        public BuildConfig withPreSortEntities(boolean enable) {
            this.preSortEntities = enable;
            return this;
        }

        public BuildConfig withProgressTracking(boolean enable) {
            this.enableProgressTracking = enable;
            return this;
        }

        public BuildConfig withStrategy(BuildStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public BuildConfig withTrackInsertedIds(boolean enable) {
            this.trackInsertedIds = enable;
            return this;
        }
    }

    /**
     * Stack frame for iterative construction
     */
    public static class BuildStackFrame<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        public final Key                                                  nodeIndex;
        public final byte                                                 level;
        public final List<BulkOperationProcessor.SfcEntity<Key, Content>> entities;
        public final int                                                  startIdx;
        public final int                                                  endIdx;
        public final BuildPhase                                           phase;

        public BuildStackFrame(Key nodeIndex, byte level, List<BulkOperationProcessor.SfcEntity<Key, Content>> entities,
                               int startIdx, int endIdx, BuildPhase phase) {
            this.nodeIndex = nodeIndex;
            this.level = level;
            this.entities = entities;
            this.startIdx = startIdx;
            this.endIdx = endIdx;
            this.phase = phase;
        }

        public int getEntityCount() {
            return endIdx - startIdx;
        }
    }

    /**
     * Build result with statistics
     */
    public static class BuildResult<ID extends EntityID> {
        public final int               nodesCreated;
        public final int               entitiesProcessed;
        public final long              timeTaken;
        public final int               maxDepthReached;
        public final Map<String, Long> phaseTimes;
        public final List<ID>          insertedIds;

        public BuildResult(int nodesCreated, int entitiesProcessed, long timeTaken, int maxDepthReached,
                           Map<String, Long> phaseTimes, List<ID> insertedIds) {
            this.nodesCreated = nodesCreated;
            this.entitiesProcessed = entitiesProcessed;
            this.timeTaken = timeTaken;
            this.maxDepthReached = maxDepthReached;
            this.phaseTimes = phaseTimes;
            this.insertedIds = insertedIds;
        }
    }
}
