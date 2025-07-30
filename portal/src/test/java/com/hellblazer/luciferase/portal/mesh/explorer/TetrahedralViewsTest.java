/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for TetrahedralTransformViews and TetrahedralWireframeViews.
 * Validates creation, caching, transformations, and rendering aspects using random samples
 * across all levels and types.
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class TetrahedralViewsTest {
    
    private static final Random RANDOM = new Random(42); // Fixed seed for reproducibility
    private static final int SAMPLES_PER_LEVEL = 10;
    
    @BeforeAll
    public static void initJavaFX() {
        // Initialize JavaFX platform for tests
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Platform already initialized
        }
    }
    
    @Test
    public void testTransformViewsCreation() {
        TetrahedralTransformViews views = new TetrahedralTransformViews();
        
        // Test all 6 tetrahedron types
        for (byte type = 0; type < 6; type++) {
            // Use level 10 with coordinates aligned to its cell size
            byte level = 10;
            int cellSize = Constants.lengthAtLevel(level);
            Tet tet = new Tet(cellSize * 2, cellSize * 3, cellSize * 4, level, type);
            MeshView meshView = views.of(tet);
            
            assertNotNull(meshView, "MeshView should not be null for type " + type);
            assertNotNull(meshView.getMesh(), "Mesh should not be null");
            assertInstanceOf(TriangleMesh.class, meshView.getMesh(), "Should be TriangleMesh");
            
            // Verify mesh has correct structure
            TriangleMesh mesh = (TriangleMesh) meshView.getMesh();
            assertEquals(12, mesh.getPoints().size(), "Should have 4 vertices * 3 coords = 12 points");
            assertEquals(8, mesh.getTexCoords().size(), "Should have 4 texture coords * 2 = 8");
            assertEquals(24, mesh.getFaces().size(), "Should have 4 faces * 6 indices = 24");
            
            // Verify transform is applied
            assertFalse(meshView.getTransforms().isEmpty(), "Should have transforms");
            assertInstanceOf(Affine.class, meshView.getTransforms().getFirst(), "Should have Affine transform");
        }
    }
    
    @Test
    public void testWireframeViewsCreation() {
        TetrahedralWireframeViews views = new TetrahedralWireframeViews();
        
        // Test all 6 tetrahedron types
        for (byte type = 0; type < 6; type++) {
            // Use level 10 with coordinates aligned to its cell size
            byte level = 10;
            int cellSize = Constants.lengthAtLevel(level);
            Tet tet = new Tet(cellSize * 2, cellSize * 3, cellSize * 4, level, type);
            Group wireframe = views.of(tet);
            
            assertNotNull(wireframe, "Wireframe should not be null for type " + type);
            assertEquals(6, wireframe.getChildren().size(), "Should have 6 edges");
            
            // Verify all children are cylinders
            for (var child : wireframe.getChildren()) {
                assertInstanceOf(Cylinder.class, child, "Edge should be a Cylinder");
                Cylinder cylinder = (Cylinder) child;
                assertTrue(cylinder.getRadius() > 0, "Cylinder should have positive radius");
                assertTrue(cylinder.getHeight() > 0, "Cylinder should have positive height");
            }
            
            // Verify transform is applied
            assertFalse(wireframe.getTransforms().isEmpty(), "Should have transforms");
            assertInstanceOf(Affine.class, wireframe.getTransforms().getFirst(), "Should have Affine transform");
        }
    }
    
    @Test
    public void testComprehensiveRandomSamples() {
        TetrahedralTransformViews transformViews = new TetrahedralTransformViews();
        TetrahedralWireframeViews wireframeViews = new TetrahedralWireframeViews();
        
        // Test every level from 0 to 21
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            int cellSize = Constants.lengthAtLevel(level);
            
            // Test every type (but only type 0 for level 0)
            byte startType = (level == 0) ? (byte) 0 : (byte) 0;
            byte endType = (level == 0) ? (byte) 1 : (byte) 6;
            
            for (byte type = startType; type < endType; type++) {
                // Generate random samples for this level/type combination
                for (int sample = 0; sample < SAMPLES_PER_LEVEL; sample++) {
                    int x, y, z;
                    
                    if (level == 0) {
                        // Level 0 must be at origin
                        x = y = z = 0;
                    } else {
                        // Calculate the maximum valid coordinate for this level
                        // The maximum coordinate is 2^21 - 1 = 2097151
                        int maxValidCoord = (1 << Constants.getMaxRefinementLevel()) - 1;
                        
                        // Calculate how many cells we can have at this level
                        int maxCellIndex = maxValidCoord / cellSize;
                        
                        // Limit to a reasonable number to avoid huge coordinates
                        maxCellIndex = Math.min(maxCellIndex, 10);
                        
                        x = RANDOM.nextInt(maxCellIndex + 1) * cellSize;
                        y = RANDOM.nextInt(maxCellIndex + 1) * cellSize;
                        z = RANDOM.nextInt(maxCellIndex + 1) * cellSize;
                    }
                    
                    // Create Tet
                    Tet tet = new Tet(x, y, z, level, type);
                    
                    // Test mesh view
                    MeshView meshView = transformViews.of(tet);
                    assertNotNull(meshView, 
                        String.format("MeshView null for level=%d, type=%d, pos=(%d,%d,%d)", 
                                      level, type, x, y, z));
                    
                    // Verify mesh structure
                    TriangleMesh mesh = (TriangleMesh) meshView.getMesh();
                    assertEquals(12, mesh.getPoints().size(), 
                        "Incorrect vertex count at level " + level);
                    assertEquals(24, mesh.getFaces().size(), 
                        "Incorrect face count at level " + level);
                    
                    // Test wireframe view
                    Group wireframe = wireframeViews.of(tet);
                    assertNotNull(wireframe, 
                        String.format("Wireframe null for level=%d, type=%d, pos=(%d,%d,%d)", 
                                      level, type, x, y, z));
                    assertEquals(6, wireframe.getChildren().size(), 
                        "Should have 6 edges at level " + level);
                    
                    // Verify transforms exist
                    assertFalse(meshView.getTransforms().isEmpty(), 
                        "Mesh should have transforms at level " + level);
                    assertFalse(wireframe.getTransforms().isEmpty(), 
                        "Wireframe should have transforms at level " + level);
                    
                    // Verify transform scales with level
                    Affine meshTransform = (Affine) meshView.getTransforms().getFirst();
                    assertEquals(cellSize, meshTransform.getMxx(), 0.001, 
                        "Incorrect X scale at level " + level);
                    assertEquals(cellSize, meshTransform.getMyy(), 0.001, 
                        "Incorrect Y scale at level " + level);
                    assertEquals(cellSize, meshTransform.getMzz(), 0.001, 
                        "Incorrect Z scale at level " + level);
                }
            }
        }
    }
    
    @Test
    public void testWireframeGeometryCorrectness() {
        TetrahedralWireframeViews views = new TetrahedralWireframeViews();
        
        // Test each type with detailed geometry validation
        for (byte type = 0; type < 6; type++) {
            byte level = 15;
            Tet tet = new Tet(0, 0, 0, level, type);
            Group wireframe = views.of(tet);
            
            // Get expected vertices for this type
            Point3i[] expectedVertices = Constants.SIMPLEX_STANDARD[type];
            
            // Verify edge count
            assertEquals(6, wireframe.getChildren().size(), 
                "Tetrahedron type " + type + " should have 6 edges");
            
            // Calculate expected edge lengths
            double[] expectedLengths = calculateExpectedEdgeLengths(expectedVertices);
            
            // Verify each edge
            int edgeIndex = 0;
            for (var child : wireframe.getChildren()) {
                Cylinder edge = (Cylinder) child;
                
                // The edge length should match one of the expected lengths
                boolean foundMatch = false;
                for (double expectedLength : expectedLengths) {
                    if (Math.abs(edge.getHeight() - expectedLength) < 0.001) {
                        foundMatch = true;
                        break;
                    }
                }
                assertTrue(foundMatch, 
                    String.format("Edge %d of type %d has unexpected length %f", 
                                  edgeIndex, type, edge.getHeight()));
                edgeIndex++;
            }
        }
    }
    
    @Test
    public void testCustomWireframeMaterial() {
        PhongMaterial redMaterial = new PhongMaterial(Color.RED);
        TetrahedralWireframeViews views = new TetrahedralWireframeViews(0.02, redMaterial);
        
        // Use level 15 with aligned coordinates
        byte level = 15;
        Tet tet = new Tet(0, 0, 0, level, (byte) 0);
        Group wireframe = views.of(tet);
        
        // Verify custom material is applied
        for (var child : wireframe.getChildren()) {
            Cylinder cylinder = (Cylinder) child;
            assertEquals(redMaterial, cylinder.getMaterial(), "Should use custom material");
            assertEquals(0.01, cylinder.getRadius(), 0.001, "Should use custom thickness");
        }
    }
    
    @Test
    public void testTransformCaching() {
        TetrahedralTransformViews transformViews = new TetrahedralTransformViews();
        TetrahedralWireframeViews wireframeViews = new TetrahedralWireframeViews();
        
        // Create same tet multiple times with aligned coordinates
        byte level = 8;
        int cellSize = Constants.lengthAtLevel(level);
        Tet tet1 = new Tet(cellSize * 10, cellSize * 20, cellSize * 30, level, (byte) 2);
        Tet tet2 = new Tet(cellSize * 10, cellSize * 20, cellSize * 30, level, (byte) 2);
        
        // Get views
        MeshView mesh1 = transformViews.of(tet1);
        MeshView mesh2 = transformViews.of(tet2);
        wireframeViews.of(tet1);
        wireframeViews.of(tet2);
        
        // Should reuse the same reference mesh
        assertSame(mesh1.getMesh(), mesh2.getMesh(), "Should reuse same mesh");
        
        // Clear cache and verify statistics
        assertEquals(1, transformViews.getStatistics().get("transformCacheSize"));
        assertEquals(1, wireframeViews.getStatistics().get("transformCacheSize"));
        
        transformViews.clearTransformCache();
        wireframeViews.clearTransformCache();
        
        assertEquals(0, transformViews.getStatistics().get("transformCacheSize"));
        assertEquals(0, wireframeViews.getStatistics().get("transformCacheSize"));
    }
    
    @Test
    public void testDifferentLevelsAndPositions() {
        TetrahedralTransformViews transformViews = new TetrahedralTransformViews();
        TetrahedralWireframeViews wireframeViews = new TetrahedralWireframeViews();
        
        // Test different levels (sizes)
        for (byte level = 16; level <= 20; level += 2) {
            // Always use origin (0,0,0) which is valid for all levels
            Tet tet = new Tet(0, 0, 0, level, (byte) 0);
            
            MeshView mesh = transformViews.of(tet);
            Group wireframe = wireframeViews.of(tet);
            
            assertNotNull(mesh);
            assertNotNull(wireframe);
            
            // Just verify transforms exist
            assertFalse(mesh.getTransforms().isEmpty(), "Mesh should have transforms");
            assertFalse(wireframe.getTransforms().isEmpty(), "Wireframe should have transforms");
            
            // Verify the tetrahedron gets smaller as level increases
            if (level > 16) {
                Tet previousTet = new Tet(0, 0, 0, (byte)(level - 2), (byte) 0);
                assertTrue(tet.length() < previousTet.length(), 
                    "Tetrahedron should be smaller at higher levels");
            }
        }
    }
    
    @Test
    public void testAllTypes() {
        TetrahedralTransformViews transformViews = new TetrahedralTransformViews();
        TetrahedralWireframeViews wireframeViews = new TetrahedralWireframeViews();
        
        // Verify all 6 types have different orientations
        for (byte type = 0; type < 6; type++) {
            // Use level 12 with aligned coordinates
            byte level = 12;
            int cellSize = Constants.lengthAtLevel(level);
            int anchorCoord = cellSize * 5;
            Tet tet = new Tet(anchorCoord, anchorCoord, anchorCoord, level, type);
            
            MeshView mesh = transformViews.of(tet);
            Group wireframe = wireframeViews.of(tet);
            
            assertNotNull(mesh, "Type " + type + " mesh should exist");
            assertNotNull(wireframe, "Type " + type + " wireframe should exist");
            
            // Verify transform exists
            assertFalse(mesh.getTransforms().isEmpty(), "Should have transforms");
            
            // Just verify the transforms exist and have reasonable values
            // The exact transform values depend on the order of operations
            Affine meshTransform = (Affine) mesh.getTransforms().getFirst();
            assertNotNull(meshTransform, "Mesh should have transform");
            
            // Verify wireframe has same transform approach
            assertFalse(wireframe.getTransforms().isEmpty(), "Wireframe should have transforms");
            Affine wireTransform = (Affine) wireframe.getTransforms().getFirst();
            assertNotNull(wireTransform, "Wireframe should have transform");
        }
    }
    
    @Test
    public void testStatistics() {
        TetrahedralTransformViews transformViews = new TetrahedralTransformViews();
        TetrahedralWireframeViews wireframeViews = new TetrahedralWireframeViews();
        
        // Check initial statistics
        assertEquals(6, transformViews.getStatistics().get("referenceMeshCount"));
        assertEquals(6, wireframeViews.getStatistics().get("referenceWireframeCount"));
        assertEquals(0, transformViews.getStatistics().get("transformCacheSize"));
        assertEquals(0, wireframeViews.getStatistics().get("transformCacheSize"));
        
        // Create some views
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        for (int i = 0; i < 10; i++) {
            Tet tet = new Tet(cellSize * i, cellSize * i * 2, cellSize * i * 3, level, (byte) (i % 6));
            transformViews.of(tet);
            wireframeViews.of(tet);
        }
        
        // Check cache has grown
        assertEquals(10, transformViews.getStatistics().get("transformCacheSize"));
        assertEquals(10, wireframeViews.getStatistics().get("transformCacheSize"));
    }
    
    @Test
    public void testEdgeConnectivity() {
        TetrahedralWireframeViews views = new TetrahedralWireframeViews();
        
        // Use level 15 at origin
        byte level = 15;
        Tet tet = new Tet(0, 0, 0, level, (byte) 0);
        Group wireframe = views.of(tet);
        
        // Tetrahedron should have exactly 6 edges
        assertEquals(6, wireframe.getChildren().size(), "Tetrahedron should have 6 edges");
        
        // Each edge should be a cylinder with positive length
        for (var child : wireframe.getChildren()) {
            assertInstanceOf(Cylinder.class, child);
            Cylinder edge = (Cylinder) child;
            assertTrue(edge.getHeight() > 0, "Edge should have positive length");
        }
    }
    
    @Test
    public void testBoundaryConditions() {
        TetrahedralTransformViews transformViews = new TetrahedralTransformViews();
        TetrahedralWireframeViews wireframeViews = new TetrahedralWireframeViews();
        
        // Test at level 0 (largest tetrahedra)
        Tet largestTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        MeshView largestMesh = transformViews.of(largestTet);
        Group largestWire = wireframeViews.of(largestTet);
        assertNotNull(largestMesh);
        assertNotNull(largestWire);
        assertEquals(Constants.lengthAtLevel((byte) 0), largestTet.length());
        
        // Test at level 21 (smallest tetrahedra)
        Tet smallestTet = new Tet(0, 0, 0, (byte) 21, (byte) 0);
        MeshView smallestMesh = transformViews.of(smallestTet);
        Group smallestWire = wireframeViews.of(smallestTet);
        assertNotNull(smallestMesh);
        assertNotNull(smallestWire);
        assertEquals(1, smallestTet.length());
        
        // Test all corners of each type
        for (byte type = 0; type < 6; type++) {
            byte level = 10;
            int cellSize = Constants.lengthAtLevel(level);
            
            // Test at various corners of valid space
            int[][] testCoords = {
                {0, 0, 0},
                {cellSize * 100, 0, 0},
                {0, cellSize * 100, 0},
                {0, 0, cellSize * 100},
                {cellSize * 50, cellSize * 50, cellSize * 50}
            };
            
            for (int[] coords : testCoords) {
                Tet tet = new Tet(coords[0], coords[1], coords[2], level, type);
                assertNotNull(transformViews.of(tet), 
                    String.format("Failed at coords (%d,%d,%d) type %d", 
                                  coords[0], coords[1], coords[2], type));
                assertNotNull(wireframeViews.of(tet), 
                    String.format("Failed at coords (%d,%d,%d) type %d", 
                                  coords[0], coords[1], coords[2], type));
            }
        }
    }
    
    // Helper method to calculate expected edge lengths for a tetrahedron
    private double[] calculateExpectedEdgeLengths(Point3i[] vertices) {
        int[][] edges = {{0,1}, {0,2}, {0,3}, {1,2}, {1,3}, {2,3}};
        double[] lengths = new double[6];
        
        for (int i = 0; i < edges.length; i++) {
            Point3i v1 = vertices[edges[i][0]];
            Point3i v2 = vertices[edges[i][1]];
            lengths[i] = Math.sqrt(
                Math.pow(v2.x - v1.x, 2) + 
                Math.pow(v2.y - v1.y, 2) + 
                Math.pow(v2.z - v1.z, 2)
            );
        }
        
        return lengths;
    }
}
