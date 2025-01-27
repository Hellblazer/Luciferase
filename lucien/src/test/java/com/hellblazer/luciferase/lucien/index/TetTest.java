package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tet;
import org.junit.jupiter.api.Test;

import static com.hellblazer.luciferase.lucien.TetConstants.MAX_REFINEMENT_LEVEL;

/**
 * @author hal.hildebrand
 **/
public class TetTest {
    @Test
    public void smokin() {
        for (int i = 0; i < 20; i++) {
            var midpointSimples = Tet.tetrahedron(i, (byte) 10);
            System.out.println("\nSimplex from index: " + midpointSimples);
            System.out.println("consecutive index: %s".formatted(midpointSimples.index()));
            System.out.println("edge length: %s".formatted(midpointSimples.lengthAtLevel()));
        }
    }
}
