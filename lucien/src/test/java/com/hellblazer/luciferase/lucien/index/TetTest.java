package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.luciferase.lucien.TetConstants;
import org.junit.jupiter.api.Test;

import static com.hellblazer.luciferase.lucien.TetConstants.MAX_COORD;
import static com.hellblazer.luciferase.lucien.TetConstants.MAX_REFINEMENT_LEVEL;

/**
 * @author hal.hildebrand
 **/
public class TetTest {
    @Test
    public void smokin() {
        byte z = 0;
        var rootSimplex = TetConstants.ROOT_SIMPLEX;
        System.out.println("\nRoot simplex: " + rootSimplex);
        System.out.println("morton index: %s".formatted(rootSimplex.childTM((byte) 5)));
        System.out.println("consecutive index: %s".formatted(rootSimplex.index()));
        System.out.println("edge length: %s".formatted(rootSimplex.lengthAtLevel()));

        var unitSimplex = TetConstants.UNIT_SIMPLEX;
        System.out.println("\nUnit simplex: " + unitSimplex);
        System.out.println("consecutive index: %s".formatted(unitSimplex.index()));
        System.out.println("edge length: %s".formatted(unitSimplex.lengthAtLevel()));

        var finalSimplex = TetConstants.FINAL_SIMPLEX;
        System.out.println("\nFinal simplex: " + finalSimplex);
        System.out.println("consecutive index: %s".formatted(finalSimplex.index()));
        System.out.println("edge length: %s".formatted(finalSimplex.lengthAtLevel()));

        var unitSimplexIndex = 9223372036854775800L;
        var fromIndex = Tet.tetrahedron(unitSimplexIndex, (byte) (MAX_REFINEMENT_LEVEL));
        System.out.println("\nUnit simplex from index: " + fromIndex);
        System.out.println("consecutive index: %s".formatted(fromIndex.index()));
        System.out.println("edge length: %s".formatted(fromIndex.lengthAtLevel()));

        var type6 = new Tet(0, 0, 0, MAX_REFINEMENT_LEVEL, (byte) 5);
        System.out.println("\ntype 6 simplex: " + type6);
        System.out.println("consecutive index: %s".formatted(type6.index()));
        System.out.println("edge length: %s".formatted(type6.lengthAtLevel()));

        var midpoint = new Tet(MAX_COORD / 2, MAX_COORD / 2, MAX_COORD / 2, MAX_REFINEMENT_LEVEL, (byte) 5);
        System.out.println("\nMidpoint simplex: " + midpoint);
        System.out.println("consecutive index: %s".formatted(midpoint.index()));
        System.out.println("edge length: %s".formatted(midpoint.lengthAtLevel()));

        var midpointSimplexIndex = 9223372036854775800L / 2;
        var midpointSimples = Tet.tetrahedron(midpointSimplexIndex, (byte) (MAX_REFINEMENT_LEVEL / 2));
        System.out.println("\nMidpoint simplex from index: " + midpointSimples);
        System.out.println("consecutive index: %s".formatted(midpointSimples.index()));
        System.out.println("edge length: %s".formatted(midpointSimples.lengthAtLevel()));
    }
}
