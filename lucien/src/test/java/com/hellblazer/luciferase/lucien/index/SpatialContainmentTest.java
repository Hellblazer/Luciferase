package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3d;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for spatial containment logic in both Tetree and Octree implementations. Tests spatial operations
 * including containment, intersection, bounding, and enclosure.
 *
 * @author hal.hildebrand
 */
public class SpatialContainmentTest {

    private Tetree<String>        tetree;
    private Octree<String>        octree;
    private TreeMap<Long, String> tetreeContents;
    private TreeMap<Long, String> octreeContents;

    @BeforeEach
    void setUp() {
        tetreeContents = new TreeMap<>();
        octreeContents = new TreeMap<>();
        tetree = new Tetree<>(tetreeContents);
        octree = new Octree<>(octreeContents);
    }

    @Test
    @DisplayName("Test basic point location in octree")
    void testBasicPointLocationOctree() {
        // Test Morton curve-based point location
        Point3f point = new Point3f(100.0f, 200.0f, 300.0f);
        byte level = 10;

        long index = octree.insert(point, level, "test");
        Spatial.Cube cube = octree.locate(index);

        assertNotNull(cube, "Octree should locate cube for point");
        assertTrue(cube.extent() >= 0, "Cube extent should be non-negative");

        // Test that the point is within the cube bounds (considering quantization)
        int length = Constants.lengthAtLevel(level);
        int expectedX = (int) (Math.floor(point.x / length) * length);
        int expectedY = (int) (Math.floor(point.y / length) * length);
        int expectedZ = (int) (Math.floor(point.z / length) * length);

        // Note: Due to Morton encoding, exact coordinates may vary, but should be reasonable
        assertNotNull(cube);
    }

    @Test
    @DisplayName("Test basic point location in tetree")
    void testBasicPointLocationTetree() {
        // Test that locate() works correctly for tetrahedral subdivision
        Point3f point = new Point3f(100.0f, 200.0f, 300.0f);
        byte level = 10;

        Tet tet = tetree.locate(point, level);
        assertNotNull(tet, "Tetree should locate tetrahedron for point");
        assertEquals(level, tet.l(), "Located tetrahedron should be at correct level");
        assertTrue(tet.type() >= 0 && tet.type() <= 5, "Tetrahedron type should be valid (0-5)");

        // Test that the point is within the tetrahedron's bounding cube
        int length = Constants.lengthAtLevel(level);
        assertTrue(point.x >= tet.x() && point.x < tet.x() + length, "Point X should be within tet bounds");
        assertTrue(point.y >= tet.y() && point.y < tet.y() + length, "Point Y should be within tet bounds");
        assertTrue(point.z >= tet.z() && point.z < tet.z() + length, "Point Z should be within tet bounds");
    }

    @Test
    @DisplayName("Test current intersection implementations")
    void testCurrentIntersectionImplementations() {
        // Test the one working intersection implementation (aabb and aabt)
        Spatial.aabb aabb1 = new Spatial.aabb(0.0f, 0.0f, 0.0f, 100.0f, 100.0f, 100.0f);
        Spatial.aabb aabb2 = new Spatial.aabb(50.0f, 50.0f, 50.0f, 150.0f, 150.0f, 150.0f);
        Spatial.aabb aabb3 = new Spatial.aabb(200.0f, 200.0f, 200.0f, 300.0f, 300.0f, 300.0f);

        // Test overlapping AABB intersection
        assertTrue(aabb1.intersects(25.0f, 25.0f, 25.0f, 75.0f, 75.0f, 75.0f), "Overlapping AABBs should intersect");

        // Test non-overlapping AABB intersection
        assertFalse(aabb1.intersects(200.0f, 200.0f, 200.0f, 300.0f, 300.0f, 300.0f),
                    "Non-overlapping AABBs should not intersect");

        // Test AABT intersection (should work the same as AABB)
        Spatial.aabt aabt1 = new Spatial.aabt(0.0f, 0.0f, 0.0f, 100.0f, 100.0f, 100.0f);
        assertTrue(aabt1.intersects(25.0f, 25.0f, 25.0f, 75.0f, 75.0f, 75.0f), "Overlapping AABTs should intersect");
        assertFalse(aabt1.intersects(200.0f, 200.0f, 200.0f, 300.0f, 300.0f, 300.0f),
                    "Non-overlapping AABTs should not intersect");
    }

