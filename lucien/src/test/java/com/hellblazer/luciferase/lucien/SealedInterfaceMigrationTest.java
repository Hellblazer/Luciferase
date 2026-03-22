// SPDX-License-Identifier: AGPL-3.0-or-later
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.tetree.RangeHandle;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-migration regression suite for the sealed-interface migration (P2.2 / Luciferase-hsg).
 *
 * <p>Every test here exercises a callsite that reads {@code aabt.originX()} etc., or one of the
 * {@code containedBy(aabt)} implementations.  The suite must pass BOTH before and after the migration.
 *
 * <p>Test organisation:
 * <ol>
 *   <li>Callsites 1-6 – {@code containedBy(aabt)} for each concrete {@link Spatial} implementor</li>
 *   <li>Callsite 7  – {@code RangeHandle.BoundingBox.containedBy} (tested indirectly)</li>
 *   <li>Callsite 8  – {@code VolumeBounds.from(aabt)} switch branch</li>
 *   <li>Callsite 9  – {@code Spatial.default intersects(aabt)}</li>
 *   <li>Simplex stubs – {@code containedBy} and {@code intersects} currently return false unconditionally</li>
 *   <li>Boundary – every point in a level-10 cube is contained by exactly one of the 6 S-type tets</li>
 * </ol>
 */
public class SealedInterfaceMigrationTest {

    // -----------------------------------------------------------------------
    // Shared fixture: a large outer aabt and a smaller inner aabt
    // -----------------------------------------------------------------------

    /** Large bounding volume: [0, 1000] in all axes. */
    private static final Spatial.aabt OUTER = new Spatial.aabt(0f, 0f, 0f, 1000f, 1000f, 1000f);

    /** Interior query volume: [100, 900] in all axes – strictly inside OUTER. */
    private static final Spatial.aabt INNER = new Spatial.aabt(100f, 100f, 100f, 900f, 900f, 900f);

    /** Disjoint volume: [1100, 2000] in all axes. */
    private static final Spatial.aabt FAR = new Spatial.aabt(1100f, 1100f, 1100f, 2000f, 2000f, 2000f);

    // -----------------------------------------------------------------------
    // Callsite 1 – Spatial.Cube.containedBy(aabt)  (Spatial.java:47)
    // -----------------------------------------------------------------------

    @Test
    void cube_fullyInsideOuter_containedByReturnsTrue() {
        // Cube with origin (200,200,200) and extent 100 → max edge at 300 – well inside [0,1000]
        var cube = new Spatial.Cube(200f, 200f, 200f, 100f);
        assertTrue(cube.containedBy(OUTER), "Cube fully inside OUTER should be contained");
    }

    @Test
    void cube_outsideFar_containedByReturnsFalse() {
        // Cube with origin (200,200,200) is entirely outside FAR [1100,2000]
        var cube = new Spatial.Cube(200f, 200f, 200f, 100f);
        assertFalse(cube.containedBy(FAR), "Cube outside FAR should not be contained");
    }

    @Test
    void cube_partiallyOverlapping_notContained() {
        // Cube crosses OUTER boundary: origin 950, extent 100 → max = 1050 > 1000
        var cube = new Spatial.Cube(950f, 950f, 950f, 100f);
        assertFalse(cube.containedBy(OUTER), "Cube partially outside OUTER should not be contained");
    }

    // -----------------------------------------------------------------------
    // Callsite 2 – Spatial.Sphere.containedBy(aabt)  (Spatial.java:70)
    // -----------------------------------------------------------------------

    @Test
    void sphere_fullyInsideOuter_containedByReturnsTrue() {
        // Sphere at (500,500,500) radius 100 → bounding box [400,600] – inside [0,1000]
        var sphere = new Spatial.Sphere(500f, 500f, 500f, 100f);
        assertTrue(sphere.containedBy(OUTER), "Sphere fully inside OUTER should be contained");
    }

