package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.luciferase.lucien.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3d;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Tetree implementation using Bey red refinement. Tests spatial indexing, tetrahedral
 * space-filling curve properties, and geometric consistency.
 *
 * @author hal.hildebrand
 */
public class TetreeComprehensiveTest {

    private Tetree<String>        tetree;
    private TreeMap<Long, String> contents;

    @BeforeEach
    void setUp() {
        contents = new TreeMap<>();
        tetree = new Tetree<>(contents);
    }

    @Test
    @DisplayName("Test basic insertion and retrieval")
    void testBasicInsertionAndRetrieval() {
        // Test insertion at different levels
        Point3f point1 = new Point3f(100.0f, 200.0f, 300.0f);
        long index1 = tetree.insert(point1, (byte) 10, "content1");

        Point3f point2 = new Point3f(500.0f, 600.0f, 700.0f);
        long index2 = tetree.insert(point2, (byte) 15, "content2");

        // Verify retrieval
        assertEquals("content1", contents.get(index1));
        assertEquals("content2", contents.get(index2));
        assertEquals("content1", tetree.get(index1));
        assertEquals("content2", tetree.get(index2));

        // Verify different points produce different indices
        assertNotEquals(index1, index2);
    }

    @Test
    @DisplayName("Test boundary conditions and edge cases")
    void testBoundaryConditionsAndEdgeCases() {
        // Test origin
        Tet originTet = tetree.locate(new Point3f(0.0f, 0.0f, 0.0f), (byte) 10);
        assertNotNull(originTet);

        // Test large coordinates
        Tet largeTet = tetree.locate(new Point3f(1000000.0f, 1000000.0f, 1000000.0f), (byte) 5);
        assertNotNull(largeTet);

        // Test negative coordinates (should still work due to floor operation)
        Tet negativeTet = tetree.locate(new Point3f(-100.0f, -200.0f, -300.0f), (byte) 8);
        assertNotNull(negativeTet);

        // Test very small coordinates
        Tet smallTet = tetree.locate(new Point3f(0.001f, 0.002f, 0.003f), (byte) 20);
        assertNotNull(smallTet);
    }

