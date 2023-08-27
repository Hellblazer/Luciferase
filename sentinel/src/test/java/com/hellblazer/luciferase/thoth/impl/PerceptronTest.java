/**
 * Copyright (C) 2008 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the Thoth Interest Management and Load Balancing
 * Framework.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.thoth.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Random;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.thoth.Perceiving;
import com.hellblazer.primeMover.controllers.SteppingController;
import com.hellblazer.primeMover.runtime.Kairos;

/**
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 * 
 */

public class PerceptronTest {

//    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testTracking() throws Throwable {
        SteppingController controller = new SteppingController();
        Kairos.setController(controller);
        controller.setCurrentTime(0);

        int x = 1000;
        int y = 1000;
        int thinkTime = 1000;
        int numNodes = 100;
        int aoi = 20;
        int maxStep = 1000;
        int flipStep = 75;
        int maxVelocity = 10;
        Random random = new Random(666);
        var lastDistance = new HashMap<Perceptron<?>, Tuple3f>();

        Perceptron<Perceiving>[] perceptrons = new Perceptron[numNodes];
        SimEntityImpl[] entities = new SimEntityImpl[numNodes];
        for (int i = 0; i < numNodes; i++) {
            entities[i] = new SimEntityImpl(random, thinkTime, flipStep, maxVelocity, x, y);
            perceptrons[i] = new Perceptron(entities[i], new Point3f(random.nextInt(x), random.nextInt(y), 0), aoi, 10);
            perceptrons[i].join(perceptrons[0]);
            lastDistance.put(perceptrons[i], perceptrons[i]);
        }

        controller.step();

        boolean frist = true;
        for (int step = 0; step < maxStep; step++) {
            for (SimEntity entity : entities) {
                entity.doSomething();
            }
            controller.step();
            for (Perceptron<Perceiving> perceptron : perceptrons) {
                for (var v : perceptron.getNeighbors()) {
                    Node node = (Node) v;
                    var distance = new Vector3f(perceptron.getLocation());
                    if (distance.length() < perceptron.getAoiRadius()) {
                        // Verify that all the neighbors that are within the
                        // perceptron's AOI are perceived in the right location
                        assertTrue(node.equals(v), step + ": Node [" + perceptron
                        + "] Model location does not match neighbor's reported location: [" + node + "]");
                    }
                    lastDistance.get(perceptron).sub(distance);
                    if (!frist) {
                        assertTrue(distance.lengthSquared() > 0);
                    }
                    lastDistance.put(perceptron, distance);
                }
            }
            frist = false;
        }
    }
}
