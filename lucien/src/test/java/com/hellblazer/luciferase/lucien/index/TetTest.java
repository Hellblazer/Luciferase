package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