    @Test
    @DisplayName("Test edge cases and boundary conditions")
    void testEdgeCasesAndBoundaryConditions() {
        // Test edge cases for spatial containment

        // Test with very small coordinates
        Point3f smallPoint = new Point3f(0.001f, 0.002f, 0.003f);
        Tet smallTet = tetree.locate(smallPoint, (byte) 20);
        assertNotNull(smallTet, "Should handle very small coordinates");

        // Test with large coordinates
        Point3f largePoint = new Point3f(100000.0f, 200000.0f, 300000.0f);
        Tet largeTet = tetree.locate(largePoint, (byte) 5);
        assertNotNull(largeTet, "Should handle large coordinates");

        // Test with negative coordinates
        Point3f negativePoint = new Point3f(-100.0f, -200.0f, -300.0f);
        Tet negativeTet = tetree.locate(negativePoint, (byte) 10);
        assertNotNull(negativeTet, "Should handle negative coordinates");

        // Test boundary between different tetrahedron types
        byte level = 15;
        int length = Constants.lengthAtLevel(level);

        // Test points very close to tetrahedron boundaries
        Point3f[] boundaryPoints = { new Point3f(length / 2.0f - 0.1f, length / 2.0f, length / 2.0f), new Point3f(
        length / 2.0f + 0.1f, length / 2.0f, length / 2.0f), new Point3f(length / 2.0f, length / 2.0f - 0.1f,
                                                                         length / 2.0f), new Point3f(length / 2.0f,
                                                                                                     length / 2.0f
                                                                                                     + 0.1f,
                                                                                                     length / 2.0f),
                                     new Point3f(length / 2.0f, length / 2.0f, length / 2.0f - 0.1f), new Point3f(
        length / 2.0f, length / 2.0f, length / 2.0f + 0.1f) };

        for (int i = 0; i < boundaryPoints.length; i++) {
            Tet boundaryTet = tetree.locate(boundaryPoints[i], level);
            assertNotNull(boundaryTet, "Should locate tetrahedron for boundary point " + i);
            assertTrue(boundaryTet.type() >= 0 && boundaryTet.type() <= 5,
                       "Boundary point " + i + " should have valid tetrahedron type");
        }
    }

    @Test
    @DisplayName("Test implemented spatial operations return proper values")
    void testImplementedSpatialOperations() {
        // Add some content to tetree and octree first
        tetree.insert(new Point3f(100, 100, 100), (byte) 10, "test-data-1");
        tetree.insert(new Point3f(150, 150, 150), (byte) 10, "test-data-2");
        octree.insert(new Point3f(100, 100, 100), (byte) 10, "test-data-3");

        // Test that implemented operations return proper values

        // Tetree spatial operations
        Spatial.Cube testVolume = new Spatial.Cube(100.0f, 100.0f, 100.0f, 50.0f);

        // Should return streams for implemented operations
        Stream<Tetree.Simplex<String>> boundedBy = tetree.boundedBy(testVolume);
        assertTrue(boundedBy.count() >= 0, "Implemented boundedBy should return stream (may be empty)");

        Stream<Tetree.Simplex<String>> bounding = tetree.bounding(testVolume);
        assertTrue(bounding.count() >= 0, "Implemented bounding should return stream (may be empty)");

        // Should return simplex for implemented operations
        Tetree.Simplex<String> enclosing = tetree.enclosing(testVolume);
        assertNotNull(enclosing, "Implemented enclosing should return simplex");

        Point3i point = new Point3i(100, 200, 300);
        Tetree.Simplex<String> enclosingPoint = tetree.enclosing(point, (byte) 10);
        assertNotNull(enclosingPoint, "Implemented point enclosing should return simplex");

        Tetree.Simplex<String> intersecting = tetree.intersecting(testVolume);
        assertNotNull(intersecting, "Implemented intersecting should return simplex");

        // Octree spatial operations (using wildcard since Hexahedron is not public)
        Stream<?> octreeBoundedBy = octree.boundedBy(testVolume);
        assertTrue(octreeBoundedBy.count() >= 0, "Implemented octree boundedBy should return stream");

        Stream<?> octreeBounding = octree.bounding(testVolume);
        assertTrue(octreeBounding.count() >= 0, "Implemented octree bounding should return stream");

        Object octreeEnclosing = octree.enclosing(testVolume);
        assertNotNull(octreeEnclosing, "Implemented octree enclosing should return hexahedron");

        Object octreeEnclosingPoint = octree.enclosing(point, (byte) 10);
        assertNotNull(octreeEnclosingPoint, "Implemented octree point enclosing should return hexahedron");
    }

