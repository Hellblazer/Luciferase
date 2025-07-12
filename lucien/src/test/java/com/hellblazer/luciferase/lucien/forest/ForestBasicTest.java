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
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for Forest functionality
 */
public class ForestBasicTest {
    
    private static class TestEntityID implements EntityID {
        private final UUID id;
        
        public TestEntityID() {
            this.id = UUID.randomUUID();
        }
        
        @Override
        public String toString() {
            return id.toString();
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TestEntityID)) return false;
            return id.equals(((TestEntityID) obj).id);
        }
        
        @Override
        public int hashCode() {
            return id.hashCode();
        }
        
        @Override
        public int compareTo(EntityID other) {
            if (other instanceof TestEntityID testOther) {
                return id.compareTo(testOther.id);
            }
            // Compare by class name if different types
            return this.getClass().getName().compareTo(other.getClass().getName());
        }
        
        @Override
        public String toDebugString() {
            return "TestEntity[" + id + "]";
        }
    }
    
    private static class TestContent {
        private final String name;
        private final int value;
        
        public TestContent(String name, int value) {
            this.name = name;
            this.value = value;
        }
        
        public String getName() { return name; }
        public int getValue() { return value; }
    }
    
    private static class TestEntityIDGenerator implements EntityIDGenerator<TestEntityID> {
        private final AtomicLong counter = new AtomicLong(0);
        
        @Override
        public TestEntityID generateID() {
            return new TestEntityID();
        }
    }
    
    private Forest<MortonKey, TestEntityID, TestContent> forest;
    private TestEntityIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        var config = ForestConfig.defaultConfig();
        forest = new Forest<>(config);
        idGenerator = new TestEntityIDGenerator();
    }
    
    @Test
    void testForestCreation() {
        assertNotNull(forest);
        assertEquals(0, forest.getTreeCount());
        assertNotNull(forest.getConfig());
    }
    
    @Test
    void testAddTree() {
        // Create an octree with entity ID generator
        var octree = new Octree<TestEntityID, TestContent>(idGenerator);
        
        // Create metadata
        var metadata = TreeMetadata.builder()
            .name("TestTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        
        // Add tree to forest
        var treeId = forest.addTree(octree, metadata);
        
        assertNotNull(treeId);
        assertEquals(1, forest.getTreeCount());
        
        var tree = forest.getTree(treeId);
        assertNotNull(tree);
        assertEquals(treeId, tree.getTreeId());
        
        // Get metadata from tree node
        var storedMetadata = tree.getMetadata("metadata");
        assertNotNull(storedMetadata);
        assertTrue(storedMetadata instanceof TreeMetadata);
        assertEquals("TestTree", ((TreeMetadata)storedMetadata).getName());
    }
    
    @Test
    void testMultipleTrees() {
        // Add multiple trees
        for (int i = 0; i < 5; i++) {
            var octree = new Octree<TestEntityID, TestContent>(idGenerator);
            
            var metadata = TreeMetadata.builder()
                .name("Tree_" + i)
                .treeType(TreeMetadata.TreeType.OCTREE)
                .build();
            
            forest.addTree(octree, metadata);
        }
        
        assertEquals(5, forest.getTreeCount());
    }
    
    @Test
    void testRemoveTree() {
        // Add a tree
        var octree = new Octree<TestEntityID, TestContent>(idGenerator);
        var treeId = forest.addTree(octree);
        
        assertEquals(1, forest.getTreeCount());
        
        // Remove the tree
        assertTrue(forest.removeTree(treeId));
        assertEquals(0, forest.getTreeCount());
        assertNull(forest.getTree(treeId));
        
        // Try to remove again
        assertFalse(forest.removeTree(treeId));
    }
    
    @Test
    void testTreeNeighbors() {
        // Add two adjacent trees
        var tree1 = new Octree<TestEntityID, TestContent>(idGenerator);
        var id1 = forest.addTree(tree1);
        
        var tree2 = new Octree<TestEntityID, TestContent>(idGenerator);
        var id2 = forest.addTree(tree2);
        
        // Get the tree nodes
        var treeNode1 = forest.getTree(id1);
        var treeNode2 = forest.getTree(id2);
        
        assertNotNull(treeNode1);
        assertNotNull(treeNode2);
        
        // Add neighbor relationship directly to tree nodes
        treeNode1.addNeighbor(id2);
        treeNode2.addNeighbor(id1);
        
        // Verify the relationship
        assertTrue(treeNode1.hasNeighbor(id2));
        assertTrue(treeNode2.hasNeighbor(id1));
    }
    
    @Test
    void testTreeMetadata() {
        // Test metadata on individual trees
        var octree = new Octree<TestEntityID, TestContent>(idGenerator);
        var treeId = forest.addTree(octree);
        
        var treeNode = forest.getTree(treeId);
        assertNotNull(treeNode);
        
        var key = "testKey";
        var value = "testValue";
        
        treeNode.setMetadata(key, value);
        assertEquals(value, treeNode.getMetadata(key));
        
        treeNode.setMetadata(key, "newValue");
        assertEquals("newValue", treeNode.getMetadata(key));
    }
    
    @Test
    void testGridForestCreation() {
        // GridForest is currently not fully implemented due to constructor issues
        // This test demonstrates the limitation
        var origin = new Point3f(0, 0, 0);
        var totalSize = new Vector3f(1000, 1000, 1000);
        
        // This should throw an UnsupportedOperationException as documented in GridForest
        assertThrows(UnsupportedOperationException.class, () -> {
            GridForest.<MortonKey, TestEntityID, TestContent>createOctreeGrid(
                origin, totalSize, 10, 10, 10);
        });
    }
    
    @Test
    void testRouteQuery() {
        // Create a grid of trees
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                var octree = new Octree<TestEntityID, TestContent>(idGenerator);
                
                // Create metadata with spatial bounds information
                var metadata = TreeMetadata.builder()
                    .name(String.format("Grid_%d_%d", x, y))
                    .treeType(TreeMetadata.TreeType.OCTREE)
                    .property("minX", x * 100f)
                    .property("minY", y * 100f)
                    .property("minZ", 0f)
                    .property("maxX", (x + 1) * 100f)
                    .property("maxY", (y + 1) * 100f)
                    .property("maxZ", 100f)
                    .build();
                
                var treeId = forest.addTree(octree, metadata);
                
                // Update the tree's global bounds
                var treeNode = forest.getTree(treeId);
                var minPt = new Point3f(x * 100, y * 100, 0);
                var maxPt = new Point3f((x + 1) * 100, (y + 1) * 100, 100);
                treeNode.expandGlobalBounds(new EntityBounds(minPt, maxPt));
            }
        }
        
        // Query center region (should hit 4 trees)
        var queryBounds = new EntityBounds(
            new Point3f(50, 50, 0),
            new Point3f(150, 150, 100)
        );
        var trees = forest.routeQuery(queryBounds);
        
        assertEquals(4, trees.count());
    }
}