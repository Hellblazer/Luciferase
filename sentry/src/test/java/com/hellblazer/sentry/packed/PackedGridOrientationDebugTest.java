package com.hellblazer.sentry.packed;

import com.hellblazer.sentry.Vertex;
import com.hellblazer.luciferase.geometry.Geometry;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;

/**
 * Debug test to understand orientation issues
 */
public class PackedGridOrientationDebugTest {
    
    @Test
    public void debugOrientations() {
        // The four corners from Grid.java
        float SCALE = (float) Math.pow(2, 24);
        Vertex[] FOUR_CORNERS = {
            new Vertex(-1, 1, -1, SCALE),   // 0
            new Vertex(1, 1, 1, SCALE),     // 1
            new Vertex(1, -1, -1, SCALE),   // 2
            new Vertex(-1, -1, 1, SCALE)    // 3
        };
        
        System.out.println("Four corners:");
        for (int i = 0; i < 4; i++) {
            System.out.printf("V%d: (%.1f, %.1f, %.1f)\n", i, 
                FOUR_CORNERS[i].x, FOUR_CORNERS[i].y, FOUR_CORNERS[i].z);
        }
        
        Point3f origin = new Point3f(0, 0, 0);
        
        // Test all four faces
        System.out.println("\nFace orientations with respect to origin:");
        
        // Face opposite A (contains B,C,D = vertices 1,2,3)
        double orientA = Geometry.leftOfPlaneFast(
            FOUR_CORNERS[1].x, FOUR_CORNERS[1].y, FOUR_CORNERS[1].z,
            FOUR_CORNERS[2].x, FOUR_CORNERS[2].y, FOUR_CORNERS[2].z,
            FOUR_CORNERS[3].x, FOUR_CORNERS[3].y, FOUR_CORNERS[3].z,
            origin.x, origin.y, origin.z
        );
        System.out.printf("Face opposite A (BCD): %.2e\n", orientA);
        
        // Face opposite B (contains A,C,D = vertices 0,2,3)
        double orientB = Geometry.leftOfPlaneFast(
            FOUR_CORNERS[0].x, FOUR_CORNERS[0].y, FOUR_CORNERS[0].z,
            FOUR_CORNERS[2].x, FOUR_CORNERS[2].y, FOUR_CORNERS[2].z,
            FOUR_CORNERS[3].x, FOUR_CORNERS[3].y, FOUR_CORNERS[3].z,
            origin.x, origin.y, origin.z
        );
        System.out.printf("Face opposite B (ACD): %.2e\n", orientB);
        
        // Face opposite C (contains A,B,D = vertices 0,1,3)
        double orientC = Geometry.leftOfPlaneFast(
            FOUR_CORNERS[0].x, FOUR_CORNERS[0].y, FOUR_CORNERS[0].z,
            FOUR_CORNERS[1].x, FOUR_CORNERS[1].y, FOUR_CORNERS[1].z,
            FOUR_CORNERS[3].x, FOUR_CORNERS[3].y, FOUR_CORNERS[3].z,
            origin.x, origin.y, origin.z
        );
        System.out.printf("Face opposite C (ABD): %.2e\n", orientC);
        
        // Face opposite D (contains A,B,C = vertices 0,1,2)
        double orientD = Geometry.leftOfPlaneFast(
            FOUR_CORNERS[0].x, FOUR_CORNERS[0].y, FOUR_CORNERS[0].z,
            FOUR_CORNERS[1].x, FOUR_CORNERS[1].y, FOUR_CORNERS[1].z,
            FOUR_CORNERS[2].x, FOUR_CORNERS[2].y, FOUR_CORNERS[2].z,
            origin.x, origin.y, origin.z
        );
        System.out.printf("Face opposite D (ABC): %.2e\n", orientD);
        
        // Check if the tetrahedron is oriented correctly
        // For a correctly oriented tetrahedron from vertex D's perspective,
        // vertices A, B, C should be in counter-clockwise order
        System.out.println("\nChecking tetrahedron orientation:");
        double volume = Geometry.leftOfPlaneFast(
            FOUR_CORNERS[0].x, FOUR_CORNERS[0].y, FOUR_CORNERS[0].z,
            FOUR_CORNERS[1].x, FOUR_CORNERS[1].y, FOUR_CORNERS[1].z,
            FOUR_CORNERS[2].x, FOUR_CORNERS[2].y, FOUR_CORNERS[2].z,
            FOUR_CORNERS[3].x, FOUR_CORNERS[3].y, FOUR_CORNERS[3].z
        );
        System.out.printf("Volume/orientation (should be positive): %.2e\n", volume);
    }
}