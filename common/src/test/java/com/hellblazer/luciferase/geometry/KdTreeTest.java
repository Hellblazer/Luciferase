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
package com.hellblazer.luciferase.geometry;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * @author hal.hildebrand
 */
public class KdTreeTest {
    public static void main(String[] args) {
        testRandom(1000);
        System.out.println();
        testRandom(100000);
    }

    private static KdTree.Node randomPoint(Random random) {
        float x = random.nextFloat();
        float y = random.nextFloat();
        float z = random.nextFloat();
        return new KdTree.Node(new Point3f(x, y, z));
    }

    private static void testRandom(int points) {
        Random random = new Random();
        List<KdTree.Node> nodes = new ArrayList<>();
        for (int i = 0; i < points; ++i)
            nodes.add(randomPoint(random));
        long now = System.currentTimeMillis();
        KdTree tree = new KdTree(3, nodes);
        System.out.println("T: " + (System.currentTimeMillis() - now));
        KdTree.Node target = randomPoint(random);
        KdTree.Node nearest = tree.findNearest(target);
        System.out.println("Random data (" + points + " points):");
        System.out.println("target: " + target);
        System.out.println("nearest point: " + nearest);
        System.out.println("distance: " + tree.distance());
        System.out.println("nodes visited: " + tree.visited());
    }
}
