package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Represents a 3D plane using the equation ax + by + cz + d = 0
 * All coordinate operations are constrained to positive values only
 * 
 * @author hal.hildebrand
 */
public record Plane3D(float a, float b, float c, float d) {

    /**
     * Create a plane from three points (all coordinates must be positive)
     * Points must not be collinear
     * 
     * @param p1 first point (positive coordinates only)
     * @param p2 second point (positive coordinates only) 
     * @param p3 third point (positive coordinates only)
     * @return the plane containing the three points
     * @throws IllegalArgumentException if any coordinate is negative or points are collinear
     */
    public static Plane3D fromThreePoints(Point3f p1, Point3f p2, Point3f p3) {
        validatePositiveCoordinates(p1, "p1");
        validatePositiveCoordinates(p2, "p2");
        validatePositiveCoordinates(p3, "p3");
        
        // Calculate two vectors in the plane
        Vector3f v1 = new Vector3f(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z);
        Vector3f v2 = new Vector3f(p3.x - p1.x, p3.y - p1.y, p3.z - p1.z);
        
        // Calculate normal vector via cross product
        Vector3f normal = new Vector3f();
        normal.cross(v1, v2);
        
        // Check for collinear points
        if (normal.length() < 1e-6f) {
            throw new IllegalArgumentException("Points are collinear, cannot define a unique plane");
        }
        
        // Normalize the normal vector
        normal.normalize();
        
        // Calculate d using point p1: ax + by + cz + d = 0, so d = -(ax + by + cz)
        float d = -(normal.x * p1.x + normal.y * p1.y + normal.z * p1.z);
        
        return new Plane3D(normal.x, normal.y, normal.z, d);
    }
    
    /**
     * Create a plane from a point and a normal vector (all coordinates must be positive)
     * 
     * @param point point on the plane (positive coordinates only)
     * @param normal normal vector to the plane (will be normalized)
     * @return the plane
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static Plane3D fromPointAndNormal(Point3f point, Vector3f normal) {
        validatePositiveCoordinates(point, "point");
        
        if (normal.length() < 1e-6f) {
            throw new IllegalArgumentException("Normal vector cannot be zero");
        }
        
        // Normalize the normal vector
        Vector3f normalizedNormal = new Vector3f(normal);
        normalizedNormal.normalize();
        
        // Calculate d: d = -(ax + by + cz)
        float d = -(normalizedNormal.x * point.x + normalizedNormal.y * point.y + normalizedNormal.z * point.z);
        
        return new Plane3D(normalizedNormal.x, normalizedNormal.y, normalizedNormal.z, d);
    }
    
    /**
     * Calculate the signed distance from a point to this plane
     * Positive distance means the point is on the side of the plane in the direction of the normal
     * 
     * @param point the point to test (positive coordinates only)
     * @return signed distance from point to plane
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public float distanceToPoint(Point3f point) {
        validatePositiveCoordinates(point, "point");
        return a * point.x + b * point.y + c * point.z + d;
    }
    
    /**
     * Calculate the absolute distance from a point to this plane
     * 
     * @param point the point to test (positive coordinates only)
     * @return absolute distance from point to plane
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public float absoluteDistanceToPoint(Point3f point) {
        return Math.abs(distanceToPoint(point));
    }
    
    /**
     * Test if a point lies on this plane (within tolerance)
     * 
     * @param point the point to test (positive coordinates only)
     * @param tolerance tolerance for floating point comparison
     * @return true if point is on the plane
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public boolean containsPoint(Point3f point, float tolerance) {
        return absoluteDistanceToPoint(point) <= tolerance;
    }
    
    /**
     * Test if a point lies on this plane (using default tolerance)
     * 
     * @param point the point to test (positive coordinates only)
     * @return true if point is on the plane
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public boolean containsPoint(Point3f point) {
        return containsPoint(point, 1e-6f);
    }
    
    /**
     * Get the normal vector of this plane
     * 
     * @return normalized normal vector
     */
    public Vector3f getNormal() {
        return new Vector3f(a, b, c);
    }
    
