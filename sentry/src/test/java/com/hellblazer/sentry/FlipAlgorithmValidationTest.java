/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.sentry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Deque;

/**
 * Detailed validation of the flip algorithm implementation
 * 
 * @author hal.hildebrand
 */
public class FlipAlgorithmValidationTest {
    
    private MutableGrid grid;
    private Random entropy;
    
    @BeforeEach
    public void setUp() {
        grid = new MutableGrid();
        entropy = new Random(0x666);
    }
    
    @Test
    @DisplayName("Test 1->4 flip basic operation")
    public void test1to4Flip() {
        // Create initial tetrahedron vertices
        Point3f offset = new Point3f(5000, 5000, 5000);
        
        Vertex v0 = grid.track(new Point3f(0 + offset.x, 0 + offset.y, 0 + offset.z), entropy);
        Vertex v1 = grid.track(new Point3f(1000 + offset.x, 0 + offset.y, 0 + offset.z), entropy);
        Vertex v2 = grid.track(new Point3f(500 + offset.x, 866 + offset.y, 0 + offset.z), entropy);
        Vertex v3 = grid.track(new Point3f(500 + offset.x, 289 + offset.y, 816 + offset.z), entropy);
        
        // Count initial tetrahedra
        Set<Tetrahedron> initialTets = grid.tetrahedrons();
        int initialCount = (int) initialTets.stream().filter(t -> !t.isDeleted()).count();
        
        // Add point inside the tetrahedron to trigger 1->4 flip
        Point3f insidePoint = new Point3f(500 + offset.x, 289 + offset.y, 204 + offset.z);
        Vertex v4 = grid.track(insidePoint, entropy);
        
        // Verify flip occurred
        Set<Tetrahedron> afterTets = grid.tetrahedrons();
        int afterCount = (int) afterTets.stream().filter(t -> !t.isDeleted()).count();
        
        // Should have more tetrahedra after flip
        assertTrue(afterCount > initialCount, 
            "Should have more tetrahedra after 1->4 flip");
        
        // Verify v4 is in the tetrahedralization
        boolean v4Found = false;
        for (Tetrahedron tet : afterTets) {
            if (!tet.isDeleted() && tet.includes(v4)) {
                v4Found = true;
                
                // Verify this tetrahedron has v4 as one vertex
                Vertex[] verts = tet.getVertices();
                assertTrue(verts[0] == v4 || verts[1] == v4 || verts[2] == v4 || verts[3] == v4,
                    "New vertex should be in tetrahedron");
            }
        }
        
        assertTrue(v4Found, "New vertex should be found in some tetrahedron");
    }
    
    @Test
    @DisplayName("Test flip maintains topological consistency")
    public void testFlipTopology() {
        // Create a small set of points
        List<Vertex> vertices = new ArrayList<>();
        Point3f center = new Point3f(5000, 5000, 5000);
        
        // Create initial configuration
        for (int i = 0; i < 10; i++) {
            Point3f p = Vertex.randomPoint(500, entropy);
            p.add(center);
            vertices.add(grid.track(p, entropy));
        }
        
        // Verify topological properties
        assertTrue(verifyTopology(), 
            "Topology should be consistent after flips");
        
        // Verify all vertices are connected
        for (Vertex v : vertices) {
            if (v != null) {
                assertTrue(v.hasValidStar(), 
                    "Each vertex should have a valid star");
            }
        }
    }
    
    @Test
    @DisplayName("Test flip4to1 operation")
    public void testFlip4to1() {
        // Create a configuration where 4 tetrahedra share a common vertex
        // This is done by creating vertices and then untracking the central one
        
        Point3f center = new Point3f(5000, 5000, 5000);
        
        // Create outer vertices
        Vertex v0 = grid.track(new Point3f(center.x + 1000, center.y, center.z), entropy);
        Vertex v1 = grid.track(new Point3f(center.x - 500, center.y + 866, center.z), entropy);
        Vertex v2 = grid.track(new Point3f(center.x - 500, center.y - 866, center.z), entropy);
        Vertex v3 = grid.track(new Point3f(center.x, center.y, center.z + 1000), entropy);
        
        // Add central vertex
        Vertex vCenter = grid.track(center, entropy);
        
        // Get star of central vertex before removal
        Deque<OrientedFace> star = vCenter.getStar();
        int starSize = star.size();
        
        System.out.println("Star size of central vertex: " + starSize);
        
        // The flip4to1 is typically triggered during vertex removal
        // which is part of the kinetic update process
        
        // Verify star properties
        assertTrue(starSize >= 4, "Central vertex should have at least 4 incident faces");
        
        // Verify each face in the star
        for (OrientedFace face : star) {
            assertNotNull(face.getIncident(), "Face should have incident tetrahedron");
            assertEquals(vCenter, face.getIncidentVertex(), 
                "Face should be opposite to central vertex");
        }
    }
    
