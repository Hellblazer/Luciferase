package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static com.hellblazer.luciferase.lucien.Constants.MAX_REFINEMENT_LEVEL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 **/
public class TetTest {
    @Test
    public void contains() {
        var tet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        // Should contain all four corners
        assertTrue(tet.contains(new Point3f(0, 0, 0)));
        assertTrue(tet.contains(new Point3f(1, 0, 0)));
        assertTrue(tet.contains(new Point3f(1, 0, 1)));
        assertTrue(tet.contains(new Point3f(1, 1, 1)));
    }

    @Test
    public void faceNeighbor() {
        var level = 10;
        var h = 1 << (MAX_REFINEMENT_LEVEL - level);
        var tet = new Tet(3 * h, 0, 2 * h, (byte) level, (byte) 0);
        var n0 = tet.faceNeighbor(0);
        assertEquals((byte) 4, n0.tet().type());
        assertNotNull(n0);
        var n1 = tet.faceNeighbor(1);
        assertNotNull(n1);
        assertEquals((byte) 5, n1.tet().type());
        var n2 = tet.faceNeighbor(2);
        assertNotNull(n2);
        assertEquals((byte) 1, n2.tet().type());
        var n3 = tet.faceNeighbor(3);
        assertNotNull(n3);
        assertEquals((byte) 2, n3.tet().type());
    }

    @Test
    public void orientation() {
        // Test orientation
        for (var vertices : Constants.SIMPLEX_STANDARD) {
            // A wrt CDB
            assertEquals(-1d, Tet.orientation(vertices[0], vertices[2], vertices[3], vertices[1]));
            // B wrt DCA
            assertEquals(-1d, Tet.orientation(vertices[1], vertices[3], vertices[2], vertices[0]));
            // C wrt BDA
            assertEquals(-1d, Tet.orientation(vertices[2], vertices[1], vertices[3], vertices[0]));
            // D wrt BAC
            assertEquals(-1d, Tet.orientation(vertices[3], vertices[1], vertices[0], vertices[2]));
        }
    }
}
