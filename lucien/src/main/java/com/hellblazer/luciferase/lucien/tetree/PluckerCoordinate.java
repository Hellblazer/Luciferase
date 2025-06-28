/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * <p>
 * This file is part of the Luciferase project.
 */
package com.hellblazer.luciferase.lucien.tetree;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Plücker coordinate representation for lines and rays in 3D space. Based on the algorithm from Platis & Theoharis
 * (2003) "Fast Ray-Tetrahedron Intersection Using Plücker Coordinates".
 *
 * Plücker coordinates represent a ray r as πᵣ = {L : L × P} = {Uᵣ : Vᵣ} where: - U is the direction vector - V is the
 * direction × point cross product
 *
 * The key property is the permuted inner product: πᵣ ⊙ πₛ = Uᵣ · Vₛ + Uₛ · Vᵣ which determines spatial orientation
 * between two lines.
 *
 * @author hal.hildebrand
 */
public class PluckerCoordinate {

    public final Vector3f U;  // Direction vector
    public final Vector3f V;  // Direction × Point cross product

    /**
     * Create Plücker coordinates for a ray defined by point and direction.
     *
     * @param point     A point on the line/ray
     * @param direction Direction vector of the line/ray
     */
    public PluckerCoordinate(Point3f point, Vector3f direction) {
        this.U = new Vector3f(direction);
        this.V = new Vector3f();

        // V = direction × point  (Plücker coordinate definition)
        Vector3f pointVec = new Vector3f(point.x, point.y, point.z);
        this.V.cross(direction, pointVec);
    }

    /**
     * Create Plücker coordinates for a line segment defined by two points.
     *
     * @param p1 First point on the line
     * @param p2 Second point on the line
     */
    public PluckerCoordinate(Point3f p1, Point3f p2) {
        // Direction vector: p2 - p1
        this.U = new Vector3f(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z);

        // V = U × p1 = (p2-p1) × p1 = p2×p1 - p1×p1 = p2×p1 (since p1×p1 = 0)
        Vector3f p1Vec = new Vector3f(p1.x, p1.y, p1.z);
        Vector3f p2Vec = new Vector3f(p2.x, p2.y, p2.z);
        this.V = new Vector3f();
        this.V.cross(p2Vec, p1Vec);
    }

    /**
     * Direct constructor for pre-computed Plücker coordinates.
     *
     * @param U Direction vector
     * @param V Direction × Point cross product
     */
    public PluckerCoordinate(Vector3f U, Vector3f V) {
        this.U = new Vector3f(U);
        this.V = new Vector3f(V);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PluckerCoordinate other)) {
            return false;
        }

        final float EPSILON = 1e-6f;
        return Math.abs(U.x - other.U.x) < EPSILON && Math.abs(U.y - other.U.y) < EPSILON && Math.abs(U.z - other.U.z)
        < EPSILON && Math.abs(V.x - other.V.x) < EPSILON && Math.abs(V.y - other.V.y) < EPSILON && Math.abs(
        V.z - other.V.z) < EPSILON;
    }

    @Override
    public int hashCode() {
        return U.hashCode() * 31 + V.hashCode();
    }

    /**
     * Check if this line intersects or is parallel to another line.
     *
     * @param other The other Plücker coordinate
     * @return true if lines intersect or are parallel
     */
    public boolean intersectsOrParallel(PluckerCoordinate other) {
        return Math.abs(permutedInnerProduct(other)) < 1e-6f;
    }

    /**
     * Compute the permuted inner product between two Plücker coordinates. This is the fundamental operation for
     * Plücker-based intersection tests.
     *
     * Formula: πᵣ ⊙ πₛ = Uᵣ · Vₛ + Uₛ · Vᵣ
     *
     * Geometric interpretation: - > 0: s goes counterclockwise around r - < 0: s goes clockwise around r - = 0: s
     * intersects or is parallel to r
     *
     * @param other The other Plücker coordinate
     * @return Permuted inner product value
     */
    public float permutedInnerProduct(PluckerCoordinate other) {
        return this.U.dot(other.V) + other.U.dot(this.V);
    }

    /**
     * Get the sign of the permuted inner product (-1, 0, or 1). Used in the Plücker ray-tetrahedron intersection
     * algorithm.
     *
     * @param other The other Plücker coordinate
     * @return -1 if negative, 0 if zero, 1 if positive
     */
    public int signOfPermutedInnerProduct(PluckerCoordinate other) {
        float product = permutedInnerProduct(other);
        if (product > 0) {
            return 1;
        }
        if (product < 0) {
            return -1;
        }
        return 0;
    }

    @Override
    public String toString() {
        return String.format("PluckerCoord{U=(%f,%f,%f), V=(%f,%f,%f)}", U.x, U.y, U.z, V.x, V.y, V.z);
    }
}
