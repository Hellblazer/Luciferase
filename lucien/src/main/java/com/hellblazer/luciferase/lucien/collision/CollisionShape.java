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
 * Abstract base class for collision shapes used in narrow-phase collision detection.
 *
 * @author hal.hildebrand
 */
public abstract sealed class CollisionShape 
    permits SphereShape, BoxShape, OrientedBoxShape, CapsuleShape, MeshShape, ConvexHullShape, HeightmapShape {

    protected final Point3f position;

    protected CollisionShape(Point3f position) {
        this.position = new Point3f(position);
    }

    /**
     * Test collision with another shape using pattern matching
     */
    public abstract CollisionResult collidesWith(CollisionShape other);

    /**
     * Get the axis-aligned bounding box for this shape
     */
    public abstract EntityBounds getAABB();

    /**
     * Get the position of this shape
     */
    public Point3f getPosition() {
        return new Point3f(position);
    }

    /**
     * Get the support point in a given direction (for GJK algorithm)
     */
    public abstract Point3f getSupport(Vector3f direction);

    /**
     * Test ray intersection with this shape
     */
    public abstract RayIntersectionResult intersectRay(Ray3D ray);

    /**
     * Translate this shape by the given delta
     *
     * @param delta the translation vector
     */
    public abstract void translate(Vector3f delta);

    /**
     * Return an independent deep copy of this shape (Luciferase-v2na8). The copy shares no mutable state with this
     * instance, so {@link #translate} (and other mutators) on the copy must not be observable through the original.
     * Used by continuous collision detection to test shapes at interpolated positions without corrupting the live
     * geometry owned by the simulation.
     *
     * @return an independent copy positioned identically to this shape
     */
    public abstract CollisionShape copy();

    /**
     * Result of a collision test between two shapes
     */
    public static class CollisionResult {
        public final boolean  collides;
        public final Point3f  contactPoint;
        /**
         * Direction convention: points from shape1 (A) toward shape2 (B), unit length.
         * <p>
         * Intended convention: points from shape1 (A) toward shape2 (B), unit length.
         * {@code applyPositionCorrection} assumes A→B and moves A backward along the normal, B forward.
         * <p>
         * {@code ImpulseResolver} uses a convention-independent separating-contact guard:
         * it computes {@code normalDotAtoB = contactNormal · (posB − posA)} to detect the actual
         * normal orientation at runtime; separating iff {@code normalDotAtoB * velocityAlongNormal < 0}
         * (works for both A→B and B→A normals; safe no-op for coincident centers).
         * <p>
         * Consumers that invert: {@code ContactConstraint} negates the normal to work in its own B→A frame.
         * <p>
         * Producers must therefore supply the normal pointing from the first argument shape toward the
         * second argument shape at the collision site.
         */
        public final Vector3f contactNormal;
        public final float    penetrationDepth;
        /**
         * Contact manifold (Luciferase-nm9dj): the set of contact points for this collision. Face-face / face-edge
         * contacts produce a polygon of 2-4 points; point contacts (sphere, edge-edge) produce a single point. Never
         * null — empty for no-collision, otherwise at least the {@link #contactPoint}. Resolvers needing correct
         * angular-impulse arms should distribute the impulse across these points rather than the single representative.
         */
        public final java.util.List<Point3f> contactManifold;

        public CollisionResult(boolean collides, Point3f contactPoint, Vector3f contactNormal, float penetrationDepth) {
            this(collides, contactPoint, contactNormal, penetrationDepth,
                 contactPoint != null ? java.util.List.of(new Point3f(contactPoint)) : java.util.List.of());
        }

        public CollisionResult(boolean collides, Point3f contactPoint, Vector3f contactNormal, float penetrationDepth,
                               java.util.List<Point3f> contactManifold) {
            this.collides = collides;
            this.contactPoint = contactPoint;
            this.contactNormal = contactNormal;
            this.penetrationDepth = penetrationDepth;
            this.contactManifold = contactManifold != null ? java.util.List.copyOf(contactManifold) : java.util.List.of();
        }

        public static CollisionResult collision(Point3f contactPoint, Vector3f contactNormal, float penetrationDepth) {
            return new CollisionResult(true, contactPoint, contactNormal, penetrationDepth);
        }

        /** Collision with an explicit contact manifold (Luciferase-nm9dj); {@code contactPoint} is the representative. */
        public static CollisionResult collision(Point3f contactPoint, Vector3f contactNormal, float penetrationDepth,
                                                java.util.List<Point3f> contactManifold) {
            return new CollisionResult(true, contactPoint, contactNormal, penetrationDepth, contactManifold);
        }

        public static CollisionResult noCollision() {
            return new CollisionResult(false, null, null, 0);
        }
    }

    /**
     * Result of a ray intersection test
     */
    public static class RayIntersectionResult {
        public final boolean  intersects;
        public final float    distance;
        public final Point3f  intersectionPoint;
        public final Vector3f normal;

        public RayIntersectionResult(boolean intersects, float distance, Point3f intersectionPoint, Vector3f normal) {
            this.intersects = intersects;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
            this.normal = normal;
        }

        public static RayIntersectionResult intersection(float distance, Point3f intersectionPoint, Vector3f normal) {
            return new RayIntersectionResult(true, distance, intersectionPoint, normal);
        }

        public static RayIntersectionResult noIntersection() {
            return new RayIntersectionResult(false, Float.MAX_VALUE, null, null);
        }
    }
    
    /**
     * Helper method to check if two bounds intersect
     */
    protected static boolean boundsIntersect(EntityBounds b1, EntityBounds b2) {
        return !(b1.getMaxX() < b2.getMinX() || b1.getMinX() > b2.getMaxX() ||
                b1.getMaxY() < b2.getMinY() || b1.getMinY() > b2.getMaxY() ||
                b1.getMaxZ() < b2.getMinZ() || b1.getMinZ() > b2.getMaxZ());
    }
}