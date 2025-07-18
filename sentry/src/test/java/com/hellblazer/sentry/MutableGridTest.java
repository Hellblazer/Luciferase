/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Random;

/**
 * @author hal.hildebrand
 */
public class MutableGridTest {

    @Test
    public void smokin() throws Exception {
        var sentinel = new MutableGrid();
        var sites = new ArrayList<Vertex>();
        var entropy = new Random(0x666);
        var radius = 16000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        for (var p : Vertex.getRandomPoints(entropy, 256, radius, true)) {
            p.add(center);
            sites.add(sentinel.track(p, entropy));
        }
        int iterations = 10_000;
        long now = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            //            sites.sort(Vertex::compareTo);
            for (var site : sites) {
                site.moveBy(Vertex.randomPoint(entropy, -5f, 5f));
                if (site.x < 0.0f) {
                    site.x = 0.0f;
                }
                if (site.y < 0.0f) {
                    site.y = 0.0f;
                }
                if (site.z < 0.0f) {
                    site.z = 0.0f;
                }
            }
            sentinel.rebuild(entropy);
        }
        final var total = System.nanoTime() - now;
        System.out.printf("sites: %s total time: %s ms iterations: %s avg time: %s ms%n", sites.size(),
                          total / 1_000_000.0, iterations, (total / iterations) / 1_000_000.0);
    }
}