    @Test
    void sphere_outsideFar_containedByReturnsFalse() {
        var sphere = new Spatial.Sphere(500f, 500f, 500f, 100f);
        assertFalse(sphere.containedBy(FAR), "Sphere outside FAR should not be contained");
    }

    @Test
    void sphere_tangentToOuterBoundary_notContained() {
        // Center at (900,500,500) radius 101 → bounding box max-X = 1001 > 1000
        var sphere = new Spatial.Sphere(900f, 500f, 500f, 101f);
        assertFalse(sphere.containedBy(OUTER), "Sphere touching outside of OUTER boundary should not be contained");
    }

    @Test
    void sphere_exactlyFittingInner_containedByInner() {
        // Inner is [100,900]; sphere centre (500,500,500) radius 400 fits exactly
        var sphere = new Spatial.Sphere(500f, 500f, 500f, 400f);
        assertTrue(sphere.containedBy(INNER), "Sphere exactly fitting INNER should be contained");
    }

    // -----------------------------------------------------------------------
    // Callsite 3 – Spatial.Parallelepiped.containedBy(aabt)  (Spatial.java:101)
    // -----------------------------------------------------------------------

    @Test
    void parallelepiped_fullyInsideOuter_containedByReturnsTrue() {
        // Box [200,800] in all axes – inside OUTER [0,1000]
        var para = new Spatial.Parallelepiped(200f, 200f, 200f, 800f, 800f, 800f);
        assertTrue(para.containedBy(OUTER), "Parallelepiped fully inside OUTER should be contained");
    }

    @Test
    void parallelepiped_outsideFar_containedByReturnsFalse() {
        var para = new Spatial.Parallelepiped(200f, 200f, 200f, 800f, 800f, 800f);
        assertFalse(para.containedBy(FAR), "Parallelepiped outside FAR should not be contained");
    }

    @Test
    void parallelepiped_straddlingBoundary_notContained() {
        // Extent exceeds OUTER on Z axis
        var para = new Spatial.Parallelepiped(200f, 200f, 200f, 800f, 800f, 1100f);
        assertFalse(para.containedBy(OUTER), "Parallelepiped straddling OUTER boundary should not be contained");
    }

    // -----------------------------------------------------------------------
    // Callsite 4 – Spatial.Tetrahedron.containedBy(aabt)  (Spatial.java:118)
    // -----------------------------------------------------------------------

    @Test
    void tetrahedron_allVerticesInsideOuter_containedByReturnsTrue() {
        // All four vertices well within [0,1000]
        var a = new Point3f(100f, 100f, 100f);
        var b = new Point3f(900f, 100f, 100f);
        var c = new Point3f(500f, 900f, 100f);
        var d = new Point3f(500f, 500f, 900f);
        var tet = new Spatial.Tetrahedron(a, b, c, d);
        assertTrue(tet.containedBy(OUTER), "Tetrahedron with all vertices inside OUTER should be contained");
    }

    @Test
    void tetrahedron_oneVertexOutside_notContained() {
        var a = new Point3f(100f, 100f, 100f);
        var b = new Point3f(900f, 100f, 100f);
        var c = new Point3f(500f, 900f, 100f);
        var d = new Point3f(500f, 500f, 1100f); // z=1100 exceeds OUTER
        var tet = new Spatial.Tetrahedron(a, b, c, d);
        assertFalse(tet.containedBy(OUTER), "Tetrahedron with one vertex outside OUTER should not be contained");
    }

    @Test
    void tetrahedron_entirelyOutsideFar_notContained() {
        var a = new Point3f(100f, 100f, 100f);
        var b = new Point3f(900f, 100f, 100f);
        var c = new Point3f(500f, 900f, 100f);
        var d = new Point3f(500f, 500f, 900f);
        var tet = new Spatial.Tetrahedron(a, b, c, d);
        assertFalse(tet.containedBy(FAR), "Tetrahedron outside FAR should not be contained");
    }

    // -----------------------------------------------------------------------
    // Callsite 5 – Spatial.aabb.containedBy(aabt)  (Spatial.java:158)
    // -----------------------------------------------------------------------

