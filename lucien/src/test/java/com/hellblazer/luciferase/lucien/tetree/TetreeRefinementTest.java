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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.refinement.EntityDensityRefinementCriteria;
import com.hellblazer.luciferase.lucien.refinement.SpatialRegionRefinementCriteria;
import com.hellblazer.luciferase.lucien.refinement.UniformRefinementCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for Tetree refinement operations
 *
 * @author hal.hildebrand
 */
public class TetreeRefinementTest {
    private static final int WORLD_SIZE = 1000;
    private static final int MAX_ENTITIES_PER_NODE = 8;
    private static final byte MAX_DEPTH = 10;
    
    private Tetree<LongEntityID, String> tetree;
    
    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(
            new SequentialLongIDGenerator(),
            MAX_ENTITIES_PER_NODE,
            MAX_DEPTH
        );
    }
    
    @Test
    void testUniformRefinement() {
        // Add some entities
        for (int i = 0; i < 20; i++) {
            Point3f pos = new Point3f(100 + i * 10, 100 + i * 10, 100 + i * 10);
            tetree.insert(pos, (byte) 3, "Entity-" + i);
        }
        
        // Refine uniformly to level 5
        var stats = tetree.refineUniform(5);
        
        // Verify statistics
        assertNotNull(stats);
        assertTrue(stats.getNodesRefined() > 0, "Should have refined some nodes");
        assertTrue(stats.getEntitiesRelocated() > 0, "Should have relocated entities");
        assertTrue(stats.getOperationTimeMillis() >= 0, "Should have valid operation time");
        
        System.out.println(stats.getSummary());
    }
    
    @Test
    void testAdaptiveRefinementWithDensity() {
        // Create a dense cluster of entities
        for (int i = 0; i < 50; i++) {
            // Dense cluster at (200, 200, 200)
            Point3f pos = new Point3f(
                200 + (float)(Math.random() * 10),
                200 + (float)(Math.random() * 10),
                200 + (float)(Math.random() * 10)
            );
            tetree.insert(pos, (byte) 3, "Dense-" + i);
        }
        
        // Add sparse entities elsewhere
        for (int i = 0; i < 10; i++) {
            Point3f pos = new Point3f(
                500 + i * 20,
                500 + i * 20,
                500 + i * 20
            );
            tetree.insert(pos, (byte) 3, "Sparse-" + i);
        }
        
        // Create density-based refinement criteria
        var criteria = new EntityDensityRefinementCriteria<LongEntityID>(
            0.5f,  // refine if density > 0.5 entities per unit volume
            0.1f,  // coarsen if density < 0.1 entities per unit volume
            MAX_ENTITIES_PER_NODE,
            3,     // min level
            8      // max level
        );
        
        var stats = tetree.refineAdaptive(criteria);
        
        // Verify refinement occurred
        assertNotNull(stats);
        assertTrue(stats.getNodesRefined() > 0, "Should have refined dense regions");
        System.out.println(stats.getSummary());
    }
    
    @Test
    void testSpatialRegionRefinement() {
        // Add entities across the space
        for (int i = 0; i < 30; i++) {
            Point3f pos = new Point3f(
                100 + (float)(Math.random() * 800),
                100 + (float)(Math.random() * 800),
                100 + (float)(Math.random() * 800)
            );
            tetree.insert(pos, (byte) 3, "Entity-" + i);
        }
        
        // Create spatial region criteria
        var criteria = new SpatialRegionRefinementCriteria<LongEntityID>(5); // default max level
        
        // Add a sphere region that should be refined to level 8
        criteria.addSphereRegion(new Point3f(300, 300, 300), 100.0f, 8);
        
        // Add a box region that should be refined to level 7
        criteria.addBoxRegion(
            new Point3f(600, 600, 600),
            new Point3f(700, 700, 700),
            7
        );
        
        var stats = tetree.refineAdaptive(criteria);
        
        // Verify refinement
        assertNotNull(stats);
        System.out.println(stats.getSummary());
        
        // The regions should have been refined
        assertTrue(stats.getMaxLevelReached() >= 7, "Should reach at least level 7");
    }
    
    @Test
    void testRefinementWithCallbacks() {
        // Add entities
        for (int i = 0; i < 15; i++) {
            Point3f pos = new Point3f(200 + i * 5, 200 + i * 5, 200 + i * 5);
            tetree.insert(pos, (byte) 3, "Entity-" + i);
        }
        
        // Create criteria with callbacks
        var criteria = new UniformRefinementCriteria<LongEntityID>(6) {
            @Override
            public void onRefinementStart() {
                System.out.println("Starting refinement to level 6...");
            }
            
            @Override
            public void onRefinementComplete(com.hellblazer.luciferase.lucien.refinement.RefinementStatistics statistics) {
                System.out.println("Refinement complete!");
                System.out.println("Nodes refined: " + statistics.getNodesRefined());
                System.out.println("Entities relocated: " + statistics.getEntitiesRelocated());
            }
        };
        
        var stats = tetree.refineAdaptive(criteria);
        assertNotNull(stats);
    }
    
    @Test
    void testRefinementWithBoundedEntities() {
        // Add bounded entities
        for (int i = 0; i < 10; i++) {
            Point3f center = new Point3f(300 + i * 50, 300, 300);
            Point3f min = new Point3f(center.x - 20, center.y - 20, center.z - 20);
            Point3f max = new Point3f(center.x + 20, center.y + 20, center.z + 20);
            EntityBounds bounds = new EntityBounds(min, max);
            
            var id = new LongEntityID(i + 1000); // Create ID explicitly
            tetree.insert(id, center, (byte) 3, "BoundedEntity-" + i, bounds);
        }
        
        // Refine based on entity size
        var stats = tetree.refineUniform(6);
        
        assertNotNull(stats);
        assertTrue(stats.getNodesRefined() > 0, "Should refine nodes with bounded entities");
        System.out.println(stats.getSummary());
    }
    
    @Test
    void testIncrementalRefinement() {
        // Start with coarse level
        for (int i = 0; i < 20; i++) {
            Point3f pos = new Point3f(
                200 + (float)(Math.random() * 100),
                200 + (float)(Math.random() * 100),
                200 + (float)(Math.random() * 100)
            );
            tetree.insert(pos, (byte) 2, "Entity-" + i);
        }
        
        // Refine incrementally
        var stats1 = tetree.refineUniform(4);
        System.out.println("First refinement to level 4:");
        System.out.println(stats1.getSummary());
        
        // Add more entities
        for (int i = 20; i < 40; i++) {
            Point3f pos = new Point3f(
                250 + (float)(Math.random() * 50),
                250 + (float)(Math.random() * 50),
                250 + (float)(Math.random() * 50)
            );
            tetree.insert(pos, (byte) 4, "Entity-" + i);
        }
        
        // Refine again to higher level
        var stats2 = tetree.refineUniform(6);
        System.out.println("\nSecond refinement to level 6:");
        System.out.println(stats2.getSummary());
        
        assertTrue(stats2.getMaxLevelReached() >= 6, "Should reach level 6");
    }
}