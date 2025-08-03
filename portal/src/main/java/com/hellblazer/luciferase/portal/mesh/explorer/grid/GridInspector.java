/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi GUI
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.portal.mesh.explorer.grid;

import com.hellblazer.luciferase.portal.mesh.explorer.Abstract3DApp;
import com.hellblazer.sentry.MutableGrid;
import javafx.scene.Group;

import javax.vecmath.Point3f;
import java.util.Random;

import static com.hellblazer.sentry.Vertex.randomPoint;

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