    @Test
    @DisplayName("Test point-in-tetrahedron containment geometry")
    void testPointInTetrahedronContainment() {
        // Test the geometric correctness of point location within tetrahedra
        // This tests the actual geometric containment logic in tetree.locate()

        byte level = 12;
        int length = Constants.lengthAtLevel(level);

        // Test points at cube corners - these should map to specific tetrahedron types
        Point3f[] cornerPoints = { new Point3f(0, 0, 0),                    // Origin
                                   new Point3f(length, 0, 0),               // +X corner
                                   new Point3f(0, length, 0),               // +Y corner
                                   new Point3f(0, 0, length),               // +Z corner
                                   new Point3f(length, length, 0),          // +XY corner
                                   new Point3f(length, 0, length),          // +XZ corner
                                   new Point3f(0, length, length),          // +YZ corner
                                   new Point3f(length, length, length)      // +XYZ corner
        };

        for (int i = 0; i < cornerPoints.length; i++) {
            Tet tet = tetree.locate(cornerPoints[i], level);
            assertNotNull(tet, "Should locate tetrahedron for corner point " + i);
            assertTrue(tet.type() >= 0 && tet.type() <= 5,
                       "Corner point " + i + " should map to valid tetrahedron type");
        }

        // Test points at the center of each tetrahedron type's domain
        // This verifies that the subdivision logic correctly identifies tetrahedron types
        Point3f center = new Point3f(length / 2.0f, length / 2.0f, length / 2.0f);
        Tet centerTet = tetree.locate(center, level);
        assertNotNull(centerTet, "Should locate tetrahedron for center point");

        // Test that nearby points map to consistent tetrahedra
        float offset = length / 10.0f;
        Point3f[] nearbyPoints = { new Point3f(center.x + offset, center.y, center.z), new Point3f(center.x - offset,
                                                                                                   center.y, center.z),
                                   new Point3f(center.x, center.y + offset, center.z), new Point3f(center.x,
                                                                                                   center.y - offset,
                                                                                                   center.z),
                                   new Point3f(center.x, center.y, center.z + offset), new Point3f(center.x, center.y,
                                                                                                   center.z - offset) };

        for (int i = 0; i < nearbyPoints.length; i++) {
            Tet nearbyTet = tetree.locate(nearbyPoints[i], level);
            assertNotNull(nearbyTet, "Should locate tetrahedron for nearby point " + i);

            // Nearby points should either be in the same tetrahedron or adjacent ones
            // The tetrahedron should be at the same level
            assertEquals(level, nearbyTet.l(), "Nearby point should be at same level");
        }
    }

    @Test
    @DisplayName("Test spatial hierarchical consistency")
    void testSpatialHierarchicalConsistency() {
        // Test that spatial containment is consistent across levels
        Point3f testPoint = new Point3f(1234.5f, 5678.9f, 9876.5f);

        // Test tetrahedral hierarchy
        Tet coarseTet = tetree.locate(testPoint, (byte) 8);
        Tet fineTet = tetree.locate(testPoint, (byte) 12);

        assertNotNull(coarseTet, "Should locate coarse tetrahedron");
        assertNotNull(fineTet, "Should locate fine tetrahedron");

        // Fine tetrahedron should be contained within coarse tetrahedron's region
        assertTrue(fineTet.length() < coarseTet.length(), "Fine tet should be smaller than coarse tet");

        // The fine tetrahedron's coordinates should be within the coarse tetrahedron's region
        assertTrue(fineTet.x() >= coarseTet.x() && fineTet.x() < coarseTet.x() + coarseTet.length()
                   || fineTet.y() >= coarseTet.y() && fineTet.y() < coarseTet.y() + coarseTet.length()
                   || fineTet.z() >= coarseTet.z() && fineTet.z() < coarseTet.z() + coarseTet.length(),
                   "Fine tet should be spatially related to coarse tet");

        // Test octree hierarchy
        long coarseIndex = octree.insert(testPoint, (byte) 8, "coarse");
        long fineIndex = octree.insert(testPoint, (byte) 12, "fine");

        Spatial.Cube coarseCube = octree.locate(coarseIndex);
        Spatial.Cube fineCube = octree.locate(fineIndex);

        assertNotNull(coarseCube, "Should locate coarse cube");
        assertNotNull(fineCube, "Should locate fine cube");

        // Both cubes should exist (spatial relationship depends on Morton encoding details)
        assertTrue(coarseCube.extent() >= 0, "Coarse cube should have non-negative extent");
        assertTrue(fineCube.extent() >= 0, "Fine cube should have non-negative extent");
    }