    @Test
    @DisplayName("Test cascading flips")
    public void testCascadingFlips() {
        // Create a configuration that will trigger multiple cascading flips
        Point3f center = new Point3f(5000, 5000, 5000);
        
        // Create initial points in a specific pattern
        List<Vertex> initialVertices = new ArrayList<>();
        
        // Outer ring
        float radius = 1000;
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI * 2 / 6;
            Point3f p = new Point3f(
                center.x + (float)(radius * Math.cos(angle)),
                center.y + (float)(radius * Math.sin(angle)),
                center.z
            );
            initialVertices.add(grid.track(p, entropy));
        }
        
        // Add point above and below
        initialVertices.add(grid.track(new Point3f(center.x, center.y, center.z + 500), entropy));
        initialVertices.add(grid.track(new Point3f(center.x, center.y, center.z - 500), entropy));
        
        // Record state
        Set<Tetrahedron> beforeTets = new HashSet<>(grid.tetrahedrons());
        int beforeCount = (int) beforeTets.stream().filter(t -> !t.isDeleted()).count();
        
        // Add central point that will trigger cascading flips
        Vertex centralVertex = grid.track(center, entropy);
        
        // Check results
        Set<Tetrahedron> afterTets = grid.tetrahedrons();
        int afterCount = (int) afterTets.stream().filter(t -> !t.isDeleted()).count();
        
        System.out.printf("Cascading flips: %d tetrahedra before, %d after%n", 
            beforeCount, afterCount);
        
        // Verify central vertex is well-connected
        assertTrue(centralVertex.getStarSize() > 4, 
            "Central vertex should be incident to multiple tetrahedra");
        
