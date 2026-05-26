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

package com.hellblazer.luciferase.lucien.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.*;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.*;
import com.hellblazer.luciferase.lucien.octree.MortonKey;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ghost communication using gRPC.
 * 
 * This test validates the complete ghost communication pipeline including
 * protobuf serialization, gRPC service interactions, and distributed
 * ghost synchronization between multiple processes.
 * 
 * @author Hal Hildebrand
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GhostCommunicationIntegrationTest {
    
    private static final int BASE_PORT = 9090;
    private static final String BIND_ADDRESS = "localhost";
    
    private List<GhostCommunicationManager<MortonKey, LongEntityID, String>> managers;
    private SimpleServiceDiscovery serviceDiscovery;
    
    @BeforeEach
    void setUp() throws Exception {
        // Create service discovery for 3 processes
        serviceDiscovery = SimpleServiceDiscovery.forLocalTesting(3, BASE_PORT);
        
        // Create communication managers for 3 processes
        managers = new ArrayList<>();
        for (int rank = 0; rank < 3; rank++) {
            var manager = new GhostCommunicationManager<MortonKey, LongEntityID, String>(
                rank,
                BIND_ADDRESS,
                BASE_PORT + rank,
                ContentSerializer.STRING_SERIALIZER,
                LongEntityID.class,
                serviceDiscovery
            );
            managers.add(manager);
            manager.start();
        }
        
        // Give servers time to start
        Thread.sleep(500);
    }
    
    @AfterEach
    void tearDown() {
        if (managers != null) {
            managers.forEach(GhostCommunicationManager::shutdown);
        }
    }
    
    @Test
    void testBasicGhostRequest() throws Exception {
        // Setup: Create ghost layer with some elements on rank 0
        var treeId = 12345L;
        var ghostLayer0 = new GhostLayer<MortonKey, LongEntityID, String>(com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
        
        // Add some ghost elements
        var element1 = new GhostElement<>(
            new MortonKey(0x123L),
            new LongEntityID(100L),
            "test-content-1",
            new Point3f(1.0f, 2.0f, 3.0f),
            0,
            treeId
        );
        
        var element2 = new GhostElement<>(
            new MortonKey(0x456L),
            new LongEntityID(200L),
            "test-content-2",
            new Point3f(4.0f, 5.0f, 6.0f),
            0,
            treeId
        );
        
        ghostLayer0.addGhostElement(element1);
        ghostLayer0.addGhostElement(element2);
        managers.get(0).addGhostLayer(treeId, ghostLayer0);
        
        // Test: Request ghosts from rank 1 to rank 0
        var response = managers.get(1).requestGhosts(0, treeId, com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES, null);
        
        // Verify: Check response
        assertNotNull(response, "Response should not be null");
        assertEquals(0, response.getSourceRank(), "Source rank should be 0");
        assertEquals(treeId, response.getSourceTreeId(), "Source tree ID should match");
        assertEquals(2, response.getElementsCount(), "Should have 2 ghost elements");
        
        // Verify element details
        var elements = response.getElementsList();
        var receivedEntityIds = elements.stream()
            .map(e -> Long.parseLong(e.getEntityId()))
            .sorted()
            .toList();
        
        assertEquals(List.of(100L, 200L), receivedEntityIds, "Entity IDs should match");
    }
    
    @Test
    void testGhostSynchronization() throws Exception {
        // Setup: Create ghost layers on multiple ranks
        var treeId1 = 11111L;
        var treeId2 = 22222L;
        
        // Rank 0 has ghosts for tree 1
        var ghostLayer0 = new GhostLayer<MortonKey, LongEntityID, String>(com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
        ghostLayer0.addGhostElement(new GhostElement<>(
            new MortonKey(0x100L),
            new LongEntityID(1000L),
            "rank0-tree1-content",
            new Point3f(10.0f, 20.0f, 30.0f),
            0,
            treeId1
        ));
        managers.get(0).addGhostLayer(treeId1, ghostLayer0);
        
        // Rank 1 has ghosts for tree 2
        var ghostLayer1 = new GhostLayer<MortonKey, LongEntityID, String>(com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
        ghostLayer1.addGhostElement(new GhostElement<>(
            new MortonKey(0x200L),
            new LongEntityID(2000L),
            "rank1-tree2-content",
            new Point3f(40.0f, 50.0f, 60.0f),
            1,
            treeId2
        ));
        managers.get(1).addGhostLayer(treeId2, ghostLayer1);
        
        // Test: Sync multiple trees from rank 2
        var syncResponse = managers.get(2).syncGhosts(0, List.of(treeId1), com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
        
        // Verify: Check sync response
        assertNotNull(syncResponse, "Sync response should not be null");
        assertEquals(1, syncResponse.getTotalElements(), "Should have 1 total element");
        assertEquals(1, syncResponse.getBatchesCount(), "Should have 1 batch");
        
        var batch = syncResponse.getBatches(0);
        assertEquals(0, batch.getSourceRank(), "Batch source rank should be 0");
        assertEquals(treeId1, batch.getSourceTreeId(), "Batch tree ID should be tree 1");
        assertEquals(1, batch.getElementsCount(), "Batch should have 1 element");
        
        var element = batch.getElements(0);
        assertEquals("1000", element.getEntityId(), "Entity ID should match");
        assertEquals("rank0-tree1-content", element.getContent().toStringUtf8(), "Content should match");
    }
    
    @Test
    void testMultipleProcessSync() throws Exception {
        // Setup: Each rank has a different tree with ghosts
        for (int rank = 0; rank < 3; rank++) {
            var treeId = 10000L + rank;
            var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
            
            // Add multiple elements per rank
            for (int i = 0; i < 3; i++) {
                ghostLayer.addGhostElement(new GhostElement<>(
                    new MortonKey(0x1000L * rank + i),
                    new LongEntityID(rank * 100L + i),
                    "rank" + rank + "-element" + i,
                    new Point3f(rank * 10.0f + i, rank * 20.0f + i, rank * 30.0f + i),
                    rank,
                    treeId
                ));
            }
            
            managers.get(rank).addGhostLayer(treeId, ghostLayer);
        }
        
        // Test: Rank 0 syncs with all other ranks
        var results = managers.get(0).syncGhostsWithMultiple(
            Set.of(1, 2), 
            List.of(10001L, 10002L), 
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );
        
        // Verify: Check results from both ranks
        assertEquals(2, results.size(), "Should have results from 2 ranks");
        assertTrue(results.containsKey(1), "Should have result from rank 1");
        assertTrue(results.containsKey(2), "Should have result from rank 2");
        
        // Verify rank 1 response (tree 10001)
        var response1 = results.get(1);
        assertNotNull(response1, "Response from rank 1 should not be null");
        assertEquals(3, response1.getTotalElements(), "Rank 1 should have 3 elements");
        
        // Verify rank 2 response (tree 10002)
        var response2 = results.get(2);
        assertNotNull(response2, "Response from rank 2 should not be null");
        assertEquals(3, response2.getTotalElements(), "Rank 2 should have 3 elements");
    }
    
    @Test
    void testStatisticsGathering() throws Exception {
        // Setup: Add ghost layers to all ranks
        for (int rank = 0; rank < 3; rank++) {
            var treeId = 20000L + rank;
            var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
            
            // Add different numbers of elements per rank
            for (int i = 0; i < rank + 1; i++) {
                ghostLayer.addGhostElement(new GhostElement<>(
                    new MortonKey(0x2000L * rank + i),
                    new LongEntityID(rank * 10L + i),
                    "stats-test-" + rank + "-" + i,
                    new Point3f(i, i, i),
                    rank,
                    treeId
                ));
            }
            
            managers.get(rank).addGhostLayer(treeId, ghostLayer);
        }
        
        // Test: Get local stats from rank 0
        var localStats = managers.get(0).getLocalStats();
        
        // Verify: Check local stats
        assertNotNull(localStats, "Local stats should not be null");
        assertEquals(0, localStats.get("rank"), "Rank should be 0");
        assertTrue(localStats.containsKey("totalGhostElements"), "Should have ghost element count");
        assertTrue(localStats.containsKey("ghostLayerCount"), "Should have ghost layer count");
        assertEquals(1, localStats.get("ghostLayerCount"), "Should have 1 ghost layer");
        
        // Test: Get remote stats from all processes
        var allStats = managers.get(0).getAllRemoteStats();
        
        // Verify: Should get stats from ranks 1 and 2
        assertEquals(2, allStats.size(), "Should have stats from 2 remote ranks");
        assertTrue(allStats.containsKey(1), "Should have stats from rank 1");
        assertTrue(allStats.containsKey(2), "Should have stats from rank 2");
        
        var stats1 = allStats.get(1);
        var stats2 = allStats.get(2);
        
        assertNotNull(stats1, "Stats from rank 1 should not be null");
        assertNotNull(stats2, "Stats from rank 2 should not be null");
        
        // Rank 1 should have 2 elements, rank 2 should have 3 elements
        assertEquals(2, stats1.getTotalGhostElements(), "Rank 1 should have 2 ghost elements");
        assertEquals(3, stats2.getTotalGhostElements(), "Rank 2 should have 3 ghost elements");
    }
    
    @Test
    void testEmptyGhostRequest() throws Exception {
        // Test: Request ghosts from empty layer
        var treeId = 99999L;
        var emptyLayer = new GhostLayer<MortonKey, LongEntityID, String>(com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
        managers.get(0).addGhostLayer(treeId, emptyLayer);
        
        var response = managers.get(1).requestGhosts(0, treeId, com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES, null);
        
        // Verify: Should get empty response
        assertNotNull(response, "Response should not be null");
        assertEquals(0, response.getElementsCount(), "Should have no elements");
        assertEquals(0, response.getSourceRank(), "Source rank should be 0");
        assertEquals(treeId, response.getSourceTreeId(), "Source tree ID should match");
    }
    
    @Test
    void testNonExistentTreeRequest() throws Exception {
        // Test: Request ghosts for non-existent tree
        var nonExistentTreeId = 88888L;
        var response = managers.get(1).requestGhosts(0, nonExistentTreeId, com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES, null);
        
        // Verify: Should get empty response
        assertNotNull(response, "Response should not be null");
        assertEquals(0, response.getElementsCount(), "Should have no elements");
        assertEquals(0, response.getSourceRank(), "Source rank should be 0");
    }
    
    @Test
    void testServiceStatus() {
        // Test: Check that all services are running
        for (int rank = 0; rank < 3; rank++) {
            var manager = managers.get(rank);
            assertTrue(manager.isRunning(), "Manager for rank " + rank + " should be running");
            assertEquals(rank, manager.getCurrentRank(), "Current rank should match");
            assertEquals(BASE_PORT + rank, manager.getPort(), "Port should match");
        }
    }
}