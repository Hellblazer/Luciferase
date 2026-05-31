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

package com.hellblazer.luciferase.lucien.neighbor;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TetreeNeighborDetector implementation.
 * 
 * @author Hal Hildebrand
 */
class TetreeNeighborDetectorTest {
    
    private Tetree<LongEntityID, String> tetree;
    private TetreeNeighborDetector detector;
    
    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        detector = new TetreeNeighborDetector(tetree);
        
        // Insert some test entities to create a populated tree
        // Start at positive coordinates (Tetree requirement)
        for (int i = 1; i <= 10; i++) {
            var position = new Point3f(i * 100, i * 100, i * 100);
            var level = (byte) 10;
            tetree.insert(position, level, "Entity" + i);
        }
    }
    
    /**
     * Regression for Luciferase-koaw: the detector's face-neighbor path must return the GEOMETRIC face
     * neighbor (Tet.faceNeighbor, oracle-gated by Luciferase-4bmd), not child[neighborType]. The old impl
     * passed a neighbor TYPE (0-5) into parentTet.child() as a Morton index, silently returning a wrong tet.
     * This asserts EXACT set-equality between the detector output and the in-domain geometric neighbors —
     * a non-vacuous check the prior size<=4 / no-duplicate tests could not catch.
     */
    @Test
    void faceNeighborsMatchGeometricGroundTruth() {
        int maxCoord = Constants.lengthAtLevel((byte) 0);
        int checkedTets = 0;
        int matchedNeighbors = 0;

        // DFS a refined tree; every node is a genuinely valid Tet (no fabricated anchors/types).
        Deque<Tet> work = new ArrayDeque<>();
        work.push(new Tet(0, 0, 0, (byte) 0, (byte) 0));
        while (!work.isEmpty()) {
            var tet = work.pop();
            if (tet.l() >= 1) {
                var key = tet.tmIndex();
                // Expected = in-domain geometric face neighbors, minus self (mirrors detector filtering).
                var expected = new HashSet<TetreeKey<?>>();
                for (int face = 0; face < 4; face++) {
                    var fn = tet.faceNeighbor(face);
                    if (fn == null) {
                        continue;
                    }
                    var n = fn.tet();
                    if (n.x() < 0 || n.y() < 0 || n.z() < 0 || n.x() >= maxCoord || n.y() >= maxCoord
                    || n.z() >= maxCoord) {
                        continue;
                    }
                    var nKey = n.tmIndex();
                    if (!nKey.equals(key)) {
                        expected.add(nKey);
                    }
                }

                var actual = new HashSet<>(detector.findFaceNeighbors(key));
                assertEquals(expected, actual,
                             "detector face neighbors must equal geometric ground truth for " + tet);
                checkedTets++;
                matchedNeighbors += actual.size();
            }
            if (tet.l() < 4) {
                for (int m = 0; m < 8; m++) {
                    work.push(tet.child(m));
                }
            }
        }
        assertTrue(checkedTets > 1000, "expected broad tet coverage, got " + checkedTets);
        assertTrue(matchedNeighbors > 0, "expected non-empty neighbor sets, got " + matchedNeighbors);
    }

    @Test
    void testFaceNeighbors() {
        // Get a key from the tree
        var keys = tetree.getSortedSpatialIndices();
        assertFalse(keys.isEmpty(), "Tree should have keys after insertion");
        
        var testKey = keys.first();
        var faceNeighbors = detector.findFaceNeighbors(testKey);
        
        // A tetrahedron can have up to 4 face neighbors
        assertTrue(faceNeighbors.size() <= 4, 
                  "Face neighbors should be at most 4, got: " + faceNeighbors.size());
        
        // Verify no duplicates
        assertEquals(faceNeighbors.size(), 
                    faceNeighbors.stream().distinct().count(),
                    "Face neighbors should not contain duplicates");
    }
    
    @Test
    void testEdgeNeighbors() {
        var keys = tetree.getSortedSpatialIndices();
        assertFalse(keys.isEmpty(), "Tree should have keys after insertion");
        
        var testKey = keys.first();
        var edgeNeighbors = detector.findEdgeNeighbors(testKey);
        
        // Edge neighbors include face neighbors
        var faceNeighbors = detector.findFaceNeighbors(testKey);
        assertTrue(edgeNeighbors.size() >= faceNeighbors.size(),
                  "Edge neighbors should include all face neighbors");
        
        // Verify no duplicates
        assertEquals(edgeNeighbors.size(), 
                    edgeNeighbors.stream().distinct().count(),
                    "Edge neighbors should not contain duplicates");
    }
    
    @Test
    void testVertexNeighbors() {
        var keys = tetree.getSortedSpatialIndices();
        assertFalse(keys.isEmpty(), "Tree should have keys after insertion");
        
        var testKey = keys.first();
        var vertexNeighbors = detector.findVertexNeighbors(testKey);
        
        // Vertex neighbors include edge neighbors
        var edgeNeighbors = detector.findEdgeNeighbors(testKey);
        assertTrue(vertexNeighbors.size() >= edgeNeighbors.size(),
                  "Vertex neighbors should include all edge neighbors");
        
        // Verify no duplicates
        assertEquals(vertexNeighbors.size(), 
                    vertexNeighbors.stream().distinct().count(),
                    "Vertex neighbors should not contain duplicates");
    }
    
    @Test
    void testNeighborHierarchy() {
        var keys = tetree.getSortedSpatialIndices();
        assertFalse(keys.isEmpty(), "Tree should have keys after insertion");
        
        var testKey = keys.first();
        var faceNeighbors = detector.findFaceNeighbors(testKey);
        var edgeNeighbors = detector.findEdgeNeighbors(testKey);
        var vertexNeighbors = detector.findVertexNeighbors(testKey);
        
        // Verify containment hierarchy: face ⊆ edge ⊆ vertex
        for (var face : faceNeighbors) {
            assertTrue(edgeNeighbors.contains(face),
                      "All face neighbors should be in edge neighbors");
        }
        
        for (var edge : edgeNeighbors) {
            assertTrue(vertexNeighbors.contains(edge),
                      "All edge neighbors should be in vertex neighbors");
        }
    }
    
    @Test
    void testFindNeighborsWithGhostType() {
        var keys = tetree.getSortedSpatialIndices();
        assertFalse(keys.isEmpty(), "Tree should have keys after insertion");
        
        var testKey = keys.first();
        
        // Test different ghost types
        var faceGhosts = detector.findNeighbors(testKey, GhostType.FACES);
        var edgeGhosts = detector.findNeighbors(testKey, GhostType.EDGES);
        var vertexGhosts = detector.findNeighbors(testKey, GhostType.VERTICES);
        
        // Verify expected sizes based on ghost type
        var faceNeighbors = detector.findFaceNeighbors(testKey);
        var edgeNeighbors = detector.findEdgeNeighbors(testKey);
        var vertexNeighbors = detector.findVertexNeighbors(testKey);
        
        assertEquals(faceNeighbors.size(), faceGhosts.size(),
                    "FACES ghost type should return face neighbors");
        assertEquals(edgeNeighbors.size(), edgeGhosts.size(),
                    "EDGES ghost type should return edge neighbors");
        assertEquals(vertexNeighbors.size(), vertexGhosts.size(),
                    "VERTICES ghost type should return vertex neighbors");
    }
    
    @Test
    void testBoundaryDetection() {
        // Insert entity at origin to test negative boundaries
        tetree.insert(new Point3f(5, 5, 5), (byte)5, null);
        
        var keys = tetree.getSortedSpatialIndices();
        TetreeKey<?> originKey = null;
        for (var key : keys) {
            if (key.getLevel() > 0) {
                originKey = key;
                break;
            }
        }
        assertNotNull(originKey, "Should find non-root key");
        
        // Should be at negative boundaries
        assertTrue(detector.isBoundaryElement(originKey, NeighborDetector.Direction.NEGATIVE_X));
        assertTrue(detector.isBoundaryElement(originKey, NeighborDetector.Direction.NEGATIVE_Y));
        assertTrue(detector.isBoundaryElement(originKey, NeighborDetector.Direction.NEGATIVE_Z));
        assertFalse(detector.isBoundaryElement(originKey, NeighborDetector.Direction.POSITIVE_X));
        assertFalse(detector.isBoundaryElement(originKey, NeighborDetector.Direction.POSITIVE_Y));
        assertFalse(detector.isBoundaryElement(originKey, NeighborDetector.Direction.POSITIVE_Z));
        
        var boundaryDirs = detector.getBoundaryDirections(originKey);
        assertEquals(3, boundaryDirs.size(), "Should have 3 boundary directions");
    }
    
    @Test
    void testNeighborsWithOwners() {
        var keys = tetree.getSortedSpatialIndices();
        assertFalse(keys.isEmpty(), "Tree should have keys after insertion");
        
        var testKey = keys.first();
        
        var neighborsWithOwners = detector.findNeighborsWithOwners(testKey, GhostType.FACES);
        assertFalse(neighborsWithOwners.isEmpty(), "Should have some neighbors with owners");
        
        // Verify all neighbors are marked as local (current implementation)
        for (var info : neighborsWithOwners) {
            assertTrue(info.isLocal(), "All neighbors should be local in current implementation");
            assertEquals(0, info.ownerRank(), "Local neighbors should have rank 0");
            assertEquals(0, info.treeId(), "Local neighbors should have tree ID 0");
        }
    }
}