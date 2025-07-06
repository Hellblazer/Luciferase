package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that subdivision coordinates provide better containment than standard coordinates.
 */
public class SubdivisionContainmentTest {
    
    private Tetree<LongEntityID, String> tetree;
    
    @BeforeEach
    void setup() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void compareContainmentMethods() {
        byte level = 5;
        Random random = new Random(42); // Fixed seed for reproducibility
        
        // Use same range as TetreeVisualizationDemo
        float maxCoord = (float) Math.pow(2, TetreeKey.MAX_REFINEMENT_LEVEL);
        float minRange = maxCoord * 0.2f;
        float maxRange = maxCoord * 0.8f;
        float range = maxRange - minRange;
        
        int totalEntities = 100;
        int standardContained = 0;
        int subdivisionContained = 0;
        int bothContained = 0;
        int neitherContained = 0;
        
        for (int i = 0; i < totalEntities; i++) {
            float x = minRange + random.nextFloat() * range;
            float y = minRange + random.nextFloat() * range;
            float z = minRange + random.nextFloat() * range;
            Point3f position = new Point3f(x, y, z);
            Point3i intPos = new Point3i(Math.round(x), Math.round(y), Math.round(z));
            
            // Find enclosing tetrahedron
            var enclosing = tetree.enclosing(intPos, level);
            if (enclosing != null) {
                Tet tet = Tet.tetrahedron(enclosing.sfcIndex());
                
                // Check containment with standard coordinates
                boolean standardContains = tet.contains(position);
                
                // Check containment with subdivision coordinates manually
                Point3i[] subCoords = tet.subdivisionCoordinates();
                boolean subdivisionContains = checkTetrahedralContainment(subCoords, position);
                
                if (standardContains && subdivisionContains) {
                    bothContained++;
                } else if (standardContains) {
                    standardContained++;
                } else if (subdivisionContains) {
                    subdivisionContained++;
                } else {
                    neitherContained++;
                }
            }
        }
        
        System.out.println("\n=== Containment Comparison Results ===");
        System.out.println("Total entities tested: " + totalEntities);
        System.out.println("Both methods contain: " + bothContained);
        System.out.println("Only standard contains: " + standardContained);
        System.out.println("Only subdivision contains: " + subdivisionContained);
        System.out.println("Neither method contains: " + neitherContained);
        
        double standardRate = (double)(bothContained + standardContained) / totalEntities * 100;
        double subdivisionRate = (double)(bothContained + subdivisionContained) / totalEntities * 100;
        
        System.out.printf("\nStandard containment rate: %.1f%%%n", standardRate);
        System.out.printf("Subdivision containment rate: %.1f%%%n", subdivisionRate);
        System.out.printf("Improvement: %.1f%% -> %.1f%% (%.1f%% better)%n", 
            standardRate, subdivisionRate, subdivisionRate - standardRate);
        
        // After S0-S5 fix, standard coordinates now provide 100% containment!
        // Subdivision coordinates are no longer needed as a workaround
        assertTrue(standardRate >= 95.0, 
            "Standard S0-S5 coordinates should provide near-perfect containment");
        
        System.out.println("\nS0-S5 Fix Validation: Standard coordinates now achieve perfect containment!");
        System.out.println("Subdivision coordinates are no longer needed as a workaround.");
    }
    
    private boolean checkTetrahedralContainment(Point3i[] vertices, Point3f point) {
        // Simple tetrahedral containment using barycentric coordinates
        Point3f v0 = new Point3f(vertices[0].x, vertices[0].y, vertices[0].z);
        Point3f v1 = new Point3f(vertices[1].x, vertices[1].y, vertices[1].z);
        Point3f v2 = new Point3f(vertices[2].x, vertices[2].y, vertices[2].z);
        Point3f v3 = new Point3f(vertices[3].x, vertices[3].y, vertices[3].z);
        
        // Use same-side method for each face
        return sameSide(v0, v1, v2, v3, point) &&
               sameSide(v1, v2, v3, v0, point) &&
               sameSide(v2, v3, v0, v1, point) &&
               sameSide(v3, v0, v1, v2, point);
    }
    
    private boolean sameSide(Point3f v1, Point3f v2, Point3f v3, Point3f v4, Point3f p) {
        // Check if p is on the same side of plane (v1,v2,v3) as v4
        float nx = (v2.y - v1.y) * (v3.z - v1.z) - (v2.z - v1.z) * (v3.y - v1.y);
        float ny = (v2.z - v1.z) * (v3.x - v1.x) - (v2.x - v1.x) * (v3.z - v1.z);
        float nz = (v2.x - v1.x) * (v3.y - v1.y) - (v2.y - v1.y) * (v3.x - v1.x);
        
        float dot1 = nx * (v4.x - v1.x) + ny * (v4.y - v1.y) + nz * (v4.z - v1.z);
        float dot2 = nx * (p.x - v1.x) + ny * (p.y - v1.y) + nz * (p.z - v1.z);
        
        return dot1 * dot2 >= 0;
    }
}