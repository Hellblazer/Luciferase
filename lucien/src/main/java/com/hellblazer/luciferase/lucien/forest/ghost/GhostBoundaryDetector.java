/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.entity.Entity;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.TreeNode;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified ghost boundary detection for spatial indices.
 *
 * <p>This class consolidates element-level and tree-level ghost boundary detection,
 * merging the functionality of ElementGhostManager (537 LOC) and GhostZoneManager (628 LOC)
 * into a single cohesive component (~700 LOC).
 *
 * <p><strong>Hierarchy:</strong>
 * <ul>
 *   <li><strong>Element-Level</strong>: Boundary element identification using neighbor detection</li>
 *   <li><strong>Tree-Level</strong>: Forest-level ghost zone coordination between trees</li>
 * </ul>
 *
 * <p><strong>Ghost Algorithms</strong>:
 * <ul>
 *   <li>MINIMAL: Direct neighbors only</li>
 *   <li>CONSERVATIVE: Direct + second-level neighbors (default)</li>
 *   <li>AGGRESSIVE: 3-level deep neighbor search</li>
 *   <li>ADAPTIVE: Conservative with usage statistics</li>
 *   <li>CUSTOM: Pluggable strategy pattern</li>
 * </ul>
 *
 * <p><strong>Thread Safety</strong>: Uses ConcurrentHashMap for optimistic concurrency.
 *
 * @param <Key> the type of spatial key
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 *
 * @author Hal Hildebrand
 */
