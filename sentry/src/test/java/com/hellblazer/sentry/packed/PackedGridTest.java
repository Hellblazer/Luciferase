package com.hellblazer.sentry.packed;

import com.hellblazer.sentry.TestCases;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 **/
public class PackedGridTest {

    @Test
    public void smokin() {
        var grid = new PackedGrid();
    }

    //    @Test
    public void testCubic() {
        var random = new Random(0);
        var T = new PackedGrid();
        for (var v : TestCases.getCubicCrystalStructure()) {
            assertTrue(T.contains(v));
            T.track(v, random);
        }

        var L = T.tetrahedrons();
        Assertions.assertEquals(188, L.size());
    }
}
