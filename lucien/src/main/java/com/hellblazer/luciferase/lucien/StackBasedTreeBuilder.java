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
 * @param <NodeType> The type of spatial node used
 * @author hal.hildebrand
 */
public class StackBasedTreeBuilder<Key extends SpatialKey<Key>, ID extends EntityID, Content, NodeType extends SpatialNodeStorage<ID>> {

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
    public BuildResult buildTree(AbstractSpatialIndex<Key, ID, Content, NodeType> index, List<Point3f> positions,
                                 List<Content> contents, byte startLevel) {
        long startTime = System.currentTimeMillis();
        Map<String, Long> phaseTimes = new HashMap<>();

        // Clear previous build state
        insertedIds.clear();
        nodesCreated.set(0);
        entitiesProcessed.set(0);
        maxDepthReached = 0;

        // Phase 1: Prepare entities
        long phaseStart = System.currentTimeMillis();
        List<BulkOperationProcessor.SfcEntity<Key, Content>> mortonEntities = prepareEntities(index, positions,
                                                                                              contents, startLevel);
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

        long totalTime = System.currentTimeMillis() - startTime;

        return new BuildResult<>(nodesCreated.get(), entitiesProcessed.get(), totalTime, maxDepthReached, phaseTimes,
                                 insertedIds);
    }

    // Setters
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Build tree bottom-up (not implemented - placeholder)
     */
    private void buildBottomUp(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
                               List<BulkOperationProcessor.SfcEntity<Key, Content>> entities, byte startLevel) {
        // TODO: Implement bottom-up construction
        // For now, fall back to top-down
        buildTopDown(index, entities, startLevel);
    }

    /**
     * Build tree using hybrid approach (not implemented - placeholder)
     */
    private void buildHybrid(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
                             List<BulkOperationProcessor.SfcEntity<Key, Content>> entities, byte startLevel) {
        // TODO: Implement hybrid construction
        // For now, fall back to top-down
        buildTopDown(index, entities, startLevel);
    }

    /**
     * Build tree top-down using iterative stack approach
     */
    private void buildTopDown(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
                              List<BulkOperationProcessor.SfcEntity<Key, Content>> entities, byte startLevel) {

        if (entities.isEmpty()) {
            return;
        }

        // Initialize stack with root frame
        Deque<BuildStackFrame<Key, ID, Content>> stack = new ArrayDeque<>();
        // Get the root key for this spatial index type
        Key rootKey = entities.get(0).sfcIndex.root();
        stack.push(new BuildStackFrame<>(rootKey, startLevel, entities, 0, entities.size(), BuildPhase.PROCESS_NODE));

        // Process stack
        while (!stack.isEmpty()) {
            // Check stack depth to prevent stack overflow
            if (stack.size() > config.getMaxStackDepth()) {
                log.debug("StackBasedTreeBuilder: Stack depth exceeded {}, processing remaining {} frames in batches",
                          config.getMaxStackDepth(), stack.size());
                // Process frames in smaller batches to avoid stack overflow
                int batchSize = Math.min(stack.size(), config.getMaxStackDepth() / 2);
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
     * Create child nodes for subdivision
     */
    private void createChildren(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
                                BuildStackFrame<Key, ID, Content> frame,
                                Deque<BuildStackFrame<Key, ID, Content>> stack) {

        // Group entities by child node
        Map<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> childGroups = groupByChildNode(index, frame);

        // Debug output for large builds
        if (frame.getEntityCount() > 1000) {
            log.debug("StackBasedTreeBuilder.createChildren: frame has {} entities, created {} child groups",
                      frame.getEntityCount(), childGroups.size());
            for (Map.Entry<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> entry : childGroups.entrySet()) {
                log.debug("  Child {}: {} entities", entry.getKey(), entry.getValue().size());
            }
        }

        // Create frames for each child (in reverse order for DFS)
        List<Key> childIndices = new ArrayList<>(childGroups.keySet());
        Collections.reverse(childIndices);

        for (Key childIndex : childIndices) {
            List<BulkOperationProcessor.SfcEntity<Key, Content>> childEntities = childGroups.get(childIndex);
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
     * Finalize a node after children have been created
     */
    private void finalizeNode(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
                              BuildStackFrame<Key, ID, Content> frame) {
        // Mark node as having children if applicable
        NodeType node = index.getSpatialIndex().get(frame.nodeIndex);
        if (node != null && node instanceof AbstractSpatialNode) {
            ((AbstractSpatialNode<ID>) node).setHasChildren(true);
        }
    }

    /**
     * Group entities by their child node
     */
    private Map<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> groupByChildNode(
    AbstractSpatialIndex<Key, ID, Content, NodeType> index, BuildStackFrame<Key, ID, Content> frame) {

        Map<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> groups = new HashMap<>();
        byte childLevel = (byte) (frame.level + 1);

        for (int i = frame.startIdx; i < frame.endIdx; i++) {
            BulkOperationProcessor.SfcEntity<Key, Content> entity = frame.entities.get(i);

            // Calculate child node index
            var childIndex = index.calculateSpatialIndex(entity.position, childLevel);

            groups.computeIfAbsent(childIndex, _ -> new ArrayList<>()).add(entity);
        }

        return groups;
    }

    /**
     * Insert entities directly into a node
     */
    private void insertEntitiesIntoNode(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
                                        BuildStackFrame<Key, ID, Content> frame) {
        // Get or create node
        NodeType node = index.getSpatialIndex().computeIfAbsent(frame.nodeIndex, _ -> {
            nodesCreated.incrementAndGet();
            return index.createNode();
        });

        // Add entities to node
        for (int i = frame.startIdx; i < frame.endIdx; i++) {
            BulkOperationProcessor.SfcEntity<Key, Content> entity = frame.entities.get(i);

            // Generate ID for entity
            ID id = index.getEntityManager().generateEntityId();

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

        // Add to sorted indices
        index.getSortedSpatialIndices().add(frame.nodeIndex);
    }

    /**
     * Prepare entities with Morton codes
     */
    private List<BulkOperationProcessor.SfcEntity<Key, Content>> prepareEntities(
    AbstractSpatialIndex<Key, ID, Content, NodeType> index, List<Point3f> positions, List<Content> contents,
    byte level) {

        List<BulkOperationProcessor.SfcEntity<Key, Content>> entities = new ArrayList<>(positions.size());

        for (int i = 0; i < positions.size(); i++) {
            Point3f pos = positions.get(i);
            Content content = contents.get(i);

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
    private void processNode(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
                             BuildStackFrame<Key, ID, Content> frame, Deque<BuildStackFrame<Key, ID, Content>> stack) {

        int entityCount = frame.getEntityCount();

        // Update max depth
        maxDepthReached = Math.max(maxDepthReached, frame.level);

        // Get adaptive subdivision threshold if enabled
        int subdivisionThreshold = config.getMinEntitiesForSubdivision();
        if (config.isUseAdaptiveSubdivision()) {
            subdivisionThreshold = LevelSelector.getAdaptiveSubdivisionThreshold(frame.level,
                                                                                 config.getMinEntitiesForSubdivision());
        }

        // Check if we should subdivide
        boolean shouldSubdivide = entityCount > index.getMaxEntitiesPerNode() && frame.level < index.getMaxDepth()
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
    private void processSingleFrame(AbstractSpatialIndex<Key, ID, Content, NodeType> index,
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

    /**
     * Progress listener for monitoring large builds
     */
    public interface ProgressListener {
        void onPhaseComplete(String phaseName, long timeTaken);

        void onProgress(int entitiesProcessed, int totalEntities, int nodesCreated);
    }

    // Factory methods

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
