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
package com.hellblazer.luciferase.lucien.grid;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3d;
import java.util.ArrayList;
import java.util.Random;

/**
 * @author hal.hildebrand
 */
public class MutableGridTest {
    private static final Point3d ORIGIN = new Point3d();

    public static Point3d[] getRandomPoints(Random random, int numberOfPoints, double radius, boolean inSphere) {
        double radiusSquared = radius * radius;
        Point3d ourPoints[] = new Point3d[numberOfPoints];
        for (int i = 0; i < ourPoints.length; i++) {
            if (inSphere) {
                do {
                    ourPoints[i] = randomPoint(random, -radius, radius);
                } while (ourPoints[i].distanceSquared(ORIGIN) >= radiusSquared);
            } else {
                ourPoints[i] = randomPoint(random, -radius, radius);
            }
        }

        return ourPoints;
    }

    public static double random(Random random, double min, double max) {
        double result = random.nextDouble();
        if (min > max) {
            result *= min - max;
            result += max;
        } else {
            result *= max - min;
            result += min;
        }
        return result;
    }

    public static Point3d randomPoint(Random random, double min, double max) {
        return new Point3d(random(random, min, max), random(random, min, max), random(random, min, max));
    }

    @Test
    public void smokin() throws Exception {
        var sentinel = new MutableGrid();
        var sites = new ArrayList<Vertex>();
        var entropy = new Random(0x666);
        for (var p : getRandomPoints(entropy, 1024, 10, true)) {
            sites.add(sentinel.track(p, entropy));
        }
        int iterations = 1_000;
        long now = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (var site : sites) {
                site.moveBy(randomPoint(entropy, -1d, 1d));
            }
            sentinel.rebuild(entropy);
        }
        final var total = System.nanoTime() - now;
        System.out.println(
        "sites: %s total time: %s ms iterations: %s avg time: %s ms".formatted(sites.size(), total / 1_000_000.0,
                                                                               iterations,
                                                                               (total / iterations) / 1_000_000.0));
    }
}
