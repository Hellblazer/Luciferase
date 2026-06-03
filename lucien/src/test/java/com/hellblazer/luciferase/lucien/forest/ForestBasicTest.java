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
import com.hellblazer.luciferase.lucien.Spatial;
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
        // Luciferase-8es5p: GridForest.createTreeAt is implemented — a grid forest constructs a populated grid of
        // trees rather than throwing UnsupportedOperationException.
        var origin = new Point3f(0, 0, 0);
        var totalSize = new Vector3f(900, 900, 900);

        var grid = GridForest.<TestEntityID, TestContent>createOctreeGrid(
            idGenerator, origin, totalSize, 3, 3, 3);

        assertEquals(27, grid.getTreeCount(), "3x3x3 grid must create 27 trees");
        assertEquals(new Vector3f(300, 300, 300), grid.getCellSize());

        // Every grid cell is retrievable and carries grid-position metadata.
        for (int z = 0; z < 3; z++) {
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    var tree = grid.getTreeAt(x, y, z);
                    assertNotNull(tree, "tree must exist at grid position (" + x + "," + y + "," + z + ")");
                }
            }
        }

        // Position routing resolves to the correct cell.
        assertArrayEquals(new int[]{0, 0, 0}, grid.getGridCoordinates(new Point3f(10, 10, 10)));
        assertArrayEquals(new int[]{2, 1, 0}, grid.getGridCoordinates(new Point3f(700, 400, 100)));
        assertNull(grid.getGridCoordinates(new Point3f(-1, 0, 0)), "out-of-grid position routes to null");
    }

    @Test
    void testGridForestRoutingAndEntityQuery() {
        // Luciferase-8es5p review: prove the per-cell expandGlobalBounds actually drives Forest routing/pruning,
        // not just construction counts.
        var grid = GridForest.<TestEntityID, TestContent>createOctreeGrid(
            idGenerator, new Point3f(0, 0, 0), new Vector3f(900, 900, 900), 3, 3, 3); // 300-unit cells

        // routeQuery prunes by the stamped cell bounds: a query well inside one cell hits exactly that one tree.
        var inOneCell = new EntityBounds(new Point3f(10, 10, 10), new Point3f(50, 50, 50));
        assertEquals(1, grid.routeQuery(inOneCell).count(), "query inside a single cell must route to exactly one tree");

        // A query straddling the x boundary at 300 hits two adjacent cells.
        var straddle = new EntityBounds(new Point3f(250, 10, 10), new Point3f(350, 50, 50));
        assertEquals(2, grid.routeQuery(straddle).count(), "query straddling a cell boundary must route to two trees");

        // End-to-end: insert an entity into one cell's index, confirm a region query finds it and prunes other cells.
        var cell000 = grid.getTreeAt(0, 0, 0).getSpatialIndex();
        var id = cell000.insert(new Point3f(20, 20, 20), (byte) 10, new TestContent("in-cell-000", 1));
        var found = grid.findEntitiesInRegion(new Spatial.Cube(0, 0, 0, 100));
        assertTrue(found.contains(id), "entity inserted in cell (0,0,0) must be found by an overlapping region query");
        assertTrue(grid.findEntitiesInRegion(new Spatial.Cube(600, 600, 600, 100)).isEmpty(),
                   "region query over an empty far cell must return nothing (routing prunes populated cell)");
    }

    @Test
    void testTetreeGridRejectsNegativeOrigin() {
        // Luciferase-8es5p review: Tetree requires non-negative coordinates; a negative-origin tetree grid must
        // fail fast at construction, not throw on first insert/query.
        assertThrows(IllegalArgumentException.class, () ->
            GridForest.<TestEntityID, TestContent>createTetreeGrid(
                idGenerator, new Point3f(-100, 0, 0), new Vector3f(300, 300, 300), 3, 1, 1),
            "negative-origin tetree grid must be rejected at construction");
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