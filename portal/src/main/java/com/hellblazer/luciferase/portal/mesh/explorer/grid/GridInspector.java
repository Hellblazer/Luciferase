/*
 * Copyright (c) 2013, 2014 Oracle and/or its affiliates.
 * All rights reserved. Use is subject to license terms.
 *
 * This file is available and licensed under the following license:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  - Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the distribution.
 *  - Neither the name of Oracle nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.hellblazer.luciferase.portal.mesh.explorer.grid;

import com.hellblazer.luciferase.lucien.grid.MutableGrid;
import com.hellblazer.luciferase.portal.mesh.explorer.Abstract3DApp;
import javafx.scene.Group;

import javax.vecmath.Point3f;
import java.util.Random;

import static com.hellblazer.luciferase.lucien.grid.Vertex.randomPoint;

/**
 * Neolithic 3D viewer, based on ye venerable JavaFX 3D sample app
 *
 * @author hal.hildebrand
 * @author cmcastil
 */
public class GridInspector extends Abstract3DApp {
    private static final Point3f ORIGIN = new Point3f(0, 0, 0);

    private GridView view;

    /**
     * Create some random points in a sphere
     */
    public static Point3f[] getRandomPoints(Random random, int numberOfPoints, float radius, boolean inSphere) {
        double radiusSquared = radius * radius;
        Point3f[] ourPoints = new Point3f[numberOfPoints];
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

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    protected Group build() {
        final var random = new Random(666);
        final var tet = new MutableGrid();
        Point3f[] ourPoints = getRandomPoints(random, 200, 10.0f, true);
        for (var v : ourPoints) {
            tet.track(v, random);
        }
        view = new GridView(tet);
        view.update(false, false, true, false, true);
        return view;
    }

    @Override
    protected String title() {
        return "Grid Inspector";
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            GridInspector.main(argv);
        }
    }
}
