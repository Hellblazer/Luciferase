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
package com.hellblazer.luciferase.lucien.entity;

import com.hellblazer.luciferase.lucien.octree.Octree;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utilities for testing multi-entity capabilities of Octree
 *
 * @author hal.hildebrand
 */
public class EntityTestUtils {

    /**
     * Count total entities across all locations
     */
    public static <ID extends EntityID, Content> int countTotalEntities(Octree<ID, Content> octree) {
        return octree.entityCount();
    }

    /**
     * Create test data with collision scenarios
     */
    public static List<MultiEntityLocation<String>> createCollisionTestData() {
        List<MultiEntityLocation<String>> locations = new ArrayList<>();

        // Multiple entities at the same exact position
        locations.add(
        new MultiEntityLocation<>(new Point3f(100, 100, 100), (byte) 10, "Entity1", "Entity2", "Entity3"));

        // Different entities at nearby positions that map to same Morton code
        Point3f basePos = new Point3f(200, 200, 200);
        locations.add(new MultiEntityLocation<>(basePos, (byte) 8, "GroupA1", "GroupA2"));

        // Single entity at a different location
        locations.add(new MultiEntityLocation<>(new Point3f(300, 300, 300), (byte) 10, "Singleton"));

        // Dense cluster of entities
        for (int i = 0; i < 5; i++) {
            locations.add(new MultiEntityLocation<>(new Point3f(400, 400, 400), (byte) 12, "Dense" + i));
        }

        return locations;
    }

    /**
     * Create an Octree populated with multi-entity test data
     */
    public static <Content> Octree<LongEntityID, Content> createMultiEntityOctree(
    List<MultiEntityLocation<Content>> locations) {
        Octree<LongEntityID, Content> octree = new Octree<>(new SequentialLongIDGenerator());

        for (MultiEntityLocation<Content> location : locations) {
            for (Content entity : location.entities) {
                octree.insert(location.position, location.level, entity);
            }
        }

        return octree;
    }

    /**
     * Create an Octree with entities that have specific IDs
     */
    public static <Content> Octree<LongEntityID, Content> createMultiEntityOctreeWithIds(
    Map<LongEntityID, MultiEntityLocation<Content>> entityMap) {
        Octree<LongEntityID, Content> octree = new Octree<>(new SequentialLongIDGenerator());

        for (Map.Entry<LongEntityID, MultiEntityLocation<Content>> entry : entityMap.entrySet()) {
            LongEntityID id = entry.getKey();
            MultiEntityLocation<Content> location = entry.getValue();
            if (!location.entities.isEmpty()) {
                octree.insert(id, location.position, location.level, location.entities.get(0));
            }
        }

        return octree;
    }

    /**
     * Create test data for entity bounds and spanning scenarios
     */
    public static List<MultiEntityLocation<String>> createSpanningTestData() {
        List<MultiEntityLocation<String>> locations = new ArrayList<>();

        // Large entity that should span multiple nodes
        locations.add(new MultiEntityLocation<>(new Point3f(500, 500, 500), (byte) 8, "LargeBuilding"));

        // Overlapping entities
        locations.add(new MultiEntityLocation<>(new Point3f(510, 510, 510), (byte) 8, "OverlappingStructure1",
                                                "OverlappingStructure2"));

        return locations;
    }

    /**
     * Get all unique spatial locations that have entities
     */
    public static <ID extends EntityID, Content> Set<Point3f> getUniqueLocations(Octree<ID, Content> octree,
                                                                                 byte level) {
        // Use getEntitiesWithPositions to get position info
        return new HashSet<>(octree.getEntitiesWithPositions().values());
    }

    /**
     * Verify that all entities at a location are present
     */
    public static <ID extends EntityID, Content> boolean verifyEntitiesAtLocation(Octree<ID, Content> octree,
                                                                                  Point3f position, byte level,
                                                                                  Collection<Content> expectedEntities) {

        List<ID> ids = octree.lookup(position, level);
        if (ids.size() != expectedEntities.size()) {
            return false;
        }

        Set<Content> actualEntities = ids.stream().map(octree::getEntity).collect(Collectors.toSet());

        return actualEntities.equals(new HashSet<>(expectedEntities));
    }

    /**
     * Test data representing multiple entities at the same location
     */
    public static class MultiEntityLocation<Content> {
        public final Point3f       position;
        public final byte          level;
        public final List<Content> entities;

        public MultiEntityLocation(Point3f position, byte level, List<Content> entities) {
            this.position = position;
            this.level = level;
            this.entities = new ArrayList<>(entities);
        }

        public MultiEntityLocation(Point3f position, byte level, Content... entities) {
            this(position, level, Arrays.asList(entities));
        }
    }

    /**
     * Helper to create a simple multi-entity adapter for search algorithms
     */
    public static class MultiEntityAdapter<Content> {
        private final Octree<LongEntityID, Content> octree;

        public MultiEntityAdapter(Octree<LongEntityID, Content> octree) {
            this.octree = octree;
        }

        /**
         * Get all entities at all Morton indices as a flattened list This allows search algorithms to work with
         * multiple entities
         */
        public List<EntityWithLocation<Content>> getAllEntitiesWithLocations() {
            List<EntityWithLocation<Content>> result = new ArrayList<>();

            // Use getEntitiesWithPositions to get entity locations
            Map<LongEntityID, Point3f> positions = octree.getEntitiesWithPositions();

            for (Map.Entry<LongEntityID, Point3f> entry : positions.entrySet()) {
                LongEntityID id = entry.getKey();
                Content content = octree.getEntity(id);
                if (content != null) {
                    result.add(new EntityWithLocation<>(id, content, entry.getValue(), (byte) 0));
                }
            }

            return result;
        }
    }

    /**
     * Container for entity with its spatial location
     */
    public static class EntityWithLocation<Content> {
        public final LongEntityID id;
        public final Content      content;
        public final Point3f      position;
        public final byte         level;

        public EntityWithLocation(LongEntityID id, Content content, Point3f position, byte level) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.level = level;
        }
    }
}
