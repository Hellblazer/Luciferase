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
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import javax.vecmath.Point3i;

import org.junit.jupiter.api.Test;

/**
 * @author hal.hildebrand
 */
public class SentinelTest {

    public static Set<Vector3i> getRandomPoints(Random random, int numberOfPoints, int radius, boolean inSphere) {
        int radiusSquared = radius * radius;
        Set<Vector3i> ourPoints = new HashSet<>();
        Vector3i translated = new Vector3i(radius, radius, radius);
        for (int i = 0; i < numberOfPoints; i++) {
            Vector3i generated = null;
            do {
                if (inSphere) {
                    do {
                        generated = randomPoint(random, -radius, radius);
                    } while (generated.lengthSquared() >= radiusSquared);
                } else {
                    generated = randomPoint(random, -radius, radius);
                }
                generated.add(translated);
            } while (ourPoints.contains(generated));
            ourPoints.add(generated);
        }

        return ourPoints;
    }

    public static int random(Random random, int min, int max) {
        float result = random.nextFloat();
        if (min > max) {
            result *= min - max;
            result += max;
        } else {
            result *= max - min;
            result += min;
        }
        return (int) result;
    }

    public static Vector3i randomPoint(Random random, int min, int max) {
        return new Vector3i(random(random, min, max), random(random, min, max), random(random, min, max));
    }

    @Test
    public void smokin() throws Exception {
        var sentinel = new Sentinel();
        var sites = new ArrayList<Site>();
        var entropy = new Random(0x666);
        for (var p : getRandomPoints(entropy, 1000, 1000, true)) {
            sites.add(sentinel.track(p));
        }

        for (int i = 0; i < 100; i++) {
            for (var site : sites) {
                sentinel.moveBy(site, randomPoint(entropy, 10, 10));
            }
        }

        final var a = new Point3i(1400, 1400, 1400);
        final var b = new Point3i(500, 500, 100);
        b.add(a);
        assertEquals(5, sentinel.nn(sites.get(0), 26, a, b).size());
    }
}
