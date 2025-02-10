package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.luciferase.lucien.TetConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author hal.hildebrand
 **/
public class TetTest {
    @Test
    public void smokin() {
        var indicies = new ArrayList<Long>();
        // Start with the base tetrahedra, using Simplex 0
        var simplex = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (int i = 0; i < 10; i++) {
            var index = simplex.index();
            indicies.add(index);
            var fromIndex = Tet.tetrahedron(index, TetConstants.MAX_REFINEMENT_LEVEL);
            assertEquals(index, fromIndex.index());
            System.out.println("consecutive index: %s".formatted(simplex.index()));
            System.out.println("edge length: %s".formatted(simplex.length()));
            System.out.println("coordinates: %s".formatted(Arrays.asList(simplex.coordinates())));
            indicies.sort(Long::compareTo);
            System.out.println(indicies);
        }
    }
}
