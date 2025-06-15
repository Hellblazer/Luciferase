/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test improved ray-sphere intersection algorithms
 */
public class ImprovedRaySphereTest {

    @Test
    void testImprovedRaySphereIntersection() {
        Point3f rayOrigin = new Point3f(50, 50, 50);
        Vector3f rayDir = new Vector3f(1, 1, 1);
        rayDir.normalize();
        
        // Test problematic entity
        Point3f entityPos = new Point3f(590, 590, 590);
        float radius = 0.1f;
        
        // Original algorithm
        float originalResult = originalRaySphere(rayOrigin, rayDir, entityPos, radius);
        System.out.println("Original algorithm result: " + originalResult);
        
        // Improved algorithm - uses different formulation
        float improvedResult = improvedRaySphere(rayOrigin, rayDir, entityPos, radius);
        System.out.println("Improved algorithm result: " + improvedResult);
        
        // Alternative algorithm - geometric approach
        float geometricResult = geometricRaySphere(rayOrigin, rayDir, entityPos, radius);
        System.out.println("Geometric algorithm result: " + geometricResult);
        
        // At least one algorithm should find the intersection
        assertTrue(originalResult >= 0 || improvedResult >= 0 || geometricResult >= 0,
                  "At least one algorithm should find intersection");
    }
    
    // Original algorithm from AbstractSpatialIndex
    private float originalRaySphere(Point3f rayOrigin, Vector3f rayDir, Point3f center, float radius) {
        Vector3f oc = new Vector3f();
        oc.sub(rayOrigin, center);

        float a = rayDir.dot(rayDir);
        float b = 2.0f * oc.dot(rayDir);
        float c = oc.dot(oc) - radius * radius;

        float discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            return -1;
        }

        float sqrtDiscriminant = (float) Math.sqrt(discriminant);
        float t1 = (-b - sqrtDiscriminant) / (2 * a);
        float t2 = (-b + sqrtDiscriminant) / (2 * a);

        if (t1 >= 0) {
            return t1;
        }
        if (t2 >= 0) {
            return t2;
        }

        return -1;
    }
    
    // Improved algorithm - different formulation to reduce numerical errors
    private float improvedRaySphere(Point3f rayOrigin, Vector3f rayDir, Point3f center, float radius) {
        Vector3f L = new Vector3f();
        L.sub(center, rayOrigin);  // Vector from ray origin to sphere center
        
        float tca = L.dot(rayDir);  // Project L onto ray direction
        if (tca < 0) {
            // Sphere is behind ray origin
            return -1;
        }
        
        float d2 = L.dot(L) - tca * tca;  // Squared distance from sphere center to ray
        float radius2 = radius * radius;
        
        if (d2 > radius2) {
            // Ray misses sphere
            return -1;
        }
        
        float thc = (float) Math.sqrt(radius2 - d2);
        
        float t0 = tca - thc;
        float t1 = tca + thc;
        
        if (t0 >= 0) {
            return t0;
        }
        if (t1 >= 0) {
            return t1;
        }
        
        return -1;
    }
    
    // Geometric algorithm - uses closest point on ray
    private float geometricRaySphere(Point3f rayOrigin, Vector3f rayDir, Point3f center, float radius) {
        // Find the closest point on the ray to the sphere center
        Vector3f toCenter = new Vector3f();
        toCenter.sub(center, rayOrigin);
        
        float t = toCenter.dot(rayDir);  // Parameter for closest point
        if (t < 0) {
            // Closest point is behind ray origin
            t = 0;
        }
        
        Point3f closestPoint = new Point3f(
            rayOrigin.x + t * rayDir.x,
            rayOrigin.y + t * rayDir.y,
            rayOrigin.z + t * rayDir.z
        );
        
        float distToCenter = closestPoint.distance(center);
        
        if (distToCenter > radius) {
            // Ray misses sphere
            return -1;
        }
        
        // Calculate intersection points
        float halfChordLength = (float) Math.sqrt(radius * radius - distToCenter * distToCenter);
        
        float t1 = t - halfChordLength;
        float t2 = t + halfChordLength;
        
        if (t1 >= 0) {
            return t1;
        }
        if (t2 >= 0) {
            return t2;
        }
        
        return -1;
    }
    
    @Test
    void testMultipleDistances() {
        Point3f rayOrigin = new Point3f(50, 50, 50);
        Vector3f rayDir = new Vector3f(1, 1, 1);
        rayDir.normalize();
        float radius = 0.1f;
        
        // Test at various distances
        float[] distances = {100, 200, 300, 400, 500, 590};
        
        System.out.println("\nTesting at various distances:");
        System.out.println("Distance | Original | Improved | Geometric");
        System.out.println("---------|----------|----------|----------");
        
        for (float dist : distances) {
            Point3f entityPos = new Point3f(
                50 + dist / (float)Math.sqrt(3),
                50 + dist / (float)Math.sqrt(3),
                50 + dist / (float)Math.sqrt(3)
            );
            
            float original = originalRaySphere(rayOrigin, rayDir, entityPos, radius);
            float improved = improvedRaySphere(rayOrigin, rayDir, entityPos, radius);
            float geometric = geometricRaySphere(rayOrigin, rayDir, entityPos, radius);
            
            System.out.printf("%8.1f | %8.2f | %8.2f | %8.2f%n", 
                            dist, original, improved, geometric);
        }
    }
}