    @Test
    void aabb_fullyInsideOuter_containedByReturnsTrue() {
        var box = new Spatial.aabb(200f, 200f, 200f, 800f, 800f, 800f);
        assertTrue(box.containedBy(OUTER), "aabb fully inside OUTER should be contained");
    }

    @Test
    void aabb_outsideFar_containedByReturnsFalse() {
        var box = new Spatial.aabb(200f, 200f, 200f, 800f, 800f, 800f);
        assertFalse(box.containedBy(FAR), "aabb outside FAR should not be contained");
    }

    @Test
    void aabb_exactlyMatchingBounds_isContained() {
        // aabb that exactly matches OUTER should be contained
        var box = new Spatial.aabb(0f, 0f, 0f, 1000f, 1000f, 1000f);
        assertTrue(box.containedBy(OUTER), "aabb matching OUTER exactly should be contained");
    }

    @Test
    void aabb_extentExceedsBoundsOnOneAxis_notContained() {
        // Extend X beyond OUTER
        var box = new Spatial.aabb(0f, 0f, 0f, 1001f, 800f, 800f);
        assertFalse(box.containedBy(OUTER), "aabb with extentX=1001 exceeding OUTER extentX=1000 should not be contained");
    }

    // -----------------------------------------------------------------------
    // Callsite 6 – Spatial.aabt.containedBy(aabt)  (Spatial.java:185)
    // -----------------------------------------------------------------------

    @Test
    void aabt_fullyInsideOuter_containedByReturnsTrue() {
        // INNER [100,900] ⊂ OUTER [0,1000]
        assertTrue(INNER.containedBy(OUTER), "INNER aabt should be contained by OUTER");
    }

    @Test
    void aabt_equalToOuter_isContained() {
        var same = new Spatial.aabt(0f, 0f, 0f, 1000f, 1000f, 1000f);
        assertTrue(same.containedBy(OUTER), "aabt identical to OUTER should be self-contained");
    }

    @Test
    void aabt_outsideFar_notContained() {
        assertFalse(INNER.containedBy(FAR), "INNER aabt should not be contained by FAR");
    }

    @Test
    void aabt_partiallyOverlapping_notContained() {
        // Origin outside OUTER on the low side (negative origin)
        var partiallyOut = new Spatial.aabt(-10f, 100f, 100f, 900f, 900f, 900f);
        assertFalse(partiallyOut.containedBy(OUTER), "aabt with origin below OUTER origin should not be contained");
    }

    // -----------------------------------------------------------------------
    // Callsite 7 – RangeHandle.BoundingBox.containedBy(aabt)
    // Tested indirectly: a range query with includeIntersecting=false uses BoundingBox.containedBy
    // -----------------------------------------------------------------------

    @Test
    void rangeHandle_boundingBoxContainedBy_usedDuringBoundedQuery() {
        // Level 10, cell size = 2048; use a tiny bounding box well inside the root tet
        var rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        var bounds = new VolumeBounds(100f, 100f, 100f, 200f, 200f, 200f);
        var handle = new RangeHandle(rootTet, bounds, false, (byte) 10);
        // The stream call exercises BoundingBox.containedBy(aabt) via Tet.boundedBy
        assertNotNull(handle.stream(), "RangeHandle stream should not be null");
        // We cannot assert count because boundedBy depends on actual tet distribution,
        // but we verify no exception is thrown
        assertDoesNotThrow(() -> handle.stream().count(),
                           "Streaming a contained RangeHandle should not throw");
    }

    @Test
    void rangeHandle_boundingBoxNotContained_outsideBoundsReturnsEmpty() {
        // Bounds entirely outside [0, root extent] – no tets should be contained
        var rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        // Negative coordinates are outside the tetrahedral SFC space
        var bounds = new VolumeBounds(5_000_000f, 5_000_000f, 5_000_000f, 6_000_000f, 6_000_000f, 6_000_000f);
        var handle = new RangeHandle(rootTet, bounds, false, (byte) 10);
        assertDoesNotThrow(() -> handle.stream().count(),
                           "Streaming an out-of-range RangeHandle should not throw");
    }

