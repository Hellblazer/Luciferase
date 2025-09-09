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
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector.NeighborInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Manages element-level ghost detection and creation.
 * 
 * This class bridges the gap between topological neighbor detection
 * and ghost element management, providing element-level ghost functionality
 * similar to t8code's approach.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 * 
 * @author Hal Hildebrand
 */
public class ElementGhostManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(ElementGhostManager.class);
    
    private final AbstractSpatialIndex<Key, ID, Content> spatialIndex;
    private final NeighborDetector<Key> neighborDetector;
    private final GhostLayer<Key, ID, Content> ghostLayer;
    private final GhostAlgorithm ghostAlgorithm;
    
    // Track boundary elements for efficient ghost detection
    private final Set<Key> boundaryElements;
    
    // Track which elements have been processed for ghosts
    private final Set<Key> processedElements;
    
    // Owner information for distributed support (placeholder for now)
    private final Map<Key, Integer> elementOwners;
    
    // gRPC client for fetching remote ghost data (optional for distributed environments)
    private final com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostServiceClient ghostServiceClient;
    
    // Tree ID for distributed ghost requests
    private final long treeId;
    
    /**
     * Create an element ghost manager.
     * 
     * @param spatialIndex the spatial index
     * @param neighborDetector the neighbor detector for this index type
     * @param ghostType the type of ghosts to create
     */
    public ElementGhostManager(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                              NeighborDetector<Key> neighborDetector,
                              GhostType ghostType) {
        this(spatialIndex, neighborDetector, ghostType, GhostAlgorithm.CONSERVATIVE, null, 0L);
    }
    
    /**
     * Create an element ghost manager with gRPC support.
     * 
     * @param spatialIndex the spatial index
     * @param neighborDetector the neighbor detector for this index type
     * @param ghostType the type of ghosts to create
     * @param ghostServiceClient gRPC client for remote ghost data fetching (null for local-only operation)
     * @param treeId tree identifier for distributed ghost requests
     */
    public ElementGhostManager(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                              NeighborDetector<Key> neighborDetector,
                              GhostType ghostType,
                              com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostServiceClient ghostServiceClient,
                              long treeId) {
        this(spatialIndex, neighborDetector, ghostType, GhostAlgorithm.CONSERVATIVE, ghostServiceClient, treeId);
    }
    
    /**
     * Create an element ghost manager with specified algorithm.
     * 
     * @param spatialIndex the spatial index
     * @param neighborDetector the neighbor detector for this index type
     * @param ghostType the type of ghosts to create
     * @param ghostAlgorithm the ghost creation algorithm to use
     */
    public ElementGhostManager(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                              NeighborDetector<Key> neighborDetector,
                              GhostType ghostType,
                              GhostAlgorithm ghostAlgorithm) {
        this(spatialIndex, neighborDetector, ghostType, ghostAlgorithm, null, 0L);
    }
    
    /**
     * Create an element ghost manager with full configuration.
     * 
     * @param spatialIndex the spatial index
     * @param neighborDetector the neighbor detector for this index type
     * @param ghostType the type of ghosts to create
     * @param ghostAlgorithm the ghost creation algorithm to use
     * @param ghostServiceClient gRPC client for remote ghost data fetching (null for local-only operation)
     * @param treeId tree identifier for distributed ghost requests
     */
    public ElementGhostManager(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                              NeighborDetector<Key> neighborDetector,
                              GhostType ghostType,
                              GhostAlgorithm ghostAlgorithm,
                              com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostServiceClient ghostServiceClient,
                              long treeId) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex);
        this.neighborDetector = Objects.requireNonNull(neighborDetector);
        this.ghostLayer = new GhostLayer<>(ghostType);
        this.ghostAlgorithm = Objects.requireNonNull(ghostAlgorithm);
        this.boundaryElements = new ConcurrentSkipListSet<>();
        this.processedElements = ConcurrentHashMap.newKeySet();
        this.elementOwners = new ConcurrentHashMap<>();
        this.ghostServiceClient = ghostServiceClient;
        this.treeId = treeId;
    }
    
    /**
     * Create ghost elements for the entire spatial index.
     * This identifies boundary elements and creates ghosts for them.
     */
    public void createGhostLayer() {
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
     * Get the ghost layer.
     * 
     * @return the ghost layer
     */
    public GhostLayer<Key, ID, Content> getGhostLayer() {
        return ghostLayer;
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
     * Set owner information for an element (for distributed support).
     * 
     * @param key the spatial key
     * @param ownerRank the owner process rank
     */
    public void setElementOwner(Key key, int ownerRank) {
        elementOwners.put(key, ownerRank);
    }
    
    /**
     * Get owner information for an element.
     * 
     * @param key the spatial key
     * @return owner rank, or 0 if local
     */
    public int getElementOwner(Key key) {
        return elementOwners.getOrDefault(key, 0);
    }
    
    /**
     * Set the ghost type for this manager.
     * This will update the ghost layer configuration.
     * 
     * @param ghostType the new ghost type
     */
    public void setGhostType(GhostType ghostType) {
        // Create new ghost layer with updated type
        ghostLayer.clear();
        // Note: GhostLayer is immutable regarding type, so we'd need to recreate
        // For now, we'll just clear and let the next createGhostLayer() use the new type
        log.info("Ghost type changed to: {}", ghostType);
    }
    
    /**
     * Set the neighbor detector for this manager.
     * 
     * @param neighborDetector the neighbor detector to use
     */
    public void setNeighborDetector(NeighborDetector<Key> neighborDetector) {
        // This method would be used if we needed to change the neighbor detector
        // For now, it's set in the constructor, but we can add this for completeness
        log.warn("setNeighborDetector called but neighbor detector is set in constructor");
    }
    
    // Private helper methods
    
    private void identifyBoundaryElements() {
        if (neighborDetector == null) {
            log.warn("Cannot identify boundary elements - neighbor detector not set");
            return;
        }
        
        boundaryElements.clear();
        
        // Get all spatial keys from the spatial index
        var spatialKeys = spatialIndex.getSpatialKeys();
        
        log.debug("Identifying boundary elements from {} total elements", spatialKeys.size());
        
        for (var key : spatialKeys) {
            if (isElementAtBoundary(key)) {
                boundaryElements.add(key);
                log.debug("Added boundary element: {}", key);
            }
        }
        
        log.debug("Identified {} boundary elements out of {} total elements", 
                  boundaryElements.size(), spatialKeys.size());
    }
    
    private void createGhostsForElement(Key key) {
        if (processedElements.contains(key)) {
            return;
        }
        
        // Find neighbors based on ghost type and algorithm
        var neighbors = findNeighborsForGhostCreation(key);
        
        for (var neighborKey : neighbors) {
            // Check if neighbor exists in our spatial index
            if (!spatialIndex.containsSpatialKey(neighborKey)) {
                // This neighbor doesn't exist locally - could be a ghost
                var ownerRank = getElementOwner(neighborKey);
                if (ownerRank != getCurrentRank()) {
                    // This is owned by another process - create ghost element
                    createGhostElement(neighborKey, ownerRank);
                }
            }
        }
        
        processedElements.add(key);
    }
    
    /**
     * Find neighbors for ghost creation based on the configured algorithm.
     */
    private Set<Key> findNeighborsForGhostCreation(Key key) {
        var neighbors = new HashSet<Key>();
        
        switch (ghostAlgorithm) {
            case MINIMAL -> {
                // Only direct neighbors
                neighbors.addAll(neighborDetector.findNeighbors(key, ghostLayer.getGhostType()));
            }
            case CONSERVATIVE -> {
                // Direct neighbors and their neighbors
                var directNeighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());
                neighbors.addAll(directNeighbors);
                
                // Add neighbors of neighbors (limited depth)
                for (var neighbor : directNeighbors) {
                    var secondLevelNeighbors = neighborDetector.findNeighbors(neighbor, ghostLayer.getGhostType());
                    neighbors.addAll(secondLevelNeighbors);
                }
            }
            case AGGRESSIVE -> {
                // Extensive neighbor search with multiple levels
                var currentLevel = Set.of(key);
                var visited = new HashSet<Key>();
                
                // Search up to 3 levels deep
                for (int level = 0; level < 3; level++) {
                    var nextLevel = new HashSet<Key>();
                    for (var currentKey : currentLevel) {
                        if (!visited.contains(currentKey)) {
                            var levelNeighbors = neighborDetector.findNeighbors(currentKey, ghostLayer.getGhostType());
                            neighbors.addAll(levelNeighbors);
                            nextLevel.addAll(levelNeighbors);
                            visited.add(currentKey);
                        }
                    }
                    currentLevel = nextLevel;
                }
            }
            case ADAPTIVE -> {
                // Start with conservative, could be enhanced with usage statistics
                // For now, use conservative approach
                var directNeighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());
                neighbors.addAll(directNeighbors);
                
                for (var neighbor : directNeighbors) {
                    var secondLevelNeighbors = neighborDetector.findNeighbors(neighbor, ghostLayer.getGhostType());
                    neighbors.addAll(secondLevelNeighbors);
                }
            }
            case CUSTOM -> {
                // For custom algorithms, delegate to a pluggable strategy
                // For now, fall back to conservative
                log.warn("CUSTOM ghost algorithm not implemented, using CONSERVATIVE");
                var directNeighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());
                neighbors.addAll(directNeighbors);
            }
        }
        
        return neighbors;
    }
    
    private void createGhostElement(Key neighborKey, int ownerRank) {
        // Create a ghost element for this non-local neighbor
        
        if (ghostServiceClient != null && treeId != 0L) {
            // Use gRPC to fetch real ghost data from remote process
            try {
                var ghostBatch = ghostServiceClient.requestGhosts(
                    ownerRank, 
                    treeId, 
                    ghostLayer.getGhostType(), 
                    List.of(neighborKey)
                );
                
                if (ghostBatch != null) {
                    // Process the received ghost elements
                    for (var ghostElementProto : ghostBatch.getElementsList()) {
                        processReceivedGhostElement(ghostElementProto);
                    }
                    log.debug("Successfully fetched ghost element for neighbor key: {} from owner: {}", 
                             neighborKey, ownerRank);
                } else {
                    log.warn("Failed to fetch ghost element for neighbor key: {} from owner: {}", 
                            neighborKey, ownerRank);
                }
                
            } catch (Exception e) {
                log.error("Error fetching ghost element for neighbor key: {} from owner: {}: {}", 
                         neighborKey, ownerRank, e.getMessage(), e);
            }
        } else {
            // Local-only mode: create a placeholder ghost element for testing
            log.debug("Creating placeholder ghost element for neighbor key: {} from owner: {} (no gRPC client)", 
                     neighborKey, ownerRank);
            
            // Create a minimal ghost element with synthetic data
            createPlaceholderGhostElement(neighborKey, ownerRank);
        }
    }
    
    /**
     * Process a ghost element received from a remote process via gRPC.
     */
    private void processReceivedGhostElement(com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement ghostElementProto) {
        try {
            // Convert protobuf ghost element to local representation
            var spatialKey = com.hellblazer.luciferase.lucien.SpatialKey.fromProtoSpatialKey(ghostElementProto.getSpatialKey());
            
            // Convert protobuf position to javax.vecmath.Point3f
            var protoPos = ghostElementProto.getPosition();
            var position = new Point3f(protoPos.getX(), protoPos.getY(), protoPos.getZ());
            
            // For now, we create placeholder ID and Content since we can't deserialize them generically
            // In a real implementation, these would be properly deserialized from protobuf
            @SuppressWarnings("unchecked")
            ID placeholderEntityId = (ID) new com.hellblazer.luciferase.lucien.entity.UUIDEntityID(java.util.UUID.fromString(ghostElementProto.getEntityId()));
            
            @SuppressWarnings("unchecked") 
            Content placeholderContent = (Content) ghostElementProto.getContent().toByteArray();
            
            // Create a proper ghost element with the received data
            var ghostElement = new GhostElement<Key, ID, Content>(
                (Key) spatialKey,
                placeholderEntityId,
                placeholderContent,
                position,
                ghostElementProto.getOwnerRank(),
                treeId
            );
            
            // Add to ghost layer
            ghostLayer.addGhostElement(ghostElement);
            
            log.trace("Processed ghost element with key: {} from rank: {}", 
                     spatialKey, ghostElementProto.getOwnerRank());
                     
        } catch (Exception e) {
            log.error("Error processing received ghost element: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Create a placeholder ghost element for local testing without gRPC.
     */
    private void createPlaceholderGhostElement(Key neighborKey, int ownerRank) {
        // Create a minimal ghost element for testing purposes
        
        // Create placeholder ID and content
        @SuppressWarnings("unchecked")
        ID placeholderEntityId = (ID) new com.hellblazer.luciferase.lucien.entity.UUIDEntityID(java.util.UUID.nameUUIDFromBytes(("ghost-" + neighborKey.toString()).getBytes()));
        
        @SuppressWarnings("unchecked") 
        Content placeholderContent = (Content) new byte[0];
        
        // Create a placeholder position (origin)
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        
        var ghostElement = new GhostElement<Key, ID, Content>(
            neighborKey,
            placeholderEntityId,
            placeholderContent,
            position,
            ownerRank,
            treeId
        );
        
        // Add to ghost layer
        ghostLayer.addGhostElement(ghostElement);
        
        log.trace("Created placeholder ghost element with key: {} for rank: {}", 
                 neighborKey, ownerRank);
    }
    
    private void removeGhostsForElement(Key key) {
        // Remove any ghosts associated with this element
        // This would be implemented based on the ghost layer API
    }
    
    private void updateNeighborGhosts(Key key) {
        var neighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());
        
        for (var neighbor : neighbors) {
            if (processedElements.contains(neighbor)) {
                // Reprocess this neighbor to update its ghosts
                processedElements.remove(neighbor);
                createGhostsForElement(neighbor);
            }
        }
    }
    
    private boolean affectsGhosts(Key key) {
        // Check if this element is a ghost for any boundary element
        var neighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());
        
        for (var neighbor : neighbors) {
            if (boundaryElements.contains(neighbor)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get the current process rank (for single-process testing, return 0).
     * In a distributed implementation, this would return the actual MPI rank.
     */
    private int getCurrentRank() {
        // For single-process testing, always return rank 0
        // In distributed mode, this would return the actual process rank
        return 0;
    }
    
    /**
     * Check if an element is at a boundary by using the neighbor detector.
     * An element is at a boundary if it has missing neighbors in any direction.
     */
    private boolean isElementAtBoundary(Key key) {
        if (neighborDetector == null) {
            return false;
        }
        
        // Use the neighbor detector to check boundary status
        var boundaryDirections = neighborDetector.getBoundaryDirections(key);
        return !boundaryDirections.isEmpty();
    }
    
}