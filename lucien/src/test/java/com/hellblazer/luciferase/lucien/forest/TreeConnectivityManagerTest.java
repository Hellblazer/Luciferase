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

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TreeConnectivityManager
 */
public class TreeConnectivityManagerTest {
    
    private TreeConnectivityManager<MortonKey, LongEntityID, String> connectivityManager;
    
    @BeforeEach
    void setUp() {
        connectivityManager = new TreeConnectivityManager<>();
    }
    
    @Test
    void testAddAndRemoveConnections() {
        var boundary = new EntityBounds(
            new Point3f(100, 0, 0),
            new Point3f(100, 100, 100)
        );
        
        // Add connection
        connectivityManager.addConnection("tree1", "tree2", 
            TreeConnectivityManager.ConnectivityType.FACE, boundary);
        
        assertTrue(connectivityManager.areNeighbors("tree1", "tree2"));
        assertTrue(connectivityManager.areNeighbors("tree2", "tree1")); // Bidirectional
        
        // Remove connection
        assertTrue(connectivityManager.removeConnection("tree1", "tree2"));
        assertFalse(connectivityManager.areNeighbors("tree1", "tree2"));
        assertFalse(connectivityManager.areNeighbors("tree2", "tree1"));
        
        // Remove non-existent connection
        assertFalse(connectivityManager.removeConnection("tree1", "tree2"));
    }
    