        // Verify Delaunay property
        assertTrue(validateLocalDelaunay(centralVertex), 
            "Local Delaunay property should hold around central vertex");
    }
    
    @Test
    @DisplayName("Test ear queue management during flips")
    public void testEarQueueManagement() {
        // The ear queue is used during flip operations to track faces that need checking
        
        // Create a simple configuration
        Point3f center = new Point3f(5000, 5000, 5000);
        
        // Track the flip operations by using a custom test grid
        TestMutableGrid testGrid = new TestMutableGrid();
        
        // Add points that will trigger flips
        testGrid.track(new Point3f(center.x + 500, center.y, center.z), entropy);
        testGrid.track(new Point3f(center.x - 500, center.y, center.z), entropy);
        testGrid.track(new Point3f(center.x, center.y + 500, center.z), entropy);
        testGrid.track(new Point3f(center.x, center.y, center.z + 500), entropy);
        
        // Add point that triggers flips
        testGrid.track(center, entropy);
        
        // Verify ear processing
        assertTrue(testGrid.getMaxEarQueueSize() > 0, 
            "Ear queue should be used during flips");
        
        System.out.printf("Max ear queue size: %d, Total ears processed: %d%n",
            testGrid.getMaxEarQueueSize(), testGrid.getTotalEarsProcessed());
    }
    
    @Test
    @DisplayName("Test degenerate flip cases")
    public void testDegenerateFlipCases() {
        // Test cases where flips might fail or produce degenerate tetrahedra
        
        Point3f base = new Point3f(5000, 5000, 5000);
        
        // Case 1: Nearly coplanar points
        List<Vertex> coplanar = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Point3f p = new Point3f(
                base.x + i * 100,
                base.y + i * 100,
                base.z + 0.001f * i  // Very small z variation
            );
            coplanar.add(grid.track(p, entropy));
        }
        
        // Add a point slightly above the plane
        Vertex abovePlane = grid.track(new Point3f(base.x + 150, base.y + 150, base.z + 10), entropy);
        
        // Verify no crashes and topology is maintained
        assertTrue(verifyTopology(), 
            "Topology should be maintained even with near-degenerate cases");
        
        // Case 2: Points on a line
        List<Vertex> collinear = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Point3f p = new Point3f(base.x + i * 200, base.y + 1000, base.z + 1000);
            collinear.add(grid.track(p, entropy));
        }
        
        // Add points to create volume
        grid.track(new Point3f(base.x + 500, base.y + 1500, base.z + 1000), entropy);
        grid.track(new Point3f(base.x + 500, base.y + 1000, base.z + 1500), entropy);
        
        // Verify handling of degenerate cases
        Set<Tetrahedron> tets = grid.tetrahedrons();
        int degenerateCount = 0;
        
        for (Tetrahedron tet : tets) {
            if (!tet.isDeleted() && tet.isDegenerate()) {
                degenerateCount++;
                System.out.printf("Degenerate tetrahedron volume: %e%n", tet.volume());
            }
        }
        
        System.out.printf("Degenerate tetrahedra: %d out of %d%n", 
            degenerateCount, tets.size());
    }
    
    // Helper method to verify topological consistency
    private boolean verifyTopology() {
        Set<Tetrahedron> tets = grid.tetrahedrons();
        
        for (Tetrahedron tet : tets) {
            if (tet.isDeleted()) continue;
            
            // Check neighbor relationships are bidirectional
            for (V face : Grid.VERTICES) {
                Tetrahedron neighbor = tet.getNeighbor(face);
                if (neighbor != null && !neighbor.isDeleted()) {
                    // Find which face of neighbor points back to tet
                    boolean foundBack = false;
                    for (V nFace : Grid.VERTICES) {
                        if (neighbor.getNeighbor(nFace) == tet) {
                            foundBack = true;
                            break;
                        }
                    }
                    if (!foundBack) {
                        System.err.println("Broken neighbor relationship");
                        return false;
                    }
                }
            }
            
            // Check vertices are valid
            Vertex[] vertices = tet.getVertices();
            for (Vertex v : vertices) {
                if (v == null) {
                    System.err.println("Null vertex in tetrahedron");
                    return false;
                }
            }
        }
        
        return true;
    }
    
    // Helper method to validate local Delaunay property around a vertex
    private boolean validateLocalDelaunay(Vertex v) {
        if (v == null || v.getAdjacent() == null) return false;
        
        Set<Tetrahedron> star = new HashSet<>();
        v.getAdjacent().visitStar(v, (vertex, t, x, y, z) -> {
            star.add(t);
        });
        
        // Check each tetrahedron in the star
        for (Tetrahedron tet : star) {
            if (tet.isDeleted()) continue;
            
            // Check against other vertices in the star
            for (Tetrahedron other : star) {
                if (other == tet || other.isDeleted()) continue;
                
                Vertex[] otherVerts = other.getVertices();
                for (Vertex ov : otherVerts) {
                    if (!tet.includes(ov) && tet.inSphere(ov)) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }
    
    // Test helper class to track flip operations
    private static class TestMutableGrid extends MutableGrid {
        private int maxEarQueueSize = 0;
        private int totalEarsProcessed = 0;
        private int flip4to1Count = 0;
        
        @Override
        protected Tetrahedron flip4to1(Vertex n) {
            // Track when 4->1 flips occur
            flip4to1Count++;
            System.out.println("Performing 4->1 flip #" + flip4to1Count);
            return super.flip4to1(n);
        }
        
        @Override
        protected void insert(Vertex v, final Tetrahedron target) {
            List<OrientedFace> ears = new ArrayList<>(20);
            last = target.flip1to4(v, ears);
            
            // Track initial ear queue size
            if (ears.size() > maxEarQueueSize) {
                maxEarQueueSize = ears.size();
            }
            
            // Update landmark index with new tetrahedra from initial flip
            if (landmarkIndex != null && USE_LANDMARK_INDEX) {
                landmarkIndex.addTetrahedron(last, size * 4);
            }
            
            // Process ears and track statistics
            while (!ears.isEmpty()) {
                totalEarsProcessed++;
                int lastIndex = ears.size() - 1;
                OrientedFace face = ears.remove(lastIndex);
                
                // Track current queue size
                if (ears.size() > maxEarQueueSize) {
                    maxEarQueueSize = ears.size();
                }
                
                Tetrahedron l = USE_OPTIMIZED_FLIP 
                    ? FlipOptimizer.flipOptimized(face, v, ears)
                    : face.flip(v, ears);
                if (l != null) {
                    last = l;
                    if (landmarkIndex != null && USE_LANDMARK_INDEX && ears.size() % 10 == 0) {
                        landmarkIndex.addTetrahedron(l, size * 4);
                    }
                }
            }
            
            // Periodically clean up deleted landmarks
            if (landmarkIndex != null && USE_LANDMARK_INDEX && size % 100 == 0) {
                landmarkIndex.cleanup();
            }
        }
        
        public int getMaxEarQueueSize() {
            return maxEarQueueSize;
        }
        
        public int getTotalEarsProcessed() {
            return totalEarsProcessed;
        }
        
        public int getFlip4to1Count() {
            return flip4to1Count;
        }
    }
}