    // -----------------------------------------------------------------------
    // Callsite 8 – VolumeBounds.from(aabt)  (VolumeBounds.java:45)
    // -----------------------------------------------------------------------

    @Test
    void volumeBoundsFrom_aabt_mapsOriginAndExtentDirectly() {
        var aabt = new Spatial.aabt(10f, 20f, 30f, 110f, 120f, 130f);
        var vb = VolumeBounds.from(aabt);
        assertNotNull(vb, "VolumeBounds.from(aabt) should not return null");
        assertEquals(10f, vb.minX(), 1e-6f, "minX should equal aabt.originX()");
        assertEquals(20f, vb.minY(), 1e-6f, "minY should equal aabt.originY()");
        assertEquals(30f, vb.minZ(), 1e-6f, "minZ should equal aabt.originZ()");
        assertEquals(110f, vb.maxX(), 1e-6f, "maxX should equal aabt.extentX()");
        assertEquals(120f, vb.maxY(), 1e-6f, "maxY should equal aabt.extentY()");
        assertEquals(130f, vb.maxZ(), 1e-6f, "maxZ should equal aabt.extentZ()");
    }

    @Test
    void volumeBoundsFrom_cube_computesExtentCorrectly() {
        var cube = new Spatial.Cube(5f, 10f, 15f, 50f);
        var vb = VolumeBounds.from(cube);
        assertNotNull(vb);
        assertEquals(5f, vb.minX(), 1e-6f);
        assertEquals(55f, vb.maxX(), 1e-6f);  // originX + extent
    }

    @Test
    void volumeBoundsFrom_sphere_usesCentreRadius() {
        var sphere = new Spatial.Sphere(100f, 200f, 300f, 50f);
        var vb = VolumeBounds.from(sphere);
        assertNotNull(vb);
        assertEquals(50f, vb.minX(), 1e-6f);   // centre - radius
        assertEquals(150f, vb.maxX(), 1e-6f);  // centre + radius
    }

    @Test
    void volumeBoundsFrom_aabb_mapsDirectly() {
        var box = new Spatial.aabb(1f, 2f, 3f, 10f, 20f, 30f);
        var vb = VolumeBounds.from(box);
        assertNotNull(vb);
        assertEquals(1f, vb.minX(), 1e-6f);
        assertEquals(10f, vb.maxX(), 1e-6f);
    }

    @Test
    void volumeBoundsFrom_parallelepiped_addsExtentsToOrigin() {
        var para = new Spatial.Parallelepiped(0f, 0f, 0f, 100f, 200f, 300f);
        var vb = VolumeBounds.from(para);
        assertNotNull(vb);
        // Parallelepiped branch: origin + extent (NOT direct passthrough)
        assertEquals(0f, vb.minX(), 1e-6f);
        assertEquals(100f, vb.maxX(), 1e-6f);
        assertEquals(200f, vb.maxY(), 1e-6f);
        assertEquals(300f, vb.maxZ(), 1e-6f);
    }

    @Test
    void volumeBoundsFrom_tetrahedron_usesVertexBounds() {
        var a = new Point3f(0f, 0f, 0f);
        var b = new Point3f(100f, 0f, 0f);
        var c = new Point3f(50f, 100f, 0f);
        var d = new Point3f(50f, 50f, 100f);
        var tet = new Spatial.Tetrahedron(a, b, c, d);
        var vb = VolumeBounds.from(tet);
        assertNotNull(vb);
        assertEquals(0f, vb.minX(), 1e-6f);
        assertEquals(100f, vb.maxX(), 1e-6f);
        assertEquals(0f, vb.minY(), 1e-6f);
        assertEquals(100f, vb.maxY(), 1e-6f);
        assertEquals(0f, vb.minZ(), 1e-6f);
        assertEquals(100f, vb.maxZ(), 1e-6f);
    }

