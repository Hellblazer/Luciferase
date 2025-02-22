package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 **/
public class TetTest {
    @Test
    public void smokin() {
        var s0 = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertTrue(s0.contains(new Point3i(1, 1, 1)));
    }
}
