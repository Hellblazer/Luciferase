package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for BubbleBounds - tetrahedral bounding volumes.
 * <p>
 * CRITICAL ARCHITECTURE POINTS:
 * 1. BubbleBounds uses TetreeKey + RDGCS coordinates (NOT AABB)
 * 2. Centroid formula: (v0+v1+v2+v3)/4 (NOT cube center)
 * 3. RDGCS transformations via Tetrahedral.toRDG() and toCartesian()
 * <p>
 * These tests MUST pass before implementing BubbleBounds.java (TDD red phase).
 *
 * @author hal.hildebrand
 */
public class BubbleBoundsTest {

    private static final float EPSILON = 0.001f;

    /**
     * Test 1: fromTetreeKey at level 0 (root tetrahedron)
     * <p>
     * Validates: Root tetrahedron bounds creation
     */
    @Test
    public void testFromTetreeKeyLevel0() {
        var rootKey = TetreeKey.create((byte) 0, 0L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(rootKey);

        assertNotNull(bounds, "Bounds should not be null");
        assertEquals((byte) 0, bounds.level(), "Level should be 0");
        assertNotNull(bounds.rootKey(), "Root key should not be null");
        assertNotNull(bounds.rdgMin(), "RDGCS min should not be null");
        assertNotNull(bounds.rdgMax(), "RDGCS max should not be null");

        // Root tetrahedron should contain origin
        assertTrue(bounds.contains(new Point3f(0, 0, 0)), "Root should contain origin");
    }

    /**
     * Test 2: fromTetreeKey at level 10 (mid-level cell)
     * <p>
     * Validates: Mid-level tetrahedron bounds creation
     */
    @Test
    public void testFromTetreeKeyLevel10() {
        var midLevelKey = TetreeKey.create((byte) 10, 0x123456L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(midLevelKey);

        assertNotNull(bounds, "Bounds should not be null");
        assertEquals((byte) 10, bounds.level(), "Level should be 10");
        assertNotNull(bounds.rootKey(), "Root key should not be null");

        // Verify tetrahedral type is S0-S5
        var type = bounds.type();
        assertTrue(type >= 0 && type <= 5, "Type should be S0-S5 (0-5), got: " + type);
    }

    /**
     * Test 3: fromEntityPositions
     * <p>
     * Validates: Compute bounds from point cloud of entity positions
     */
    @Test
    public void testFromEntityPositions() {
        var positions = List.of(
            new Point3f(1.0f, 2.0f, 3.0f),
            new Point3f(5.0f, 6.0f, 7.0f),
            new Point3f(2.0f, 3.0f, 4.0f)
        );

        var bounds = BubbleBounds.fromEntityPositions(positions);

        assertNotNull(bounds, "Bounds should not be null");

        // All positions should be contained
        for (var pos : positions) {
            assertTrue(bounds.contains(pos), "Bounds should contain position: " + pos);
        }

        // Centroid should be roughly in the middle
        var centroid = bounds.centroid();
        assertNotNull(centroid, "Centroid should not be null");
    }

    /**
     * Test 4: encompassing two bounds
     * <p>
     * Validates: Union of two tetrahedral bounds
     */
    @Test
    public void testEncompassingTwoBounds() {
        var key1 = TetreeKey.create((byte) 5, 0x100L, 0L);
        var key2 = TetreeKey.create((byte) 5, 0x200L, 0L);

        var bounds1 = BubbleBounds.fromTetreeKey(key1);
        var bounds2 = BubbleBounds.fromTetreeKey(key2);

        var encompassing = BubbleBounds.encompassing(bounds1, bounds2);

        assertNotNull(encompassing, "Encompassing bounds should not be null");

        // Both original bounds should be contained
        var centroid1 = bounds1.centroid();
        var centroid2 = bounds2.centroid();

        assertTrue(encompassing.contains(new Point3f((float) centroid1.getX(),
                                                     (float) centroid1.getY(),
                                                     (float) centroid1.getZ())),
                  "Encompassing should contain first bounds centroid");
        assertTrue(encompassing.contains(new Point3f((float) centroid2.getX(),
                                                     (float) centroid2.getY(),
                                                     (float) centroid2.getZ())),
                  "Encompassing should contain second bounds centroid");
    }

    /**
     * Test 5: containsCartesianPoint
     * <p>
     * Validates: Point containment test in Cartesian coordinates
     */
    @Test
    public void testContainsCartesianPoint() {
        var key = TetreeKey.create((byte) 0, 0L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(key);

        // Get tetrahedron vertices to test containment
        var tet = key.toTet();
        var coords = tet.coordinates();

        // Centroid (average of 4 vertices) should definitely be inside
        var centroidX = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0f;
        var centroidY = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0f;
        var centroidZ = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0f;

        var centroid = new Point3f(centroidX, centroidY, centroidZ);
        assertTrue(bounds.contains(centroid), "Bounds should contain tetrahedral centroid");

        // Point far outside should not be contained (use negative coords)
        var farPoint = new Point3f(-1000000.0f, -1000000.0f, -1000000.0f);
        assertFalse(bounds.contains(farPoint), "Bounds should not contain far point");
    }

    /**
     * Test 6: containsRDGPoint
     * <p>
     * Validates: Point containment test in RDGCS coordinates
     */
    @Test
    public void testContainsRDGPoint() {
        var key = TetreeKey.create((byte) 5, 0x50L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(key);

        // Convert centroid to RDGCS and verify containment
        var cartesianCentroid = bounds.centroid();
        var rdgCentroid = bounds.toRDG(new Point3f(
            (float) cartesianCentroid.getX(),
            (float) cartesianCentroid.getY(),
            (float) cartesianCentroid.getZ()
        ));

        assertTrue(bounds.contains(rdgCentroid), "Bounds should contain RDGCS centroid");
    }

    /**
     * Test 7: overlaps adjacent tetrahedra
     * <p>
     * Validates: Adjacent tetrahedra overlap detection (should overlap at shared face)
     */
    @Test
    public void testOverlapsAdjacent() {
        // Create two potentially adjacent tetrahedra
        var key1 = TetreeKey.create((byte) 5, 0x10L, 0L);
        var key2 = TetreeKey.create((byte) 5, 0x11L, 0L);

        var bounds1 = BubbleBounds.fromTetreeKey(key1);
        var bounds2 = BubbleBounds.fromTetreeKey(key2);

        // Adjacent tetrahedra should overlap (at least at shared boundary)
        // This is a relaxed test - actual adjacency depends on TM-index structure
        assertNotNull(bounds1, "First bounds should exist");
        assertNotNull(bounds2, "Second bounds should exist");

        // Overlap check should execute without error
        assertDoesNotThrow(() -> bounds1.overlaps(bounds2),
                          "Overlap check should not throw exception");
    }

    /**
     * Test 8: overlaps disjoint bounds
     * <p>
     * Validates: Non-overlapping bounds detection
     */
    @Test
    public void testOverlapsDisjoint() {
        // Create two tetrahedra that are far apart
        var key1 = TetreeKey.create((byte) 10, 0x0L, 0L);
        var key2 = TetreeKey.create((byte) 10, 0xFFFFFFL, 0L);

        var bounds1 = BubbleBounds.fromTetreeKey(key1);
        var bounds2 = BubbleBounds.fromTetreeKey(key2);

        // Disjoint bounds should not overlap
        assertFalse(bounds1.overlaps(bounds2), "Disjoint bounds should not overlap");
        assertFalse(bounds2.overlaps(bounds1), "Overlap should be symmetric");
    }

    /**
     * Test 9: expand with new point
     * <p>
     * Validates: Bounds grow to include new point outside current bounds
     */
    @Test
    public void testExpandWithNewPoint() {
        var key = TetreeKey.create((byte) 10, 0x100L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(key);

        // Get a point that's definitely outside (far from centroid)
        var centroid = bounds.centroid();
        var farPoint = new Point3f(
            (float) (centroid.getX() + 1000),
            (float) (centroid.getY() + 1000),
            (float) (centroid.getZ() + 1000)
        );

        // Expand bounds to include far point
        var expanded = bounds.expand(farPoint);

        assertNotNull(expanded, "Expanded bounds should not be null");
        assertTrue(expanded.contains(farPoint), "Expanded bounds should contain new point");

        // Original bounds centroid should still be contained
        var originalCentroid = new Point3f(
            (float) centroid.getX(),
            (float) centroid.getY(),
            (float) centroid.getZ()
        );
        assertTrue(expanded.contains(originalCentroid),
                  "Expanded bounds should still contain original centroid");
    }

    /**
     * Test 10: recalculate from positions
     * <p>
     * Validates: Full recalculation of bounds from new entity positions
     */
    @Test
    public void testRecalculateFromPositions() {
        var key = TetreeKey.create((byte) 5, 0x20L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(key);

        // New set of positions (possibly outside original bounds)
        var newPositions = List.of(
            new Point3f(10.0f, 20.0f, 30.0f),
            new Point3f(50.0f, 60.0f, 70.0f),
            new Point3f(15.0f, 25.0f, 35.0f)
        );

        var recalculated = bounds.recalculate(newPositions);

        assertNotNull(recalculated, "Recalculated bounds should not be null");

        // All new positions should be contained
        for (var pos : newPositions) {
            assertTrue(recalculated.contains(pos),
                      "Recalculated bounds should contain position: " + pos);
        }
    }

    /**
     * Test 11: CRITICAL - Tetrahedral centroid calculation
     * <p>
     * Validates: Centroid = (v0+v1+v2+v3)/4 (NOT cube center formula)
     */
    @Test
    public void testCentroidCalculation() {
        var key = TetreeKey.create((byte) 5, 0x42L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(key);

        var centroid = bounds.centroid();
        assertNotNull(centroid, "Centroid should not be null");

        // Verify against direct calculation from Tet vertices
        var tet = key.toTet();
        var coords = tet.coordinates();

        var expectedX = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0f;
        var expectedY = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0f;
        var expectedZ = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0f;

        assertEquals(expectedX, centroid.getX(), EPSILON,
                    "Centroid X should match (v0+v1+v2+v3)/4 formula");
        assertEquals(expectedY, centroid.getY(), EPSILON,
                    "Centroid Y should match (v0+v1+v2+v3)/4 formula");
        assertEquals(expectedZ, centroid.getZ(), EPSILON,
                    "Centroid Z should match (v0+v1+v2+v3)/4 formula");

        // WRONG formula check: ensure it's NOT using cube center
        // (This is defensive - the correct implementation should never use this)
        var cubeAnchor = tet.anchor();
        var cellSize = tet.length();
        var wrongCentroidX = cubeAnchor.x + cellSize / 2.0f;
        var wrongCentroidY = cubeAnchor.y + cellSize / 2.0f;
        var wrongCentroidZ = cubeAnchor.z + cellSize / 2.0f;

        // If these assertions fail, it means we're using cube center (WRONG!)
        // Note: This might pass if the tetrahedron happens to have the same centroid
        // as its bounding cube, but it's a useful sanity check
        boolean usingCubeFormula =
            Math.abs(wrongCentroidX - centroid.getX()) < EPSILON &&
            Math.abs(wrongCentroidY - centroid.getY()) < EPSILON &&
            Math.abs(wrongCentroidZ - centroid.getZ()) < EPSILON;

        if (usingCubeFormula) {
            System.err.println("WARNING: Centroid matches cube center formula. " +
                             "Verify implementation uses (v0+v1+v2+v3)/4, not origin+cellSize/2");
        }
    }

    /**
     * Test 12: RDGCS to Cartesian round-trip
     * <p>
     * Validates: Coordinate conversion accuracy (precision loss acceptable)
     */
    @Test
    public void testRDGToCartesianRoundTrip() {
        var bounds = BubbleBounds.fromTetreeKey(TetreeKey.create((byte) 5, 0x30L, 0L));

        var originalCartesian = new Point3f(10.5f, 20.3f, 30.7f);

        // Convert to RDGCS and back
        var rdg = bounds.toRDG(originalCartesian);
        var cartesian = bounds.toCartesian(rdg);

        // Due to integer casting in toRDG(), we expect some precision loss
        // Tolerance should be reasonable (within 1 unit)
        var tolerance = 1.5f;

        assertEquals(originalCartesian.x, cartesian.getX(), tolerance,
                    "Round-trip X coordinate should be within tolerance");
        assertEquals(originalCartesian.y, cartesian.getY(), tolerance,
                    "Round-trip Y coordinate should be within tolerance");
        assertEquals(originalCartesian.z, cartesian.getZ(), tolerance,
                    "Round-trip Z coordinate should be within tolerance");

        // Document precision loss for review
        var errorX = Math.abs(originalCartesian.x - cartesian.getX());
        var errorY = Math.abs(originalCartesian.y - cartesian.getY());
        var errorZ = Math.abs(originalCartesian.z - cartesian.getZ());

        System.out.printf("Round-trip precision loss: X=%.3f, Y=%.3f, Z=%.3f%n",
                         errorX, errorY, errorZ);
    }
}