    /**
     * Test if this plane intersects with an axis-aligned bounding box
     * Uses the separating axis theorem for plane-AABB intersection
     * 
     * @param minX minimum X coordinate of the box (must be positive)
     * @param minY minimum Y coordinate of the box (must be positive)
     * @param minZ minimum Z coordinate of the box (must be positive)
     * @param maxX maximum X coordinate of the box (must be positive)
     * @param maxY maximum Y coordinate of the box (must be positive)
     * @param maxZ maximum Z coordinate of the box (must be positive)
     * @return true if plane intersects the box
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public boolean intersectsAABB(float minX, float minY, float minZ, 
                                  float maxX, float maxY, float maxZ) {
        if (minX < 0 || minY < 0 || minZ < 0 || maxX < 0 || maxY < 0 || maxZ < 0) {
            throw new IllegalArgumentException("All coordinates must be positive");
        }
        
        // Find the positive and negative vertices of the AABB relative to plane normal
        float positiveX = (a >= 0) ? maxX : minX;
        float positiveY = (b >= 0) ? maxY : minY;
        float positiveZ = (c >= 0) ? maxZ : minZ;
        
        float negativeX = (a >= 0) ? minX : maxX;
        float negativeY = (b >= 0) ? minY : maxY;
        float negativeZ = (c >= 0) ? minZ : maxZ;
        
        // Calculate distances to positive and negative vertices
        float positiveDistance = a * positiveX + b * positiveY + c * positiveZ + d;
        float negativeDistance = a * negativeX + b * negativeY + c * negativeZ + d;
        
        // If distances have different signs, the plane intersects the box
        return positiveDistance * negativeDistance <= 0;
    }
    
    /**
     * Test if this plane intersects with a cube (all coordinates must be positive)
     * 
     * @param cube the cube to test intersection with
     * @return true if plane intersects the cube
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public boolean intersectsCube(Spatial.Cube cube) {
        return intersectsAABB(cube.originX(), cube.originY(), cube.originZ(),
                             cube.originX() + cube.extent(), 
                             cube.originY() + cube.extent(), 
                             cube.originZ() + cube.extent());
    }
    
    /**
     * Validate that all coordinates in a point are positive
     */
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }
    
    /**
     * Create a plane parallel to XY plane at given Z coordinate (Z must be positive)
     * 
     * @param z the Z coordinate (must be positive)
     * @return plane with equation z = constant
     * @throws IllegalArgumentException if z is negative
     */
    public static Plane3D parallelToXY(float z) {
        if (z < 0) {
            throw new IllegalArgumentException("Z coordinate must be positive, got: " + z);
        }
        return new Plane3D(0, 0, 1, -z);
    }
    
    /**
     * Create a plane parallel to XZ plane at given Y coordinate (Y must be positive)
     * 
     * @param y the Y coordinate (must be positive)
     * @return plane with equation y = constant
     * @throws IllegalArgumentException if y is negative
     */
    public static Plane3D parallelToXZ(float y) {
        if (y < 0) {
            throw new IllegalArgumentException("Y coordinate must be positive, got: " + y);
        }
        return new Plane3D(0, 1, 0, -y);
    }
    
    /**
     * Create a plane parallel to YZ plane at given X coordinate (X must be positive)
     * 
     * @param x the X coordinate (must be positive)
     * @return plane with equation x = constant
     * @throws IllegalArgumentException if x is negative
     */
    public static Plane3D parallelToYZ(float x) {
        if (x < 0) {
            throw new IllegalArgumentException("X coordinate must be positive, got: " + x);
        }
        return new Plane3D(1, 0, 0, -x);
    }
    
    @Override
    public String toString() {
        return String.format("Plane3D[%.3fx + %.3fy + %.3fz + %.3f = 0]", a, b, c, d);
    }
}