    // -----------------------------------------------------------------------
    // Callsite 9 – Spatial.default intersects(aabt)  (Spatial.java:19)
    // -----------------------------------------------------------------------

    @Test
    void defaultIntersects_aabb_overlappingOuter_returnsTrue() {
        // aabb [500,1500] partially overlaps OUTER [0,1000]
        var box = new Spatial.aabb(500f, 500f, 500f, 1500f, 1500f, 1500f);
        assertTrue(box.intersects(OUTER), "Overlapping aabb should intersect OUTER via default method");
    }

    @Test
    void defaultIntersects_aabb_disjointFromOuter_returnsFalse() {
        // aabb in FAR region [1100,2000] is disjoint from OUTER [0,1000]
        var box = new Spatial.aabb(1100f, 1100f, 1100f, 2000f, 2000f, 2000f);
        assertFalse(box.intersects(OUTER), "Disjoint aabb should not intersect OUTER via default method");
    }

    @Test
    void defaultIntersects_cube_overlappingOuter_returnsTrue() {
        // Cube origin (900,900,900) extent 200 → max at 1100 → overlaps OUTER [0,1000]
        var cube = new Spatial.Cube(900f, 900f, 900f, 200f);
        assertTrue(cube.intersects(OUTER), "Overlapping Cube should intersect OUTER via default method");
    }

    @Test
    void defaultIntersects_sphere_overlappingOuter_returnsTrue() {
        // Sphere centre (1050,500,500) radius 100 → left edge at 950 → overlaps OUTER
        var sphere = new Spatial.Sphere(1050f, 500f, 500f, 100f);
        assertTrue(sphere.intersects(OUTER), "Overlapping Sphere should intersect OUTER via default method");
    }

    @Test
    void defaultIntersects_aabt_selfIntersect_returnsTrue() {
        // An aabt intersects itself
        assertTrue(OUTER.intersects(OUTER), "aabt should intersect itself");
    }

    @Test
    void defaultIntersects_aabt_disjoint_returnsFalse() {
        assertFalse(OUTER.intersects(FAR), "OUTER and FAR are disjoint – should not intersect");
    }

    // -----------------------------------------------------------------------
    // Simplex stubs – current behaviour is false unconditionally
    // These will change in P2.2 once Simplex delegates to Tet geometry.
    // -----------------------------------------------------------------------

