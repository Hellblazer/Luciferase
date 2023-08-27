/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sentinel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Random;

import javax.vecmath.Point3f;

import org.junit.jupiter.api.Test;

/**
 * @author hal.hildebrand
 */
public class SentinelTest {
    private static final Point3f ORIGIN = new Point3f();

    public static Point3f[] getRandomPoints(Random random, int numberOfPoints, float radius, boolean inSphere) {
        float radiusSquared = radius * radius;
        Point3f ourPoints[] = new Point3f[numberOfPoints];
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

    public static float random(Random random, float min, float max) {
        float result = random.nextFloat();
        if (min > max) {
            result *= min - max;
            result += max;
        } else {
            result *= max - min;
            result += min;
        }
        return result;
    }

    public static Point3f randomPoint(Random random, float min, float max) {
        return new Point3f(random(random, min, max), random(random, min, max), random(random, min, max));
    }

    @Test
    public void smokin() throws Exception {
        var sentinel = new Sentinel();
        var sites = new ArrayList<Vertex>();
        var entropy = new Random(0x666);
        for (var p : getRandomPoints(entropy, 512, 1000, true)) {
            sites.add(sentinel.track(p));
        }
        int iterations = 1000;
        long now = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            for (var site : sites) {
                site.moveBy(randomPoint(entropy, -10f, 10f));
            }
            sentinel.rebuild();
//            if (i != 0 && i % 1000 == 0) {
//                final var total = System.currentTimeMillis() - now;
//                System.out.println("sites: %s total time: %s ms iterations: %s avg time: %s ms".formatted(sites.size(),
//                                                                                                          total, i,
//                                                                                                          total / i));
//            }
        }
        final var total = System.currentTimeMillis() - now;
        System.out.println("sites: %s total time: %s ms iterations: %s avg time: %s ms".formatted(sites.size(), total,
                                                                                                  iterations,
                                                                                                  total / iterations));

        assertEquals(18, sites.get(50).getNeighbors().size());
    }
}
