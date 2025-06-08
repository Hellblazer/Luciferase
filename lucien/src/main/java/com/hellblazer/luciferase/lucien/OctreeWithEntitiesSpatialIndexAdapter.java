package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.Entity;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Adapter that makes OctreeWithEntities implement the SpatialIndex interface.
 * This adapter enforces single-content-per-node semantics to match the SpatialIndex contract.
 * 
 * @param <ID> the type of entity ID
 * @param <Content> the type of content stored in the octree
 */
public class OctreeWithEntitiesSpatialIndexAdapter<ID extends EntityID, Content> implements SpatialIndex<Content> {
    
    private final OctreeWithEntities<ID, Content> octreeWithEntities;
    
    /**
     * Create an adapter with a new OctreeWithEntities instance
     */
    public OctreeWithEntitiesSpatialIndexAdapter(EntityIDGenerator<ID> idGenerator) {
        this.octreeWithEntities = new OctreeWithEntities<>(idGenerator, 1, Constants.getMaxRefinementLevel(),
                                                          new EntitySpanningPolicy(), true);
    }
    
    /**
     * Create an adapter with specified parameters
     */
    public OctreeWithEntitiesSpatialIndexAdapter(EntityIDGenerator<ID> idGenerator, 
                                                 int maxEntitiesPerNode, 
                                                 byte maxLevel) {
        this.octreeWithEntities = new OctreeWithEntities<>(idGenerator, maxEntitiesPerNode, maxLevel, 
                                                          new EntitySpanningPolicy(), true);
    }
    
    /**
     * Wrap an existing OctreeWithEntities instance
     */
    public OctreeWithEntitiesSpatialIndexAdapter(OctreeWithEntities<ID, Content> octreeWithEntities) {
        if (!octreeWithEntities.singleContentMode) {
            throw new IllegalArgumentException("OctreeWithEntities must be in single content mode for SpatialIndex adapter");
        }
        this.octreeWithEntities = octreeWithEntities;
    }
    
    @Override
    public long insert(Point3f position, byte level, Content content) {
        // In single content mode, check if there's already an entity at this position
        var existingEntities = octreeWithEntities.lookup(position, level);
        if (!existingEntities.isEmpty()) {
            // Remove existing entities to maintain single content behavior
            for (ID existingId : existingEntities) {
                octreeWithEntities.removeEntity(existingId);
            }
        }
        
        // Insert and get the entity ID
        ID entityId = octreeWithEntities.insert(position, level, content);
        
        // The Morton index should match what OctreeWithEntities actually stores
        // We need to find the actual Morton index used in the spatial index
        for (var entry : octreeWithEntities.spatialIndex.entrySet()) {
            long mortonIndex = entry.getKey();
            var node = entry.getValue();
            if (node.getEntityIds().contains(entityId)) {
                return mortonIndex;
            }
        }
        
        // Fallback: calculate it ourselves (should not happen)
        throw new IllegalStateException("Entity was inserted but not found in spatial index");
    }
    
    @Override
    public Content lookup(Point3f position, byte level) {
        // In single content mode, return the first (and only) entity at this position
        var entities = octreeWithEntities.lookup(position, level);
        if (entities.isEmpty()) {
            return null;
        }
        return octreeWithEntities.getEntity(entities.get(0));
    }
    
    @Override
    public Content get(long mortonIndex) {
        // Check if this Morton index exists in the spatial index
        var node = octreeWithEntities.spatialIndex.get(mortonIndex);
        if (node == null || node.isEmpty()) {
            return null;
        }
        
        // In single content mode, get the first (and only) entity
        ID entityId = node.getEntityIds().iterator().next();
        return octreeWithEntities.getEntity(entityId);
    }
    
    @Override
    public Stream<SpatialNode<Content>> nodes() {
        // Convert entity storage to spatial nodes
        return octreeWithEntities.spatialIndex.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> {
                long mortonIndex = entry.getKey();
                var node = entry.getValue();
                // In single content mode, get the first (and only) entity
                ID entityId = node.getEntityIds().iterator().next();
                Content content = octreeWithEntities.getEntity(entityId);
                return new SpatialNode<>(mortonIndex, content);
            });
    }
    
    @Override
    public Stream<SpatialNode<Content>> boundedBy(Spatial spatial) {
        // Use the octree's spatial range query
        return octreeWithEntities.spatialRangeQuery(spatial, false);
    }
    
    @Override
    public Stream<SpatialNode<Content>> bounding(Spatial volume) {
        // Use spatial range query with intersection
        return octreeWithEntities.spatialRangeQuery(volume, true);
    }
    
    @Override
    public SpatialNode<Content> enclosing(Spatial volume) {
        // Find the minimum enclosing node
        var bounds = octreeWithEntities.getVolumeBounds(volume);
        if (bounds == null) {
            return null;
        }
        
        byte level = octreeWithEntities.findMinimumContainingLevel(bounds);
        float midX = (bounds.minX() + bounds.maxX()) / 2.0f;
        float midY = (bounds.minY() + bounds.maxY()) / 2.0f;
        float midZ = (bounds.minZ() + bounds.maxZ()) / 2.0f;
        Point3f center = new Point3f(midX, midY, midZ);
        
        Content content = lookup(center, level);
        if (content != null) {
            long mortonIndex = Constants.calculateMortonIndex(center, level);
            return new SpatialNode<>(mortonIndex, content);
        }
        return null;
    }
    
    @Override
    public SpatialNode<Content> enclosing(Tuple3i point, byte level) {
        Point3f position = new Point3f(point.x, point.y, point.z);
        Content content = lookup(position, level);
        if (content != null) {
            long mortonIndex = Constants.calculateMortonIndex(position, level);
            return new SpatialNode<>(mortonIndex, content);
        }
        return null;
    }
    
    @Override
    public NavigableMap<Long, Content> getMap() {
        // Convert spatial index to navigable map
        NavigableMap<Long, Content> mortonMap = new TreeMap<>();
        
        for (var entry : octreeWithEntities.spatialIndex.entrySet()) {
            long mortonIndex = entry.getKey();
            var node = entry.getValue();
            
            if (!node.isEmpty()) {
                // In single content mode, get the first (and only) entity
                ID entityId = node.getEntityIds().iterator().next();
                Content content = octreeWithEntities.getEntity(entityId);
                mortonMap.put(mortonIndex, content);
            }
        }
        
        return mortonMap;
    }
    
    @Override
    public int size() {
        return octreeWithEntities.spatialIndex.size();
    }
    
    @Override
    public boolean hasNode(long mortonIndex) {
        var node = octreeWithEntities.spatialIndex.get(mortonIndex);
        return node != null && !node.isEmpty();
    }
    
    @Override
    public SpatialIndexStats getStats() {
        // Convert entity stats to spatial index stats
        var entityStats = octreeWithEntities.getEntityStats();
        return new SpatialIndexStats(entityStats.nodeCount, entityStats.entityCount);
    }
    
    /**
     * Get the underlying OctreeWithEntities instance
     */
    public OctreeWithEntities<ID, Content> getEntityOctree() {
        return octreeWithEntities;
    }
    
    /**
     * Get cube from Morton index
     * @param index the Morton index
     * @return the spatial cube at that index
     */
    public Spatial.Cube locate(long index) {
        return new Spatial.Cube(index);
    }
}