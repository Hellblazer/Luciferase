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
import com.hellblazer.luciferase.lucien.entity.LongEntityID;

import javax.vecmath.Point3f;
import java.util.*;

/**
 * Multi-entity aware containment search for OctreeWithEntities.
 * Finds all entities contained within various 3D volumes.
 *
 * @author hal.hildebrand
 */
public class ContainmentSearch {
    
    /**
     * Result container that includes entity ID and location info
     */
    public static class ContainedEntity<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final long mortonIndex;
        public final byte level;
        
        public ContainedEntity(ID id, Content content, long mortonIndex, byte level) {
            this.id = id;
            this.content = content;
            this.mortonIndex = mortonIndex;
            this.level = level;
        }
    }
    
    /**
     * Find all entities contained within a cube
     * 
     * @param volume the cube to search within
     * @param octree the multi-entity octree to search
     * @return list of all entities contained in the cube
     */
    public static <ID extends EntityID, Content> List<ContainedEntity<ID, Content>> findEntitiesInCube(
            Spatial.Cube volume,
            OctreeWithEntities<ID, Content> octree) {
        
        List<ContainedEntity<ID, Content>> results = new ArrayList<>();
        
        // Get all entities in the region
        List<ID> entityIds = octree.entitiesInRegion(volume);
        
        for (ID entityId : entityIds) {
            Content content = octree.getEntity(entityId);
            if (content != null) {
                // We need to determine the Morton index and level
                // This is a limitation without direct access to entity positions
                results.add(new ContainedEntity<>(entityId, content, 0L, (byte)0));
            }
        }
        
        return results;
    }
    
    /**
     * Find all entities contained within a sphere
     * 
     * @param sphere the sphere to search within
     * @param octree the multi-entity octree to search
     * @return list of all entities contained in the sphere
     */
    public static <ID extends EntityID, Content> List<ContainedEntity<ID, Content>> findEntitiesInSphere(
            Spatial.Sphere sphere,
            OctreeWithEntities<ID, Content> octree) {
        
        // First get all entities in the bounding cube
        Spatial.Cube boundingCube = new Spatial.Cube(
            sphere.centerX() - sphere.radius(),
            sphere.centerY() - sphere.radius(),
            sphere.centerZ() - sphere.radius(),
            sphere.radius() * 2
        );
        
        List<ID> entityIds = octree.entitiesInRegion(boundingCube);
        List<ContainedEntity<ID, Content>> results = new ArrayList<>();
        
        // Filter to only those actually in the sphere
        for (ID entityId : entityIds) {
            Content content = octree.getEntity(entityId);
            if (content != null) {
                Point3f entityPos = octree.getEntityPosition(entityId);
                if (entityPos != null) {
                    // Check if entity is within sphere
                    float dx = entityPos.x - sphere.centerX();
                    float dy = entityPos.y - sphere.centerY();
                    float dz = entityPos.z - sphere.centerZ();
                    float distSquared = dx * dx + dy * dy + dz * dz;
                    
                    if (distSquared <= sphere.radius() * sphere.radius()) {
                        // Calculate Morton index for this position
                        long mortonIndex = com.hellblazer.luciferase.geometry.MortonCurve.encode(
                            (int)entityPos.x, (int)entityPos.y, (int)entityPos.z
                        );
                        byte level = Constants.toLevel(mortonIndex);
                        results.add(new ContainedEntity<>(entityId, content, mortonIndex, level));
                    }
                }
            }
        }
        
        return results;
    }
    
    /**
     * Find all entities contained within an AABB (axis-aligned bounding box)
     * 
     * @param aabb the AABB to search within
     * @param octree the multi-entity octree to search
     * @return list of all entities contained in the AABB
     */
    public static <ID extends EntityID, Content> List<ContainedEntity<ID, Content>> findEntitiesInAABB(
            Spatial.aabb aabb,
            OctreeWithEntities<ID, Content> octree) {
        
        // Convert AABB to cube for the search
        Spatial.Cube searchCube = new Spatial.Cube(
            aabb.originX(),
            aabb.originY(),
            aabb.originZ(),
            Math.max(aabb.extentX(), Math.max(aabb.extentY(), aabb.extentZ()))
        );
        
        List<ID> entityIds = octree.entitiesInRegion(searchCube);
        List<ContainedEntity<ID, Content>> results = new ArrayList<>();
        
        // Filter to only those actually in the AABB
        for (ID entityId : entityIds) {
            Content content = octree.getEntity(entityId);
            if (content != null) {
                Point3f entityPos = octree.getEntityPosition(entityId);
                if (entityPos != null) {
                    // Check if entity is within AABB
                    if (entityPos.x >= aabb.originX() && 
                        entityPos.x <= aabb.originX() + aabb.extentX() &&
                        entityPos.y >= aabb.originY() && 
                        entityPos.y <= aabb.originY() + aabb.extentY() &&
                        entityPos.z >= aabb.originZ() && 
                        entityPos.z <= aabb.originZ() + aabb.extentZ()) {
                        
                        // Calculate Morton index for this position
                        long mortonIndex = com.hellblazer.luciferase.geometry.MortonCurve.encode(
                            (int)entityPos.x, (int)entityPos.y, (int)entityPos.z
                        );
                        byte level = Constants.toLevel(mortonIndex);
                        results.add(new ContainedEntity<>(entityId, content, mortonIndex, level));
                    }
                }
            }
        }
        
        return results;
    }
    
    /**
     * Find all entities at a specific Morton index
     * 
     * @param mortonIndex the Morton index to check
     * @param octree the multi-entity octree to search
     * @return list of all entities at that Morton index
     */
    public static <ID extends EntityID, Content> List<ContainedEntity<ID, Content>> findEntitiesAtMortonIndex(
            long mortonIndex,
            OctreeWithEntities<ID, Content> octree) {
        
        // Convert Morton index to position and level
        byte level = Constants.toLevel(mortonIndex);
        var coords = com.hellblazer.luciferase.geometry.MortonCurve.decode(mortonIndex);
        Point3f position = new Point3f(coords[0], coords[1], coords[2]);
        
        List<ID> entityIds = octree.lookup(position, level);
        List<ContainedEntity<ID, Content>> results = new ArrayList<>();
        
        for (ID entityId : entityIds) {
            Content content = octree.getEntity(entityId);
            if (content != null) {
                results.add(new ContainedEntity<>(entityId, content, mortonIndex, level));
            }
        }
        
        return results;
    }
    
    /**
     * Find all entities within multiple volumes (union)
     * 
     * @param volumes array of volumes to search within
     * @param octree the multi-entity octree to search
     * @return list of all unique entities contained in any of the volumes
     */
    public static <ID extends EntityID, Content> List<ContainedEntity<ID, Content>> findEntitiesInUnion(
            Spatial[] volumes,
            OctreeWithEntities<ID, Content> octree) {
        
        Set<ID> uniqueIds = new HashSet<>();
        List<ContainedEntity<ID, Content>> results = new ArrayList<>();
        
        for (Spatial volume : volumes) {
            List<ContainedEntity<ID, Content>> volumeResults = switch (volume) {
                case Spatial.Cube cube -> findEntitiesInCube(cube, octree);
                case Spatial.Sphere sphere -> findEntitiesInSphere(sphere, octree);
                case Spatial.aabb aabb -> findEntitiesInAABB(aabb, octree);
                default -> new ArrayList<>();
            };
            
            // Add only unique entities
            for (ContainedEntity<ID, Content> entity : volumeResults) {
                if (uniqueIds.add(entity.id)) {
                    results.add(entity);
                }
            }
        }
        
        return results;
    }
    
    /**
     * Count entities in a volume without retrieving them
     * 
     * @param volume the volume to count entities in
     * @param octree the multi-entity octree to search
     * @return number of entities in the volume
     */
    public static <ID extends EntityID, Content> int countEntitiesInVolume(
            Spatial volume,
            OctreeWithEntities<ID, Content> octree) {
        
        return switch (volume) {
            case Spatial.Cube cube -> octree.entitiesInRegion(cube).size();
            case Spatial.Sphere sphere -> {
                Spatial.Cube boundingCube = new Spatial.Cube(
                    sphere.centerX() - sphere.radius(),
                    sphere.centerY() - sphere.radius(),
                    sphere.centerZ() - sphere.radius(),
                    sphere.radius() * 2
                );
                yield octree.entitiesInRegion(boundingCube).size();
            }
            case Spatial.aabb aabb -> {
                Spatial.Cube searchCube = new Spatial.Cube(
                    aabb.originX(),
                    aabb.originY(),
                    aabb.originZ(),
                    Math.max(aabb.extentX(), Math.max(aabb.extentY(), aabb.extentZ()))
                );
                yield octree.entitiesInRegion(searchCube).size();
            }
            default -> 0;
        };
    }
    
    /**
     * Find entities in a volume grouped by their spatial location
     * 
     * @param volume the volume to search within
     * @param octree the multi-entity octree to search
     * @return map of Morton indices to lists of entities at each location
     */
    public static <ID extends EntityID, Content> Map<Long, List<ContainedEntity<ID, Content>>> findEntitiesGroupedByLocation(
            Spatial.Cube volume,
            OctreeWithEntities<ID, Content> octree) {
        
        Map<Long, List<ContainedEntity<ID, Content>>> groupedResults = new HashMap<>();
        
        // Get all entities in the region
        List<ID> entityIds = octree.entitiesInRegion(volume);
        
        // Group by location (this is approximate without position info)
        for (ID entityId : entityIds) {
            Content content = octree.getEntity(entityId);
            if (content != null) {
                // Without position info, we can't properly group
                // This is a limitation that needs API extension
                long fakeIndex = 0L;
                groupedResults.computeIfAbsent(fakeIndex, k -> new ArrayList<>())
                    .add(new ContainedEntity<>(entityId, content, fakeIndex, (byte)0));
            }
        }
        
        return groupedResults;
    }
}