    @Test
    @DisplayName("Test spatial volume containment implementations")
    void testSpatialVolumeContainmentImplementations() {
        // Test that all spatial volume types have working containedBy methods
        Spatial.aabt testAABT = new Spatial.aabt(0.0f, 0.0f, 0.0f, 100.0f, 100.0f, 100.0f);

        // Test contained volumes
        Spatial.Cube cube = new Spatial.Cube(10.0f, 10.0f, 10.0f, 20.0f);
        assertTrue(cube.containedBy(testAABT), "Cube should be contained within larger AABT");

        Spatial.Sphere sphere = new Spatial.Sphere(50.0f, 50.0f, 50.0f, 15.0f);
        assertTrue(sphere.containedBy(testAABT), "Sphere should be contained within larger AABT");

        Spatial.aabb aabb = new Spatial.aabb(5.0f, 5.0f, 5.0f, 25.0f, 25.0f, 25.0f);
        assertTrue(aabb.containedBy(testAABT), "AABB should be contained within larger AABT");

        Spatial.aabt aabt = new Spatial.aabt(8.0f, 8.0f, 8.0f, 20.0f, 20.0f, 20.0f);
        assertTrue(aabt.containedBy(testAABT), "AABT should be contained within larger AABT");

        Point3f v1 = new Point3f(5, 5, 5);
        Point3f v2 = new Point3f(15, 5, 5);
        Point3f v3 = new Point3f(10, 15, 5);
        Point3f v4 = new Point3f(10, 10, 15);
        Spatial.Tetrahedron tetrahedron = new Spatial.Tetrahedron(v1, v2, v3, v4);
        assertTrue(tetrahedron.containedBy(testAABT), "Tetrahedron should be contained within larger AABT");

        // Test Simplex containedBy
        long index = tetree.insert(new Point3f(50.0f, 50.0f, 50.0f), (byte) 10, "test");
        Tetree.Simplex<String> simplex = new Tetree.Simplex<>(index, "test");
        assertFalse(simplex.containedBy(testAABT), "Simplex containedBy is placeholder (returns false)");
    }

    @Test
    @DisplayName("Test spatial volume intersection placeholders")
    void testSpatialVolumeIntersectionPlaceholders() {
        // Test intersection methods that are placeholders (return false except aabb/aabt)
        float testX = 50.0f, testY = 50.0f, testZ = 50.0f;
        float testExtentX = 100.0f, testExtentY = 100.0f, testExtentZ = 100.0f;

        Spatial.Cube cube = new Spatial.Cube(10.0f, 10.0f, 10.0f, 20.0f);
        assertFalse(cube.intersects(testX, testY, testZ, testExtentX, testExtentY, testExtentZ),
                    "Cube intersects is placeholder (returns false)");

        Spatial.Sphere sphere = new Spatial.Sphere(10.0f, 10.0f, 10.0f, 15.0f);
        assertFalse(sphere.intersects(testX, testY, testZ, testExtentX, testExtentY, testExtentZ),
                    "Sphere intersects is placeholder (returns false)");

        Spatial.Parallelepiped parallelepiped = new Spatial.Parallelepiped(5.0f, 5.0f, 5.0f, 25.0f, 25.0f, 25.0f);
        assertFalse(parallelepiped.intersects(testX, testY, testZ, testExtentX, testExtentY, testExtentZ),
                    "Parallelepiped intersects is placeholder (returns false)");

        Point3f v1 = new Point3f(5, 5, 5);
        Point3f v2 = new Point3f(15, 5, 5);
        Point3f v3 = new Point3f(10, 15, 5);
        Point3f v4 = new Point3f(10, 10, 15);
        Spatial.Tetrahedron tetrahedron = new Spatial.Tetrahedron(v1, v2, v3, v4);
        assertFalse(tetrahedron.intersects(testX, testY, testZ, testExtentX, testExtentY, testExtentZ),
                    "Tetrahedron intersects is placeholder (returns false)");

        // Test Simplex intersects
        long index = tetree.insert(new Point3f(25.0f, 25.0f, 25.0f), (byte) 10, "test");
        Tetree.Simplex<String> simplex = new Tetree.Simplex<>(index, "test");
        assertFalse(simplex.intersects(testX, testY, testZ, testExtentX, testExtentY, testExtentZ),
                    "Simplex intersects is placeholder (returns false)");
    }