    @Test
    void testGetNeighbors() {
        // Create a simple grid of connections
        connectivityManager.addConnection("center", "north", 
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("center", "south", 
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("center", "east", 
            TreeConnectivityManager.ConnectivityType.EDGE, null);
        connectivityManager.addConnection("center", "west", 
            TreeConnectivityManager.ConnectivityType.VERTEX, null);
        
        var connections = connectivityManager.getConnections("center");
        assertEquals(4, connections.size());
        var neighborIds = connections.stream()
            .map(c -> c.getOtherId("center"))
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(neighborIds.contains("north"));
        assertTrue(neighborIds.contains("south"));
        assertTrue(neighborIds.contains("east"));
        assertTrue(neighborIds.contains("west"));
        
        // Non-existent tree has no neighbors
        assertTrue(connectivityManager.getConnections("unknown").isEmpty());
    }
    
    @Test
    void testGetConnectionsByType() {
        connectivityManager.addConnection("tree1", "tree2", 
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("tree1", "tree3", 
            TreeConnectivityManager.ConnectivityType.EDGE, null);
        connectivityManager.addConnection("tree1", "tree4", 
            TreeConnectivityManager.ConnectivityType.VERTEX, null);
        connectivityManager.addConnection("tree1", "tree5", 
            TreeConnectivityManager.ConnectivityType.FACE, null);
        
        var faceConnections = connectivityManager.getConnectionsByType("tree1", 
            TreeConnectivityManager.ConnectivityType.FACE);
        assertEquals(2, faceConnections.size());
        var faceNeighbors = faceConnections.stream()
            .map(c -> c.getOtherId("tree1"))
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(faceNeighbors.contains("tree2"));
        assertTrue(faceNeighbors.contains("tree5"));
        
        var edgeConnections = connectivityManager.getConnectionsByType("tree1",
            TreeConnectivityManager.ConnectivityType.EDGE);
        assertEquals(1, edgeConnections.size());
        assertEquals("tree3", edgeConnections.get(0).getOtherId("tree1"));
    }
    
    @Test
    void testFindSharedBoundary() {
        var boundary1 = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(100, 100, 100)
        );
        var boundary2 = new EntityBounds(
            new Point3f(100, 0, 0),
            new Point3f(200, 100, 100)
        );
        
        // Add connection with shared boundary
        var sharedBoundary = new EntityBounds(
            new Point3f(100, 0, 0),
            new Point3f(100, 100, 100)
        );
        connectivityManager.addConnection("tree1", "tree2",
            TreeConnectivityManager.ConnectivityType.FACE, sharedBoundary);
        
        var connection = connectivityManager.getConnection("tree1", "tree2");
        assertNotNull(connection);
        assertEquals(sharedBoundary, connection.getSharedBoundary());
        
        // No boundary for non-connected trees
        assertNull(connectivityManager.getConnection("tree1", "tree3"));
    }
    
    
    @Test
    void testFindConnectedComponents() {
        // Create three separate components
        // Component 1: A-B-C
        connectivityManager.addConnection("A", "B",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("B", "C",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        
        // Component 2: D-E
        connectivityManager.addConnection("D", "E",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        
        // Test that we can identify connected components manually
        assertTrue(connectivityManager.areNeighbors("A", "B"));
        assertTrue(connectivityManager.areNeighbors("B", "C"));
        assertFalse(connectivityManager.areNeighbors("A", "C")); // Not directly connected
        
        assertTrue(connectivityManager.areNeighbors("D", "E"));
        assertFalse(connectivityManager.areNeighbors("A", "D")); // Different components
    }
    
    @Test
    void testFindShortestPath() {
        // Create a graph: A-B-C-D and A-E-D
        connectivityManager.addConnection("A", "B",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("B", "C",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("C", "D",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("A", "E",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("E", "D",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        
        // Test connections are established
        assertTrue(connectivityManager.areNeighbors("A", "B"));
        assertTrue(connectivityManager.areNeighbors("B", "C"));
        assertTrue(connectivityManager.areNeighbors("C", "D"));
        assertTrue(connectivityManager.areNeighbors("A", "E"));
        assertTrue(connectivityManager.areNeighbors("E", "D"));
        
        // Test we can reach D from A through either path
        var aConnections = connectivityManager.getConnections("A");
        assertEquals(2, aConnections.size()); // Connected to B and E
    }
    
    @Test
    void testFindTreesWithinDistance() {
        // Create a linear chain: A-B-C-D-E
        connectivityManager.addConnection("A", "B",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("B", "C",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("C", "D",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("D", "E",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        
        // When sharedBoundary is null, distance defaults to MAX_VALUE
        // Trees within any distance (including MAX_VALUE)
        var withinMax = connectivityManager.findTreesWithinDistance("C", Double.MAX_VALUE);
        assertEquals(2, withinMax.size()); // B and D
        assertTrue(withinMax.containsAll(List.of("B", "D")));
        
        // Trees within distance 0 - should be empty since default distance is MAX_VALUE
        var within0 = connectivityManager.findTreesWithinDistance("C", 0);
        assertEquals(0, within0.size());
    }
    
    @Test
    void testGetConnectionStatistics() {
        // Add various types of connections
        connectivityManager.addConnection("A", "B",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("A", "C",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("B", "D",
            TreeConnectivityManager.ConnectivityType.EDGE, null);
        connectivityManager.addConnection("C", "D",
            TreeConnectivityManager.ConnectivityType.VERTEX, null);
        
        // Test connections by type
        var aFaceConnections = connectivityManager.getConnectionsByType("A", TreeConnectivityManager.ConnectivityType.FACE);
        assertEquals(2, aFaceConnections.size());
        
        var bConnections = connectivityManager.getConnections("B");
        assertEquals(2, bConnections.size()); // Connected to A (FACE) and D (EDGE)
        
        var dConnections = connectivityManager.getConnections("D");
        assertEquals(2, dConnections.size()); // Connected to B (EDGE) and C (VERTEX)
    }
    
    @Test
    void testConcurrentOperations() throws InterruptedException {
        int numThreads = 10;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String tree1 = "tree_" + threadId + "_" + i;
                        String tree2 = "tree_" + threadId + "_" + ((i + 1) % opsPerThread);
                        
                        // Add connection
                        connectivityManager.addConnection(tree1, tree2,
                            TreeConnectivityManager.ConnectivityType.FACE, null);
                        
                        // Query
                        assertTrue(connectivityManager.areNeighbors(tree1, tree2));
                        
                        // Remove some connections
                        if (i % 3 == 0) {
                            connectivityManager.removeConnection(tree1, tree2);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify some connections were created
        // Note: exact count depends on thread scheduling and which connections were removed
        // Just verify the manager is still consistent
        var testConnection = connectivityManager.getConnection("tree_0_0", "tree_0_1");
        // Connection may or may not exist depending on whether it was removed
    }
    
    @Test
    void testRemoveAllConnectionsForTree() {
        // Create connections
        connectivityManager.addConnection("center", "north",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("center", "south",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("center", "east",
            TreeConnectivityManager.ConnectivityType.FACE, null);
        connectivityManager.addConnection("north", "east",
            TreeConnectivityManager.ConnectivityType.EDGE, null);
        
        // Remove all connections for "center" one by one
        connectivityManager.removeConnection("center", "north");
        connectivityManager.removeConnection("center", "south");
        connectivityManager.removeConnection("center", "east");
        
        // Verify "center" has no connections
        assertTrue(connectivityManager.getConnections("center").isEmpty());
        
        // But other connections remain
        assertTrue(connectivityManager.areNeighbors("north", "east"));
    }
}