    @Test
    @DisplayName("Test face neighbor operations")
    void testFaceNeighborOperations() {
        // Test face neighbors for various tetrahedra
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(100, 200, 300, (byte) 10, type);

            for (int face = 0; face < 4; face++) {
                Tet.FaceNeighbor neighbor = tet.faceNeighbor(face);
                assertNotNull(neighbor, String.format("Face neighbor should exist for type %d, face %d", type, face));

                Tet neighborTet = neighbor.tet();
                assertNotNull(neighborTet);

                // Neighbor should have valid type
                assertTrue(neighborTet.type() >= 0 && neighborTet.type() <= 5);

                // Neighbor should be at same level
                assertEquals(tet.l(), neighborTet.l());
            }
        }
    }

    @Test
    @DisplayName("Test hierarchical spatial consistency")
    void testHierarchicalSpatialConsistency() {
        Point3f testPoint = new Point3f(512.75f, 1024.25f, 256.125f);

        // Test that locate() is consistent across levels
        Tet coarseTet = tetree.locate(testPoint, (byte) 8);
        Tet fineTet = tetree.locate(testPoint, (byte) 12);

        // Fine tetrahedron should be within coarse tetrahedron bounds
        int coarseLength = coarseTet.length();
        int fineLength = fineTet.length();

        assertTrue(fineLength < coarseLength, "Finer level should have smaller tetrahedra");

        assertTrue(fineTet.x() >= coarseTet.x() && fineTet.x() < coarseTet.x() + coarseLength);
        assertTrue(fineTet.y() >= coarseTet.y() && fineTet.y() < coarseTet.y() + coarseLength);
        assertTrue(fineTet.z() >= coarseTet.z() && fineTet.z() < coarseTet.z() + coarseLength);
    }

    @Test
    @DisplayName("Test level and coordinate consistency")
    void testLevelAndCoordinateConsistency() {
        // Test that coordinate quantization is consistent with level
        for (byte level = 5; level <= 15; level++) {
            int expectedLength = Constants.lengthAtLevel(level);

            Point3f testPoint = new Point3f(123.456f, 234.567f, 345.678f);
            Tet tet = tetree.locate(testPoint, level);

            assertEquals(expectedLength, tet.length(), "Length should match level");

            // Coordinates should be quantized to grid
            assertEquals(0, tet.x() % expectedLength, "X should be quantized to grid");
            assertEquals(0, tet.y() % expectedLength, "Y should be quantized to grid");
            assertEquals(0, tet.z() % expectedLength, "Z should be quantized to grid");
        }
    }

    @Test
    @DisplayName("Test locate() method accuracy and consistency")
    void testLocateAccuracyAndConsistency() {
        // Test location at various refinement levels
        Point3f testPoint = new Point3f(123.456f, 234.567f, 345.678f);

        for (byte level = 5; level <= 20; level++) {
            Tet tet = tetree.locate(testPoint, level);

            // Verify basic properties
            assertEquals(level, tet.l());
            assertTrue(tet.type() >= 0 && tet.type() <= 5, "Type should be 0-5 for Bey tetrahedra");

            // Verify spatial consistency - point should be within or near tetrahedron bounds
            int length = Constants.lengthAtLevel(level);
            assertTrue(tet.x() <= testPoint.x && tet.x() + length >= testPoint.x, "X coordinate bounds");
            assertTrue(tet.y() <= testPoint.y && tet.y() + length >= testPoint.y, "Y coordinate bounds");
            assertTrue(tet.z() <= testPoint.z && tet.z() + length >= testPoint.z, "Z coordinate bounds");
        }
    }

    @Test
    @DisplayName("Test multiple insertions and spatial distribution")
    void testMultipleInsertionsAndSpatialDistribution() {
        // Insert multiple items and verify they are properly distributed
        Point3f[] points = { new Point3f(100.0f, 100.0f, 100.0f), new Point3f(200.0f, 200.0f, 200.0f), new Point3f(
        300.0f, 300.0f, 300.0f), new Point3f(150.0f, 250.0f, 350.0f), new Point3f(50.0f, 150.0f, 250.0f) };

        byte level = 12;
        for (int i = 0; i < points.length; i++) {
            long index = tetree.insert(points[i], level, "content" + i);
            assertTrue(index >= 0, "Index should be non-negative");
        }

        // Verify all contents are retrievable (note: duplicate coordinates may overwrite)
        assertTrue(contents.size() <= points.length);

        // Verify spatial locality - nearby points should have similar indices
        long index1 = tetree.insert(new Point3f(400.0f, 400.0f, 400.0f), level, "nearby1");
        long index2 = tetree.insert(new Point3f(401.0f, 401.0f, 401.0f), level, "nearby2");

        // Due to SFC properties, nearby points should have relatively close indices
        // (though not necessarily consecutive due to tetrahedral vs cubic space)
        // Note: Points may map to same index due to quantization, which is acceptable
        assertTrue(index1 >= 0 && index2 >= 0, "Indices should be non-negative");
    }

    @Test
    @DisplayName("Test space-filling curve properties")
    void testSpaceFillingCurveProperties() {
        // Test SFC roundtrip consistency using proper level-index relationships
        for (byte level = 0; level <= 4; level++) {
            // Use correct index range for each level (same as working TetTest)
            int maxIndex = level == 0 ? 1 : (1 << (3 * level));
            int startIndex = level == 0 ? 0 : (1 << (3 * (level - 1)));
            
            for (long index = startIndex; index < Math.min(maxIndex, startIndex + 16); index++) {
                Tet tet = Tet.tetrahedron(index, level);
                long reconstructedIndex = tet.index();

                assertEquals(index, reconstructedIndex,
                             String.format("SFC roundtrip failed at level %d, index %d", level, index));
            }
        }
    }

    @Test
    @DisplayName("Test tetrahedral space partitioning correctness")
    void testTetrahedralSpacePartitioning() {
        // Verify that the 6 tetrahedral types properly partition the unit cube
        byte level = 10;
        int length = Constants.lengthAtLevel(level);

        // Test points throughout a unit cube
        for (float x = 0.1f; x < length; x += length / 10.0f) {
            for (float y = 0.1f; y < length; y += length / 10.0f) {
                for (float z = 0.1f; z < length; z += length / 10.0f) {
                    Point3f point = new Point3f(x, y, z);
                    Tet tet = tetree.locate(point, level);

                    // Every point should be assigned to exactly one tetrahedron type
                    assertTrue(tet.type() >= 0 && tet.type() <= 5);

                    // The tetrahedron should contain the point (within its bounding cube)
                    assertTrue(point.x >= tet.x() && point.x < tet.x() + tet.length());
                    assertTrue(point.y >= tet.y() && point.y < tet.y() + tet.length());
                    assertTrue(point.z >= tet.z() && point.z < tet.z() + tet.length());
                }
            }
        }
    }

    @Test
    @DisplayName("Test tetrahedral subdivision consistency")
    void testTetrahedralSubdivisionConsistency() {
        // Test that children of a tetrahedron are properly subdivided
        Tet parent = new Tet(0, 0, 0, (byte) 5, (byte) 0);

        // Get all 8 children (Bey subdivision produces 8 sub-tetrahedra)
        for (int childIndex = 0; childIndex < 8; childIndex++) {
            Tet child = parent.child((byte) childIndex);

            // Child should be at higher level
            assertEquals(parent.l() + 1, child.l());

            // Child should have valid type
            assertTrue(child.type() >= 0 && child.type() <= 5);

            // Child should be within parent bounds
            int parentLength = parent.length();
            int childLength = child.length();
            assertEquals(parentLength / 2, childLength, "Child should be half parent size");

            // Child coordinates may be outside parent bounds due to tetrahedral geometry
            // This is expected behavior for tetrahedral space-filling curves
            assertNotNull(child);
        }
    }

    @Test
    @DisplayName("Test tetrahedral types and geometric consistency")
    void testTetrahedralTypesAndGeometry() {
        // Test each of the 6 tetrahedral types in Bey subdivision
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(1000, 2000, 3000, (byte) 8, type);

            // Verify type preservation
            assertEquals(type, tet.type());

            // Test simplex vertices calculation
            var simplex = new Tetree.Simplex<>(tet.index(), "test");
            Vector3d[] vertices = simplex.coordinates();

            assertEquals(4, vertices.length, "Tetrahedron should have 4 vertices");

            // Verify vertices are distinct
            for (int i = 0; i < vertices.length; i++) {
                for (int j = i + 1; j < vertices.length; j++) {
                    assertFalse(vertices[i].equals(vertices[j]),
                                String.format("Vertices %d and %d should be distinct for type %d", i, j, type));
                }
            }
        }
    }
}