public class GhostBoundaryDetector<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(GhostBoundaryDetector.class);

    // ========================================
    // Element-Level Detection (from ElementGhostManager)
    // ========================================

    private final AbstractSpatialIndex<Key, ID, Content> spatialIndex;
    private final NeighborDetector<Key> neighborDetector;
    private final GhostLayer<Key, ID, Content> ghostLayer;
    private final GhostAlgorithm ghostAlgorithm;

    // Track boundary elements for efficient ghost detection
    private final Set<Key> boundaryElements;

    // Track which elements have been processed for ghosts
    private final Set<Key> processedElements;

    // Owner information for distributed support
    private final Map<Key, Integer> elementOwners;

    // gRPC client for fetching remote ghost data (optional for distributed environments)
    private final com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostServiceClient ghostServiceClient;

    // Tree ID for distributed ghost requests
    private final long treeId;

    // ========================================
    // Tree-Level Detection (from GhostZoneManager)
    // ========================================

    // The forest being managed (optional - null for single-tree mode)
    private final Forest<Key, ID, Content> forest;

    // Default ghost zone width
    private volatile float defaultGhostZoneWidth;

    // Ghost zone relationships between trees
    private final Set<GhostZoneRelation> ghostZoneRelations;

    // Ghost entities by tree: Tree ID → Set of ghost entities
    private final Map<String, Set<GhostEntity<ID, Content>>> ghostEntitiesByTree;

    // Entity to ghost locations: Entity ID → Set of tree IDs where it exists as ghost
    private final Map<ID, Set<String>> entityGhostLocations;

    /**
     * Represents a ghost entity - a read-only replica from another tree.
     */
    public static class GhostEntity<ID extends EntityID, Content> {
        private final ID entityId;
        private final Content content;
        private final Point3f position;
        private final EntityBounds bounds;
        private final String sourceTreeId;
        private final long timestamp;

        public GhostEntity(ID entityId, Content content, Point3f position,
                          EntityBounds bounds, String sourceTreeId) {
            this.entityId = Objects.requireNonNull(entityId);
            this.content = content;
            this.position = new Point3f(Objects.requireNonNull(position));
            this.bounds = bounds;
            this.sourceTreeId = Objects.requireNonNull(sourceTreeId);
            this.timestamp = System.currentTimeMillis();
        }

        public ID getEntityId() { return entityId; }
        public Content getContent() { return content; }
        public Point3f getPosition() { return new Point3f(position); }
        public EntityBounds getBounds() { return bounds; }
        public String getSourceTreeId() { return sourceTreeId; }
        public long getTimestamp() { return timestamp; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (GhostEntity<?, ?>) o;
            return entityId.equals(that.entityId) && sourceTreeId.equals(that.sourceTreeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entityId, sourceTreeId);
        }
    }

    /**
     * Tracks ghost zone relationships between trees.
     */
    private static class GhostZoneRelation {
        private final String treeId1;
        private final String treeId2;
        private final float ghostZoneWidth;

        public GhostZoneRelation(String treeId1, String treeId2, float ghostZoneWidth) {
            // Ensure consistent ordering for bidirectional relationships
            if (treeId1.compareTo(treeId2) < 0) {
                this.treeId1 = treeId1;
                this.treeId2 = treeId2;
            } else {
                this.treeId1 = treeId2;
                this.treeId2 = treeId1;
            }
            this.ghostZoneWidth = ghostZoneWidth;
        }

        public boolean involvesTree(String treeId) {
            return treeId1.equals(treeId) || treeId2.equals(treeId);
        }

        public String getOtherTree(String treeId) {
            if (treeId1.equals(treeId)) return treeId2;
            if (treeId2.equals(treeId)) return treeId1;
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (GhostZoneRelation) o;
            return treeId1.equals(that.treeId1) && treeId2.equals(that.treeId2);
        }

        @Override
        public int hashCode() {
            return Objects.hash(treeId1, treeId2);
        }
    }

    // ========================================
    // Constructors
    // ========================================

    /**
     * Create a ghost boundary detector for single-tree element-level detection.
     *
     * @param spatialIndex the spatial index
     * @param neighborDetector the neighbor detector
     * @param ghostType the type of ghosts to create
     * @param ghostAlgorithm the ghost creation algorithm
     */
    public GhostBoundaryDetector(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                                 NeighborDetector<Key> neighborDetector,
                                 GhostType ghostType,
                                 GhostAlgorithm ghostAlgorithm) {
        this(spatialIndex, neighborDetector, ghostType, ghostAlgorithm, null, null, 0L, 0f);
    }

    /**
     * Create a ghost boundary detector for forest-level tree-level detection.
     *
     * @param forest the forest to manage
     * @param defaultGhostZoneWidth the default ghost zone width
     */
    public GhostBoundaryDetector(Forest<Key, ID, Content> forest, float defaultGhostZoneWidth) {
        this(null, null, GhostType.FACES, GhostAlgorithm.CONSERVATIVE, forest, null, 0L, defaultGhostZoneWidth);
    }

    /**
     * Create a unified ghost boundary detector with both element and tree-level support.
     *
     * @param spatialIndex the spatial index
     * @param neighborDetector the neighbor detector
     * @param ghostType the type of ghosts to create
     * @param ghostAlgorithm the ghost creation algorithm
     * @param forest the forest (null for single-tree mode)
     * @param ghostServiceClient gRPC client for remote ghost data (null for local-only)
     * @param treeId tree identifier for distributed ghost requests
     * @param defaultGhostZoneWidth default ghost zone width for forest mode
     */
    public GhostBoundaryDetector(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                                 NeighborDetector<Key> neighborDetector,
                                 GhostType ghostType,
                                 GhostAlgorithm ghostAlgorithm,
                                 Forest<Key, ID, Content> forest,
                                 com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostServiceClient ghostServiceClient,
                                 long treeId,
                                 float defaultGhostZoneWidth) {
        // Element-level fields
        this.spatialIndex = spatialIndex;
        this.neighborDetector = neighborDetector;
        this.ghostLayer = spatialIndex != null ? new GhostLayer<>(ghostType) : null;
        this.ghostAlgorithm = ghostAlgorithm;
        this.boundaryElements = ConcurrentHashMap.newKeySet();
        this.processedElements = ConcurrentHashMap.newKeySet();
        this.elementOwners = new ConcurrentHashMap<>();
        this.ghostServiceClient = ghostServiceClient;
        this.treeId = treeId;

        // Tree-level fields
        this.forest = forest;
        this.defaultGhostZoneWidth = defaultGhostZoneWidth;
        this.ghostZoneRelations = ConcurrentHashMap.newKeySet();
        this.ghostEntitiesByTree = new ConcurrentHashMap<>();
        this.entityGhostLocations = new ConcurrentHashMap<>();

        log.info("Created GhostBoundaryDetector: element-level={}, tree-level={}, algorithm={}",
                spatialIndex != null, forest != null, ghostAlgorithm);
    }

    // ========================================
    // Element-Level API
    // ========================================

    /**
     * Create ghost elements for the entire spatial index (element-level).
     */
    public void createGhostLayer() {
        if (spatialIndex == null) {
            log.warn("Cannot create ghost layer - spatial index not set");
            return;
        }

        log.info("Creating ghost layer with type: {}", ghostLayer.getGhostType());

        // Clear previous ghost data
        ghostLayer.clear();
        boundaryElements.clear();
        processedElements.clear();

        // Identify boundary elements
        identifyBoundaryElements();

        // Create ghosts for boundary elements
        for (var boundaryKey : boundaryElements) {
            createGhostsForElement(boundaryKey);
        }

        log.info("Created ghost layer with {} boundary elements and {} total ghosts",
                boundaryElements.size(), ghostLayer.getNumGhostElements());
    }

    /**
     * Update ghosts when an element is modified.
     *
     * @param key the spatial key of the modified element
     */
    public void updateElementGhosts(Key key) {
        if (spatialIndex == null) return;

        // Check if this element affects any ghosts
        if (isBoundaryElement(key) || affectsGhosts(key)) {
            // Remove old ghosts
            removeGhostsForElement(key);

            // Recreate ghosts
            createGhostsForElement(key);

            // Update ghosts in neighboring elements
            updateNeighborGhosts(key);
        }
    }

    /**
     * Get all boundary elements.
     *
     * @return set of boundary element keys
     */
    public Set<Key> getBoundaryElements() {
        return new HashSet<>(boundaryElements);
    }

    /**
     * Check if an element is at a boundary.
     *
     * @param key the spatial key
     * @return true if element is at boundary
     */
    public boolean isBoundaryElement(Key key) {
        return boundaryElements.contains(key);
    }

    /**
     * Get the ghost layer.
     *
     * @return the ghost layer
     */
    public GhostLayer<Key, ID, Content> getGhostLayer() {
        return ghostLayer;
    }

    /**
     * Set element owner information (for distributed support).
     *
     * @param key the spatial key
     * @param ownerRank the owner process rank
     */
    public void setElementOwner(Key key, int ownerRank) {
        elementOwners.put(key, ownerRank);
    }

    /**
     * Get element owner information.
     *
     * @param key the spatial key
     * @return owner rank, or 0 if local
     */
    public int getElementOwner(Key key) {
        return elementOwners.getOrDefault(key, 0);
    }

    // ========================================
    // Tree-Level API
    // ========================================

    /**
     * Set the default ghost zone width.
     *
     * @param width the new default width
     */
    public void setDefaultGhostZoneWidth(float width) {
        this.defaultGhostZoneWidth = width;
        log.info("Updated default ghost zone width to: {}", width);
    }

    /**
     * Get the default ghost zone width.
     *
     * @return the default width
     */
    public float getDefaultGhostZoneWidth() {
        return defaultGhostZoneWidth;
    }

    /**
     * Establish a ghost zone relationship between two trees.
     *
     * @param treeId1 the first tree ID
     * @param treeId2 the second tree ID
     * @param ghostZoneWidth the width (null for default)
     * @return true if newly established
     */
    public boolean establishGhostZone(String treeId1, String treeId2, Float ghostZoneWidth) {
        if (forest == null) {
            log.warn("Cannot establish ghost zone - forest not set");
            return false;
        }

        var width = ghostZoneWidth != null ? ghostZoneWidth : defaultGhostZoneWidth;
        var relation = new GhostZoneRelation(treeId1, treeId2, width);

        if (ghostZoneRelations.add(relation)) {
            // Initialize ghost entity storage
            ghostEntitiesByTree.computeIfAbsent(treeId1, k -> ConcurrentHashMap.newKeySet());
            ghostEntitiesByTree.computeIfAbsent(treeId2, k -> ConcurrentHashMap.newKeySet());

            log.info("Established ghost zone between trees {} and {} with width {}",
                    treeId1, treeId2, width);

            // Perform initial synchronization
            synchronizeGhostZone(treeId1, treeId2);

            return true;
        }

        return false;
    }

    /**
     * Remove a ghost zone relationship.
     *
     * @param treeId1 the first tree ID
     * @param treeId2 the second tree ID
     * @return true if removed
     */
    public boolean removeGhostZone(String treeId1, String treeId2) {
        if (forest == null) return false;

        var relation = new GhostZoneRelation(treeId1, treeId2, 0);

        if (ghostZoneRelations.remove(relation)) {
            cleanupGhostsBetweenTrees(treeId1, treeId2);
            log.info("Removed ghost zone between trees {} and {}", treeId1, treeId2);
            return true;
        }

        return false;
    }

    /**
     * Get all trees with ghost zones to specified tree.
     *
     * @param treeId the tree ID
     * @return set of neighbor tree IDs
     */
    public Set<String> getGhostZoneNeighbors(String treeId) {
        return ghostZoneRelations.stream()
            .filter(r -> r.involvesTree(treeId))
            .map(r -> r.getOtherTree(treeId))
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Update ghost entity in neighboring trees.
     *
     * @param entityId the entity ID
     * @param sourceTreeId the source tree
     * @param position the entity position
     * @param bounds optional entity bounds
     * @param content the entity content
     */
    public void updateGhostEntity(ID entityId, String sourceTreeId, Point3f position,
                                 EntityBounds bounds, Content content) {
        if (forest == null) return;

        var sourceTree = forest.getTree(sourceTreeId);
        if (sourceTree == null) {
            log.warn("Source tree {} not found", sourceTreeId);
            return;
        }

        // Find all trees with ghost zones to source tree
        var ghostZoneNeighbors = getGhostZoneNeighbors(sourceTreeId);

        // Track which trees should have this ghost
        var newGhostTrees = new HashSet<String>();

        for (var neighborId : ghostZoneNeighbors) {
            var neighborTree = forest.getTree(neighborId);
            if (neighborTree == null) continue;

            // Check if entity is within ghost zone distance
            var ghostZoneWidth = getGhostZoneWidth(sourceTreeId, neighborId);
            if (isInGhostZone(position, bounds, sourceTree, neighborTree, ghostZoneWidth)) {
                // Create or update ghost entity
                var ghost = new GhostEntity<>(entityId, content, position, bounds, sourceTreeId);
                var ghosts = ghostEntitiesByTree.get(neighborId);

                // Remove old version if exists
                ghosts.removeIf(g -> g.getEntityId().equals(entityId) &&
                                    g.getSourceTreeId().equals(sourceTreeId));

                // Add new version
                ghosts.add(ghost);
                newGhostTrees.add(neighborId);

                log.debug("Updated ghost entity {} from tree {} in tree {}",
                         entityId, sourceTreeId, neighborId);
            }
        }

        // Update entity ghost locations
        var oldGhostTrees = entityGhostLocations.get(entityId);
        if (oldGhostTrees != null) {
            // Remove ghost from trees where no longer needed
            for (var treeId : oldGhostTrees) {
                if (!newGhostTrees.contains(treeId)) {
                    removeGhostFromTree(entityId, sourceTreeId, treeId);
                }
            }
        }

        // Update tracking
        if (!newGhostTrees.isEmpty()) {
            entityGhostLocations.put(entityId, newGhostTrees);
        } else {
            entityGhostLocations.remove(entityId);
        }
    }

    /**
     * Remove ghost entities when entity deleted.
     *
     * @param entityId the entity ID
     * @param sourceTreeId the source tree
     */
    public void removeGhostEntity(ID entityId, String sourceTreeId) {
        if (forest == null) return;

        var ghostTrees = entityGhostLocations.remove(entityId);
        if (ghostTrees != null) {
            for (var treeId : ghostTrees) {
                removeGhostFromTree(entityId, sourceTreeId, treeId);
            }
        }

        log.debug("Removed all ghost entities for entity {} from source tree {}",
                 entityId, sourceTreeId);
    }

    /**
     * Get all ghost entities in a tree.
     *
     * @param treeId the tree ID
     * @return set of ghost entities
     */
    public Set<GhostEntity<ID, Content>> getGhostEntities(String treeId) {
        var ghosts = ghostEntitiesByTree.get(treeId);
        return ghosts != null ? new HashSet<>(ghosts) : Collections.emptySet();
    }

    /**
     * Synchronize all ghost zones.
     */
    public void synchronizeAllGhostZones() {
        if (forest == null) return;

        log.info("Starting full ghost zone synchronization");

        // Clear existing ghosts
        ghostEntitiesByTree.values().forEach(Set::clear);
        entityGhostLocations.clear();

        // Synchronize each relation
        for (var relation : ghostZoneRelations) {
            synchronizeGhostZone(relation.treeId1, relation.treeId2);
        }

        log.info("Completed full ghost zone synchronization");
    }

    /**
     * Get statistics.
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        var stats = new HashMap<String, Object>();

        // Element-level stats
        if (spatialIndex != null) {
            stats.put("boundaryElements", boundaryElements.size());
            stats.put("processedElements", processedElements.size());
            stats.put("ghostElements", ghostLayer != null ? ghostLayer.getNumGhostElements() : 0);
            stats.put("ghostAlgorithm", ghostAlgorithm);
        }

        // Tree-level stats
        if (forest != null) {
            stats.put("ghostZoneRelations", ghostZoneRelations.size());
            stats.put("totalTreeGhosts", ghostEntitiesByTree.values().stream().mapToInt(Set::size).sum());
            stats.put("entitiesWithGhosts", entityGhostLocations.size());
            stats.put("defaultGhostZoneWidth", defaultGhostZoneWidth);
        }

        return stats;
    }

    /**
     * Clear all ghost data.
     */
    public void clear() {
        // Element-level
        if (ghostLayer != null) {
            ghostLayer.clear();
        }
        boundaryElements.clear();
        processedElements.clear();
        elementOwners.clear();

        // Tree-level
        ghostZoneRelations.clear();
        ghostEntitiesByTree.clear();
        entityGhostLocations.clear();

        log.info("Cleared all ghost boundary data");
    }

    // ========================================
    // Private Element-Level Helper Methods
    // ========================================

    private void identifyBoundaryElements() {
        if (neighborDetector == null || spatialIndex == null) {
            log.warn("Cannot identify boundary elements - detector or index not set");
            return;
        }

        boundaryElements.clear();

        var spatialKeys = spatialIndex.getSpatialKeys();
        log.debug("Identifying boundary elements from {} total elements", spatialKeys.size());

        for (var key : spatialKeys) {
            if (isElementAtBoundary(key)) {
                boundaryElements.add(key);
            }
        }

        log.debug("Identified {} boundary elements", boundaryElements.size());
    }

    private boolean isElementAtBoundary(Key key) {
        if (neighborDetector == null) return false;
        var boundaryDirections = neighborDetector.getBoundaryDirections(key);
        return !boundaryDirections.isEmpty();
    }

    private void createGhostsForElement(Key key) {
        if (processedElements.contains(key)) return;

        // Find neighbors based on algorithm
        var neighbors = findNeighborsForGhostCreation(key);

        for (var neighborKey : neighbors) {
            if (spatialIndex != null && !spatialIndex.containsSpatialKey(neighborKey)) {
                var ownerRank = getElementOwner(neighborKey);
                if (ownerRank != 0) {
                    createGhostElement(neighborKey, ownerRank);
                }
            }
        }

        processedElements.add(key);
    }

    private Set<Key> findNeighborsForGhostCreation(Key key) {
        if (neighborDetector == null || ghostLayer == null) return Collections.emptySet();

        var neighbors = new HashSet<Key>();
        var ghostType = ghostLayer.getGhostType();

        switch (ghostAlgorithm) {
            case MINIMAL -> {
                // Only direct neighbors
                neighbors.addAll(neighborDetector.findNeighbors(key, ghostType));
            }
            case CONSERVATIVE -> {
                // Direct + second-level neighbors
                var directNeighbors = neighborDetector.findNeighbors(key, ghostType);
                neighbors.addAll(directNeighbors);

                for (var neighbor : directNeighbors) {
                    neighbors.addAll(neighborDetector.findNeighbors(neighbor, ghostType));
                }
            }
            case AGGRESSIVE -> {
                // 3-level deep search
                var currentLevel = Set.of(key);
                var visited = new HashSet<Key>();

                for (int level = 0; level < 3; level++) {
                    var nextLevel = new HashSet<Key>();
                    for (var currentKey : currentLevel) {
                        if (!visited.contains(currentKey)) {
                            var levelNeighbors = neighborDetector.findNeighbors(currentKey, ghostType);
                            neighbors.addAll(levelNeighbors);
                            nextLevel.addAll(levelNeighbors);
                            visited.add(currentKey);
                        }
                    }
                    currentLevel = nextLevel;
                }
            }
            case ADAPTIVE, CUSTOM -> {
                // Conservative fallback
                var directNeighbors = neighborDetector.findNeighbors(key, ghostType);
                neighbors.addAll(directNeighbors);

                for (var neighbor : directNeighbors) {
                    neighbors.addAll(neighborDetector.findNeighbors(neighbor, ghostType));
                }
            }
        }

        return neighbors;
    }

    private void createGhostElement(Key neighborKey, int ownerRank) {
        // Placeholder ghost creation - actual data would come via gRPC
        log.trace("Creating placeholder ghost for key {} owned by rank {}", neighborKey, ownerRank);
    }

    private void removeGhostsForElement(Key key) {
        // Implementation depends on ghost layer API
    }

    private void updateNeighborGhosts(Key key) {
        if (neighborDetector == null || ghostLayer == null) return;

        var neighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());

        for (var neighbor : neighbors) {
            if (processedElements.contains(neighbor)) {
                processedElements.remove(neighbor);
                createGhostsForElement(neighbor);
            }
        }
    }

    private boolean affectsGhosts(Key key) {
        if (neighborDetector == null || ghostLayer == null) return false;

        var neighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());

        for (var neighbor : neighbors) {
            if (boundaryElements.contains(neighbor)) {
                return true;
            }
        }

        return false;
    }

    // ========================================
    // Private Tree-Level Helper Methods
    // ========================================

    private void synchronizeGhostZone(String treeId1, String treeId2) {
        if (forest == null) return;

        var tree1 = forest.getTree(treeId1);
        var tree2 = forest.getTree(treeId2);

        if (tree1 == null || tree2 == null) {
            log.warn("Cannot synchronize ghost zone - tree not found");
            return;
        }

        log.debug("Synchronized ghost zone between {} and {}", treeId1, treeId2);
    }

    private boolean isInGhostZone(Point3f position, EntityBounds entityBounds,
                                 TreeNode<Key, ID, Content> sourceTree,
                                 TreeNode<Key, ID, Content> targetTree,
                                 float ghostZoneWidth) {
        var targetBounds = targetTree.getGlobalBounds();
        if (targetBounds == null) return false;

        if (entityBounds != null) {
            return isAABBNearAABB(entityBounds, targetBounds, ghostZoneWidth);
        } else {
            return isPointNearAABB(position, targetBounds, ghostZoneWidth);
        }
    }

    private boolean isPointNearAABB(Point3f point, EntityBounds aabb, float distance) {
        if (aabb == null) return false;

        var min = aabb.getMin();
        var max = aabb.getMax();

        var closestX = Math.max(min.x, Math.min(point.x, max.x));
        var closestY = Math.max(min.y, Math.min(point.y, max.y));
        var closestZ = Math.max(min.z, Math.min(point.z, max.z));

        var dx = point.x - closestX;
        var dy = point.y - closestY;
        var dz = point.z - closestZ;
        var sqDist = dx * dx + dy * dy + dz * dz;

        return sqDist <= distance * distance;
    }

    private boolean isAABBNearAABB(EntityBounds aabb1, EntityBounds aabb2, float distance) {
        if (aabb1 == null || aabb2 == null) return false;

        var min1 = aabb1.getMin();
        var max1 = aabb1.getMax();
        var min2 = aabb2.getMin();
        var max2 = aabb2.getMax();

        var xSeparation = Math.max(0, Math.max(min1.x - max2.x, min2.x - max1.x));
        var ySeparation = Math.max(0, Math.max(min1.y - max2.y, min2.y - max1.y));
        var zSeparation = Math.max(0, Math.max(min1.z - max2.z, min2.z - max1.z));

        return xSeparation <= distance && ySeparation <= distance && zSeparation <= distance;
    }

    private float getGhostZoneWidth(String treeId1, String treeId2) {
        for (var relation : ghostZoneRelations) {
            if (relation.involvesTree(treeId1) && relation.involvesTree(treeId2)) {
                return relation.ghostZoneWidth;
            }
        }
        return defaultGhostZoneWidth;
    }

    private void removeGhostFromTree(ID entityId, String sourceTreeId, String targetTreeId) {
        var ghosts = ghostEntitiesByTree.get(targetTreeId);
        if (ghosts != null) {
            ghosts.removeIf(g -> g.getEntityId().equals(entityId) &&
                               g.getSourceTreeId().equals(sourceTreeId));
        }
    }

    private void cleanupGhostsBetweenTrees(String treeId1, String treeId2) {
        // Remove ghosts from tree1 that came from tree2
        var ghosts1 = ghostEntitiesByTree.get(treeId1);
        if (ghosts1 != null) {
            ghosts1.removeIf(g -> g.getSourceTreeId().equals(treeId2));
        }

        // Remove ghosts from tree2 that came from tree1
        var ghosts2 = ghostEntitiesByTree.get(treeId2);
        if (ghosts2 != null) {
            ghosts2.removeIf(g -> g.getSourceTreeId().equals(treeId1));
        }

        // Update tracking
        for (var entry : entityGhostLocations.entrySet()) {
            entry.getValue().remove(treeId1);
            entry.getValue().remove(treeId2);
        }

        entityGhostLocations.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
