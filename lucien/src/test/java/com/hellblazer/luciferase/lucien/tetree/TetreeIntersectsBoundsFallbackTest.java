package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for Luciferase-ewjm9: both {@code Tetree.tetrahedronIntersectsBounds} overloads
 * formerly ended with a conservative {@code return true} AABB-vs-AABB fallback, admitting false
 * positives for a box overlapping only a non-covered corner of the tet's bounding box. The fallback
 * is now the exact {@code Tet.intersects12DOP} slab test.
 * <p>
 * Both target methods are {@code private}; they are driven here via reflection so the regression is
 * pinned at the method boundary independent of the (fragile-to-construct) full insert/query stack.
 * The geometric corner-only case mirrors {@link TetIntersectsVolumeBoundsTest}.
 */
public class TetreeIntersectsBoundsFallbackTest {

    private static Tetree<LongEntityID, String> newTetree() {
        return new Tetree<>(new SequentialLongIDGenerator());
    }

    private static boolean callEntityBounds(Tetree<?, ?> tetree, Tet tet, EntityBounds bounds) throws Exception {
        Method m = Tetree.class.getDeclaredMethod("tetrahedronIntersectsBounds", Tet.class, EntityBounds.class);
        m.setAccessible(true);
        return (boolean) m.invoke(tetree, tet, bounds);
    }

    private static boolean callVolumeBounds(Tetree<?, ?> tetree, TetreeKey<?> key, VolumeBounds bounds)
    throws Exception {
        Method m = Tetree.class.getDeclaredMethod("tetrahedronIntersectsBounds", TetreeKey.class, VolumeBounds.class);
        m.setAccessible(true);
        return (boolean) m.invoke(tetree, key, bounds);
    }

    // S0 tet at origin, level 10 (h = 2048). Body concentrated in high-x region; an AABB near (0,0,h)
    // overlaps the tet bounding box but not the tet body — the classic false-positive corner case.
    private static Tet cornerCaseTet() {
        return new Tet(0, 0, 0, (byte) 10, (byte) 0);
    }

    @Test
    void entityBounds_cornerOnlyOverlap_excluded() throws Exception {
        var tetree = newTetree();
        var tet = cornerCaseTet();
        int h = Constants.lengthAtLevel((byte) 10);
        float margin = 1.0f;
        var bounds = new EntityBounds(new Point3f(-margin, -margin, h - margin),
                                      new Point3f(margin, margin, h + margin));

        assertFalse(callEntityBounds(tetree, tet, bounds),
                    "AABB overlapping only a non-covered corner of the tet bounding box must not be reported as intersecting");
    }

    @Test
    void entityBounds_genuineOverlap_included() throws Exception {
        var tetree = newTetree();
        var tet = cornerCaseTet();
        int h = Constants.lengthAtLevel((byte) 10);
        // Tet centroid ~ (3h/4, h/4, h/2); a small box there is inside the tet.
        float cx = 3f * h / 4f, cy = h / 4f, cz = h / 2f, half = h / 20f;
        var bounds = new EntityBounds(new Point3f(cx - half, cy - half, cz - half),
                                      new Point3f(cx + half, cy + half, cz + half));

        assertTrue(callEntityBounds(tetree, tet, bounds),
                   "AABB centred on the tet centroid must be reported as intersecting");
    }

    @Test
    void volumeBounds_faceCrossingOverlap_included() throws Exception {
        // A wide, thin slab spanning the full x/y extent at z = h/2. No tet vertex (z in {0,h}) lies in
        // the slab, and the slab genuinely crosses the tet body — the edge/face-crossing case the old
        // membership probes could miss and the conservative fallback only caught by accident. Exact test
        // must report intersection.
        var tetree = newTetree();
        var tet = cornerCaseTet();
        var key = tet.tmIndex();
        int h = Constants.lengthAtLevel((byte) 10);
        var bounds = new VolumeBounds(0, 0, h / 2f - 1, h, h, h / 2f + 1);

        assertTrue(callVolumeBounds(tetree, key, bounds),
                   "A slab genuinely crossing the tet body must be reported as intersecting");
    }

    @Test
    void volumeBounds_cornerOnlyOverlap_excluded() throws Exception {
        var tetree = newTetree();
        var tet = cornerCaseTet();
        var key = tet.tmIndex();
        int h = Constants.lengthAtLevel((byte) 10);
        float margin = 1.0f;
        var bounds = new VolumeBounds(-margin, -margin, h - margin, margin, margin, h + margin);

        assertFalse(callVolumeBounds(tetree, key, bounds),
                    "Volume overlapping only a non-covered corner of the tet bounding box must not be reported as intersecting");
    }

    @Test
    void volumeBounds_genuineOverlap_included() throws Exception {
        var tetree = newTetree();
        var tet = cornerCaseTet();
        var key = tet.tmIndex();
        int h = Constants.lengthAtLevel((byte) 10);
        float cx = 3f * h / 4f, cy = h / 4f, cz = h / 2f, half = h / 20f;
        var bounds = new VolumeBounds(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);

        assertTrue(callVolumeBounds(tetree, key, bounds),
                   "Volume centred on the tet centroid must be reported as intersecting");
    }
}