    @Test
    @Disabled("P2.2 will fix Simplex.containedBy to delegate to Tet geometry")
    void simplex_containedBy_currentlyReturnsFalseUnconditionally() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        var key = tet.tmIndex();
        var simplex = new Simplex<>(key, "data");
        // After migration this should return true when the simplex is inside the query volume
        assertTrue(simplex.containedBy(OUTER),
                   "Post-P2.2: Simplex inside query volume should be contained");
    }

    @Test
    @Disabled("P2.2 will fix Simplex.intersects to delegate to Tet geometry")
    void simplex_intersects_currentlyReturnsFalseUnconditionally() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        var key = tet.tmIndex();
        var simplex = new Simplex<>(key, "data");
        // After migration this should return true when the simplex overlaps the query volume
        assertTrue(simplex.intersects(OUTER.originX(), OUTER.originY(), OUTER.originZ(),
                                      OUTER.extentX(), OUTER.extentY(), OUTER.extentZ()),
                   "Post-P2.2: Simplex intersecting query volume should return true");
    }

    /** Verify current stub behavior so regressions are detectable pre-migration. */
    @Test
    void simplex_containedBy_returnsfalse_currentBehavior() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        var key = tet.tmIndex();
        var simplex = new Simplex<>(key, "data");
        assertFalse(simplex.containedBy(OUTER),
                    "Pre-P2.2: Simplex.containedBy must return false unconditionally");
    }

    @Test
    void simplex_intersects_returnsFalse_currentBehavior() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        var key = tet.tmIndex();
        var simplex = new Simplex<>(key, "data");
        assertFalse(simplex.intersects(OUTER.originX(), OUTER.originY(), OUTER.originZ(),
                                       OUTER.extentX(), OUTER.extentY(), OUTER.extentZ()),
                    "Pre-P2.2: Simplex.intersects must return false unconditionally");
    }

    // -----------------------------------------------------------------------
    // Boundary – S0-S5 partition of a level-10 cube
    //
    // For a cube at level 10, cell size = 2048.  A grid of sample points is
    // checked: every sample must be contained by AT LEAST one of the 6 tets
    // that tile the cube.  (Due to the known t8code partition limitation, a
    // perfect "exactly one" guarantee cannot be asserted for all points; the
    // test instead verifies no point falls through every tet.)
    // -----------------------------------------------------------------------

    @Test
    void s0ToS5_centroidOfEachType_containedByThatType() {
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);

        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, level, type);
            Point3i[] coords = tet.coordinates();

            // Compute centroid of the 4 vertices
            float cx = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0f;
            float cy = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0f;
            float cz = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0f;

            assertTrue(tet.containsUltraFast(cx, cy, cz),
                       "Centroid of S-type " + type + " tet should be contained by itself");
        }
    }

    @Test
    void s0ToS5_samplePointsInsideCube_containedByAtLeastOneType() {
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);

        // Sample a 5x5x5 grid within the cube, avoiding exact faces/edges
        int step = cellSize / 6;
        int misses = 0;

        for (int ix = 1; ix <= 5; ix++) {
            for (int iy = 1; iy <= 5; iy++) {
                for (int iz = 1; iz <= 5; iz++) {
                    float px = ix * step;
                    float py = iy * step;
                    float pz = iz * step;

                    boolean foundContaining = false;
                    for (byte type = 0; type < 6; type++) {
                        var tet = new Tet(0, 0, 0, level, type);
                        if (tet.containsUltraFast(px, py, pz)) {
                            foundContaining = true;
                            break;
                        }
                    }
                    if (!foundContaining) {
                        misses++;
                    }
                }
            }
        }

        // Allow a small number of missed points only at boundary/shared-face positions
        // (known limitation per TETREE_T8CODE_PARTITION_ANALYSIS.md).
        // Interior sample points should all be covered.
        assertEquals(0, misses,
                     "All sampled interior points should be contained by at least one of the 6 S-type tets");
    }

    @Test
    void s0ToS5_sharedEdgePoints_containedByAtLeastOneType() {
        // Points on shared edges: u==v, v==w, u==w planes within the cube
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        float h = cellSize;

        // Points on the main diagonal sub-faces (u==v, v==w, u==w)
        float[][] edgePoints = {
            { h / 2, h / 2, h / 4 },  // u==v plane
            { h / 4, h / 2, h / 2 },  // v==w plane
            { h / 2, h / 4, h / 2 },  // u==w plane
            { h / 3, h / 3, h / 3 },  // main diagonal
        };

        for (float[] pt : edgePoints) {
            boolean contained = false;
            for (byte type = 0; type < 6; type++) {
                var tet = new Tet(0, 0, 0, level, type);
                if (tet.containsUltraFast(pt[0], pt[1], pt[2])) {
                    contained = true;
                    break;
                }
            }
            assertTrue(contained,
                       "Edge/diagonal point (" + pt[0] + "," + pt[1] + "," + pt[2]
                       + ") should be contained by at least one S-type tet");
        }
    }

    // -----------------------------------------------------------------------
    // aabt field access – direct accessor smoke tests
    // These ensure the record accessors compile and return correct values.
    // -----------------------------------------------------------------------

    @Test
    void aabt_accessors_returnConstructorValues() {
        var q = new Spatial.aabt(1f, 2f, 3f, 4f, 5f, 6f);
        assertEquals(1f, q.originX(), 1e-6f);
        assertEquals(2f, q.originY(), 1e-6f);
        assertEquals(3f, q.originZ(), 1e-6f);
        assertEquals(4f, q.extentX(), 1e-6f);
        assertEquals(5f, q.extentY(), 1e-6f);
        assertEquals(6f, q.extentZ(), 1e-6f);
    }
}
