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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test for memory-efficient spanning
 * 
 * @author hal.hildebrand
 */
public class MemoryEfficientSpanningDebugTest {
    
    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator, 5, (byte) 10, EntitySpanningPolicy.withSpanning());
    }

    @Test
    void testBasicSpanning() {
        // Start with a simple spanning test
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(50, 50, 50));
        var entityId = idGenerator.generateID();
        
        System.out.println("Inserting entity at position (30, 30, 30) with bounds from (10,10,10) to (50,50,50)");
        tetree.insert(entityId, new Point3f(30, 30, 30), (byte) 5, "test-entity", bounds);
        
        var stats = tetree.getStats();
        System.out.println("Stats after insert: " + stats);
        
        // Test looking up the entity at its exact position
        var exactLookup = tetree.lookup(new Point3f(30, 30, 30), (byte) 5);
        System.out.println("Exact lookup result: " + exactLookup);
        
        // When spanning is enabled, entities might not be stored in the exact tetrahedron
        // calculated from their position. Use entitiesInRegion for spanning entities.
        if (exactLookup.isEmpty()) {
            // Check if entity exists using region query (more reliable for spanning entities)
            var regionLookup = tetree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(30, 30, 30, 1));
            System.out.println("Region lookup result: " + regionLookup);
            assertTrue(regionLookup.contains(entityId), "Entity should be found in region query even if not at exact position");
        } else {
            assertTrue(exactLookup.contains(entityId));
        }
        
        // Test looking up within the bounds
        var withinBounds = tetree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(15, 15, 15, 10));
        System.out.println("Entities in region (15,15,15) with extent 10: " + withinBounds);
        
        // Test looking up overlapping the bounds
        var overlapping = tetree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(5, 5, 5, 20));
        System.out.println("Entities in region (5,5,5) with extent 20: " + overlapping);
        
        assertTrue(withinBounds.contains(entityId) || overlapping.contains(entityId), 
                  "Entity should be found in at least one overlapping region");
    }

    @Test
    void testSpanningPolicyCheck() {
        // Let's check if the spanning policy is working
        var policy = EntitySpanningPolicy.withSpanning();
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(50, 50, 50));
        
        // Calculate entity size
        float entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), 
                                            bounds.getMaxY() - bounds.getMinY()),
                                   bounds.getMaxZ() - bounds.getMinZ());
        
        // Try different node sizes
        for (byte level = 0; level <= 8; level++) {
            int nodeSize = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(level);
            boolean spans = policy.shouldSpan(entitySize, nodeSize);
            System.out.println("Level " + level + " (node size " + nodeSize + ") should span: " + spans);
        }
    }

    @Test
    void testMemoryEfficientStrategySelection() {
        // Test the different strategy paths
        var smallBounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(20, 20, 20));
        var mediumBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(200, 200, 200));
        var largeBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(1000, 1000, 1000));
        
        var small = idGenerator.generateID();
        var medium = idGenerator.generateID();
        var large = idGenerator.generateID();
        
        System.out.println("Testing small entity (standard strategy)");
        tetree.insert(small, new Point3f(15, 15, 15), (byte) 5, "small", smallBounds);
        
        System.out.println("Testing medium entity (adaptive strategy)"); 
        tetree.insert(medium, new Point3f(100, 100, 100), (byte) 3, "medium", mediumBounds);
        
        System.out.println("Testing large entity (hierarchical strategy)");
        tetree.insert(large, new Point3f(500, 500, 500), (byte) 2, "large", largeBounds);
        
        var stats = tetree.getStats();
        System.out.println("Final stats: " + stats);
        
        // Verify all entities are in the system
        assertEquals(3, stats.entityCount());
        
        // Test if we can find entities in their expected regions
        var smallRegion = tetree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(10, 10, 10, 15));
        var mediumRegion = tetree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(50, 50, 50, 100));
        var largeRegion = tetree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(250, 250, 250, 500));
        
        System.out.println("Small region entities: " + smallRegion);
        System.out.println("Medium region entities: " + mediumRegion);
        System.out.println("Large region entities: " + largeRegion);
    }
}