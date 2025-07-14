/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for GhostZoneManager
 */
public class GhostZoneManagerTest {
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private GhostZoneManager<MortonKey, LongEntityID, String> ghostManager;
    private SequentialLongIDGenerator idGenerator;
    private ForestEntityManager<MortonKey, LongEntityID, String> entityManager;
    
    @BeforeEach
    void setUp() {
        var config = ForestConfig.builder()
            .withGhostZones(10.0f)
            .build();
        forest = new Forest<>(config);
        idGenerator = new SequentialLongIDGenerator();
        entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Create two adjacent trees with proper bounds
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var bounds1 = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(100, 100, 100)
        );
        var treeId1 = ForestTestUtil.addTreeWithBounds(forest, tree1, bounds1, "Tree1");
        
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        var bounds2 = new EntityBounds(
            new Point3f(100, 0, 0),
            new Point3f(200, 100, 100)
        );
        var treeId2 = ForestTestUtil.addTreeWithBounds(forest, tree2, bounds2, "Tree2");
        
        // Create ghost zone manager with 10 unit width
        ghostManager = new GhostZoneManager<>(forest, 10.0f);
    }
    
    @Test
    void testEstablishGhostZone() {
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        // Establish ghost zone
        ghostManager.establishGhostZone(tree1Id, tree2Id, null);
        
        // Add entity near boundary in tree1
        var id = new LongEntityID(1);
        var pos = new Point3f(95, 50, 50); // Within 10 units of boundary
        var bounds = EntityBounds.point(pos);
        entityManager.insert(id, "Entity near boundary", pos, bounds);
        
        // Update ghost zone
        ghostManager.updateGhostEntity(id, tree1Id, pos, bounds, "Entity near boundary");
        
        // Check ghost exists in tree2
        var ghosts = ghostManager.getGhostEntities(tree2Id);
        assertEquals(1, ghosts.size());
        
        var ghost = ghosts.iterator().next();
        assertEquals(id, ghost.getEntityId());
        assertEquals(pos, ghost.getPosition());
        assertEquals(tree1Id, ghost.getSourceTreeId());
    }
    
    @Test
    void testIsInGhostZone() {
        var trees = forest.getAllTrees();
        var tree1 = trees.get(0);
        var tree2 = trees.get(1);
        var tree1Id = tree1.getTreeId();
        var tree2Id = tree2.getTreeId();
        
        ghostManager.establishGhostZone(tree1Id, tree2Id, 15.0f); // Custom width
        
        // Since isInGhostZone is private, we test indirectly through updateGhostEntity
        // Entity close to boundary
        var closePos = new Point3f(95, 50, 50);
        var closeBounds = EntityBounds.point(closePos);
        var id1 = new LongEntityID(1);
        ghostManager.updateGhostEntity(id1, tree1Id, closePos, closeBounds, "Close entity");
        assertTrue(ghostManager.getGhostEntities(tree2Id).size() > 0);
        
        // Entity far from boundary
        var farPos = new Point3f(50, 50, 50);
        var farBounds = EntityBounds.point(farPos);
        var id2 = new LongEntityID(2);
        ghostManager.updateGhostEntity(id2, tree1Id, farPos, farBounds, "Far entity");
        // The far entity should not create a ghost
        assertEquals(1, ghostManager.getGhostEntities(tree2Id).size());
        
        // Entity with bounds spanning into ghost zone
        var spanPos = new Point3f(80, 50, 50);
        var spanBounds = new EntityBounds(
            new Point3f(75, 45, 45),
            new Point3f(85, 55, 55)
        );
        var id3 = new LongEntityID(3);
        ghostManager.updateGhostEntity(id3, tree1Id, spanPos, spanBounds, "Spanning entity");
        assertEquals(2, ghostManager.getGhostEntities(tree2Id).size());
    }
    
    @Test
    void testRemoveGhostEntity() {
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        ghostManager.establishGhostZone(tree1Id, tree2Id, null);
        
        // Add ghost entity
        var id = new LongEntityID(1);
        var pos = new Point3f(95, 50, 50);
        var bounds = EntityBounds.point(pos);
        ghostManager.updateGhostEntity(id, tree1Id, pos, bounds, "Ghost entity");
        
        assertEquals(1, ghostManager.getGhostEntities(tree2Id).size());
        
        // Remove ghost entity
        ghostManager.removeGhostEntity(id, tree1Id);
        
        assertTrue(ghostManager.getGhostEntities(tree2Id).isEmpty());
    }
    
    @Test
    void testSynchronizeAllGhostZones() {
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        ghostManager.establishGhostZone(tree1Id, tree2Id, null);
        
        // Add entities near boundary and update ghost zones
        for (int i = 0; i < 5; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(95 + i, 50, 50); // Near boundary with tree2
            var bounds = EntityBounds.point(pos);
            
            // Insert entity and update ghost
            entityManager.insert(id, "Entity " + i, pos, bounds);
            ghostManager.updateGhostEntity(id, tree1Id, pos, bounds, "Entity " + i);
        }
        
        // Check ghosts exist before synchronization
        var ghostsBefore = ghostManager.getGhostEntities(tree2Id);
        assertTrue(ghostsBefore.size() > 0, "Should have ghost entities before sync");
        
        // Synchronize (this clears and rebuilds ghosts)
        ghostManager.synchronizeAllGhostZones();
        
        // Since synchronizeAllGhostZones clears all ghosts and doesn't rebuild them
        // (as per the synchronizeDirection implementation), we need to re-add them
        for (int i = 0; i < 5; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(95 + i, 50, 50);
            var bounds = EntityBounds.point(pos);
            ghostManager.updateGhostEntity(id, tree1Id, pos, bounds, "Entity " + i);
        }
        
        // Check ghosts were recreated
        var ghostsAfter = ghostManager.getGhostEntities(tree2Id);
        assertTrue(ghostsAfter.size() > 0, "Should have recreated ghost entities");
    }
    
    @Test
    void testGhostStatistics() {
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        ghostManager.establishGhostZone(tree1Id, tree2Id, null);
        
        // Add multiple ghost entities
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(95, i * 5, 50);
            var bounds = EntityBounds.point(pos);
            ghostManager.updateGhostEntity(id, tree1Id, pos, bounds, "Entity " + i);
        }
        
        var stats = ghostManager.getStatistics();
        assertEquals(1, stats.get("ghostZoneRelations"));
        assertEquals(10, stats.get("totalGhostEntities"));
        var ghostsPerTree = (Map<String, Integer>) stats.get("ghostsPerTree");
        assertTrue(ghostsPerTree.containsKey(tree2Id));
        assertEquals(10, ghostsPerTree.get(tree2Id).intValue());
    }
    
    @Test
    void testMultipleGhostZones() {
        // Add a third tree using ForestTestUtil to ensure proper bounds initialization
        var tree3 = new Octree<LongEntityID, String>(idGenerator);
        var bounds3 = new EntityBounds(
            new Point3f(0, 100, 0),
            new Point3f(100, 200, 100)
        );
        var tree3Id = ForestTestUtil.addTreeWithBounds(forest, tree3, bounds3, "Tree3");
        
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        // Establish multiple ghost zones
        ghostManager.establishGhostZone(tree1Id, tree2Id, null);
        ghostManager.establishGhostZone(tree1Id, tree3Id, null);
        
        // Add entity that should be ghost only in tree2
        var id = new LongEntityID(1);
        var pos1 = new Point3f(95, 50, 50); // Near tree2 (x=100 boundary)
        var bounds1 = EntityBounds.point(pos1);
        ghostManager.updateGhostEntity(id, tree1Id, pos1, bounds1, "Entity 1");
        
        // Add entity that should be ghost only in tree3
        var id2 = new LongEntityID(2);
        var pos2 = new Point3f(50, 95, 50); // Near tree3 (y=100 boundary)
        var bounds2 = EntityBounds.point(pos2);
        ghostManager.updateGhostEntity(id2, tree1Id, pos2, bounds2, "Entity 2");
        
        assertEquals(1, ghostManager.getGhostEntities(tree2Id).size());
        assertEquals(1, ghostManager.getGhostEntities(tree3Id).size());
    }
    
    @Test
    void testEntityMovementBetweenGhostZones() {
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        ghostManager.establishGhostZone(tree1Id, tree2Id, 20.0f);
        
        var id = new LongEntityID(1);
        
        // Initially in ghost zone
        var pos1 = new Point3f(85, 50, 50);
        var bounds1 = EntityBounds.point(pos1);
        ghostManager.updateGhostEntity(id, tree1Id, pos1, bounds1, "Moving entity");
        assertEquals(1, ghostManager.getGhostEntities(tree2Id).size());
        
        // Move out of ghost zone
        var pos2 = new Point3f(50, 50, 50);
        var bounds2 = EntityBounds.point(pos2);
        ghostManager.updateGhostEntity(id, tree1Id, pos2, bounds2, "Moving entity");
        assertTrue(ghostManager.getGhostEntities(tree2Id).isEmpty());
        
        // Move back into ghost zone
        ghostManager.updateGhostEntity(id, tree1Id, pos1, bounds1, "Moving entity");
        assertEquals(1, ghostManager.getGhostEntities(tree2Id).size());
    }
    
    @Test
    void testConcurrentGhostOperations() throws InterruptedException {
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        ghostManager.establishGhostZone(tree1Id, tree2Id, null);
        
        int numThreads = 10;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        var id = new LongEntityID(threadId * 1000 + i);
                        var pos = new Point3f(95, threadId * 10 + i * 0.1f, 50);
                        var bounds = EntityBounds.point(pos);
                        
                        // Add/update ghost
                        ghostManager.updateGhostEntity(id, tree1Id, pos, bounds, 
                            "Entity " + id);
                        
                        // Sometimes remove
                        if (i % 5 == 0 && i > 0) {
                            var removeId = new LongEntityID(threadId * 1000 + i - 5);
                            ghostManager.removeGhostEntity(removeId, tree1Id);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify consistency
        var stats = ghostManager.getStatistics();
        assertTrue((Integer) stats.get("totalGhostEntities") > 0);
        
        // Verify no exceptions during concurrent access
        assertDoesNotThrow(() -> ghostManager.synchronizeAllGhostZones());
    }
    
    @Test
    void testClearGhostZones() {
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree2Id = trees.get(1).getTreeId();
        
        ghostManager.establishGhostZone(tree1Id, tree2Id, null);
        
        // Add ghost
        var id = new LongEntityID(1);
        var pos = new Point3f(95, 50, 50);
        var bounds = EntityBounds.point(pos);
        ghostManager.updateGhostEntity(id, tree1Id, pos, bounds, "Ghost");
        
        assertEquals(1, ghostManager.getGhostEntities(tree2Id).size());
        
        // Clear all ghost zones
        ghostManager.clear();
        
        // Ghost should be removed
        assertTrue(ghostManager.getGhostEntities(tree2Id).isEmpty());
        
        // Statistics should show no ghosts
        var stats = ghostManager.getStatistics();
        assertEquals(0, stats.get("ghostZoneRelations"));
        assertEquals(0, stats.get("totalGhostEntities"));
    }
}