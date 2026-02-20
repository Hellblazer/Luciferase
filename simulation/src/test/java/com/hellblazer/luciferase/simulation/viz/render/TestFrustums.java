/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Pre-built Frustum3D instances for tests. All coordinates in internal space (0..2^21-1).
 *
 * <p>Uses {@link Frustum3D#createPerspective} which takes camera position, a lookAt point,
 * an up vector, field-of-view in radians, aspect ratio, near and far distances.
 *
 * @author hal.hildebrand
 */
public final class TestFrustums {

    private static final int MAX = 1 << 21;

    private TestFrustums() {}

    /**
     * A frustum that encompasses the entire world.
     * Camera positioned far above centre, looking toward the world origin along -Z.
     */
    public static Frustum3D fullScene() {
        var cameraPos = new Point3f(MAX / 2f, MAX / 2f, (float) (MAX * 3));
        // lookAt: same X/Y as camera, but at Z=0 (look straight down -Z)
        var lookAt = new Point3f(MAX / 2f, MAX / 2f, 0f);
        var up = new Vector3f(0f, 1f, 0f);
        // 90 degrees FOV (in radians), 1:1 aspect, near=1, far covers entire world depth
        float fovyRadians = (float) Math.toRadians(90.0);
        return Frustum3D.createPerspective(cameraPos, lookAt, up, fovyRadians, 1.0f, 1f, (float) (MAX * 4));
    }

    /**
     * A tight frustum around the origin corner.
     * Camera positioned above the origin, looking toward it.
     */
    public static Frustum3D origin() {
        var cameraPos = new Point3f(0f, 0f, 5000f);
        var lookAt = new Point3f(0f, 0f, 0f);
        var up = new Vector3f(0f, 1f, 0f);
        float fovyRadians = (float) Math.toRadians(60.0);
        return Frustum3D.createPerspective(cameraPos, lookAt, up, fovyRadians, 1.0f, 1f, 10000f);
    }
}
