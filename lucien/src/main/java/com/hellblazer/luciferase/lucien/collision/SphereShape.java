/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
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
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Sphere collision shape for narrow-phase collision detection.
 *
 * @author hal.hildebrand
 */
public final class SphereShape extends CollisionShape {

    private final float radius;

    public SphereShape(Point3f center, float radius) {
        super(center);
        if (radius <= 0) {
            throw new IllegalArgumentException("Radius must be positive");
        }
        this.radius = radius;
    }

    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return CollisionDetector.detectCollision(this, other);
    }

    @Override
    public EntityBounds getAABB() {
        return new EntityBounds(position, radius);
    }

    public float getRadius() {
        return radius;
    }

    @Override
    public Point3f getSupport(Vector3f direction) {
        var normalizedDir = new Vector3f(direction);
        normalizedDir.normalize();

        var support = new Point3f(normalizedDir);
        support.scale(radius);
        support.add(position);

        return support;
    }

    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        var oc = new Vector3f();
        oc.sub(ray.origin(), position);

        var a = ray.direction().dot(ray.direction());
        var b = 2.0f * oc.dot(ray.direction());
        var c = oc.dot(oc) - radius * radius;

        var discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            return RayIntersectionResult.noIntersection();
        }

        var sqrtDiscriminant = (float) Math.sqrt(discriminant);
        var t1 = (-b - sqrtDiscriminant) / (2 * a);
        var t2 = (-b + sqrtDiscriminant) / (2 * a);

        var t = -1f;
        if (t1 >= 0 && t1 <= ray.maxDistance()) {
            t = t1;
        } else if (t2 >= 0 && t2 <= ray.maxDistance()) {
            t = t2;
        }

        if (t < 0) {
            return RayIntersectionResult.noIntersection();
        }

        var intersectionPoint = ray.pointAt(t);
        var normal = new Vector3f();
        normal.sub(intersectionPoint, position);
        normal.normalize();

        return RayIntersectionResult.intersection(t, intersectionPoint, normal);
    }

    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
    }
}