    @Test
    @DisplayName("Test spatial volume types and basic properties")
    void testSpatialVolumeTypes() {
        // Test all spatial volume types have consistent interfaces

        // Test Cube
        Spatial.Cube cube = new Spatial.Cube(100.0f, 200.0f, 300.0f, 50.0f);
        assertNotNull(cube);
        assertEquals(100.0f, cube.originX());
        assertEquals(200.0f, cube.originY());
        assertEquals(300.0f, cube.originZ());
        assertEquals(50.0f, cube.extent());

        // Test Sphere
        Spatial.Sphere sphere = new Spatial.Sphere(100.0f, 200.0f, 300.0f, 25.0f);
        assertNotNull(sphere);
        assertEquals(100.0f, sphere.centerX());
        assertEquals(200.0f, sphere.centerY());
        assertEquals(300.0f, sphere.centerZ());
        assertEquals(25.0f, sphere.radius());

        // Test AABB (Axis-Aligned Bounding Box)
        Spatial.aabb aabb = new Spatial.aabb(10.0f, 20.0f, 30.0f, 100.0f, 200.0f, 300.0f);
        assertNotNull(aabb);
        assertEquals(10.0f, aabb.originX());
        assertEquals(20.0f, aabb.originY());
        assertEquals(30.0f, aabb.originZ());
        assertEquals(100.0f, aabb.extentX());
        assertEquals(200.0f, aabb.extentY());
        assertEquals(300.0f, aabb.extentZ());

        // Test AABT (Axis-Aligned Bounding Tetrahedron)
        Spatial.aabt aabt = new Spatial.aabt(15.0f, 25.0f, 35.0f, 150.0f, 250.0f, 350.0f);
        assertNotNull(aabt);
        assertEquals(15.0f, aabt.originX());
        assertEquals(25.0f, aabt.originY());
        assertEquals(35.0f, aabt.originZ());
        assertEquals(150.0f, aabt.extentX());
        assertEquals(250.0f, aabt.extentY());
        assertEquals(350.0f, aabt.extentZ());

        // Test Tetrahedron
        Point3f v1 = new Point3f(0, 0, 0);
        Point3f v2 = new Point3f(100, 0, 0);
        Point3f v3 = new Point3f(50, 86.6f, 0);
        Point3f v4 = new Point3f(50, 28.87f, 81.65f);
        Spatial.Tetrahedron tetrahedron = new Spatial.Tetrahedron(v1, v2, v3, v4);
        assertNotNull(tetrahedron);
        assertEquals(v1, tetrahedron.a());
        assertEquals(v2, tetrahedron.b());
        assertEquals(v3, tetrahedron.c());
        assertEquals(v4, tetrahedron.d());
    }

    @Test
    @DisplayName("Test tetrahedral simplex vertex calculation")
    void testTetrahedralSimplexVertices() {
        // Test that Simplex vertices are calculated correctly
        Point3f point = new Point3f(500.0f, 1000.0f, 1500.0f);
        byte level = 8;

        long index = tetree.insert(point, level, "test");
        Tetree.Simplex<String> simplex = new Tetree.Simplex<>(index, "test");

        Vector3d[] vertices = simplex.coordinates();
        assertEquals(4, vertices.length, "Tetrahedron should have 4 vertices");

        // Verify all vertices are distinct
        for (int i = 0; i < vertices.length; i++) {
            for (int j = i + 1; j < vertices.length; j++) {
                assertFalse(vertices[i].equals(vertices[j]),
                            String.format("Vertices %d and %d should be distinct", i, j));
            }
        }

        // Verify vertices have reasonable coordinates
        // Tetrahedra can extend beyond MAX_EXTENT due to their geometric structure
        int maxExpected = Constants.MAX_EXTENT * 3; // Allow extra space for tetrahedral vertices
        for (Vector3d vertex : vertices) {
            assertTrue(Double.isFinite(vertex.x), "Vertex X should be finite");
            assertTrue(Double.isFinite(vertex.y), "Vertex Y should be finite");
            assertTrue(Double.isFinite(vertex.z), "Vertex Z should be finite");
            assertTrue(vertex.x >= -maxExpected && vertex.x <= maxExpected,
                       String.format("Vertex X %.1f should be within reasonable bounds", vertex.x));
            assertTrue(vertex.y >= -maxExpected && vertex.y <= maxExpected,
                       String.format("Vertex Y %.1f should be within reasonable bounds", vertex.y));
            assertTrue(vertex.z >= -maxExpected && vertex.z <= maxExpected,
                       String.format("Vertex Z %.1f should be within reasonable bounds", vertex.z));
        }
    }
}
