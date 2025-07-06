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
 * Debug test to understand why entities appear outside their containing tetrahedra.
 */
public class TetreeVisualizationDebugTest {
    
    private Tetree<LongEntityID, String> tetree;
    
    @BeforeEach
    void setup() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void debugEntityContainment() {
        byte level = 5;
        Random random = new Random(42); // Fixed seed for reproducibility
        
        // Use same range as TetreeVisualizationDemo
        float maxCoord = (float) Math.pow(2, TetreeKey.MAX_REFINEMENT_LEVEL);
        float minRange = maxCoord * 0.2f;  // Start at 20% of max
        float maxRange = maxCoord * 0.8f;  // End at 80% of max
        float range = maxRange - minRange;
        
        System.out.println("Testing entity containment with Tetree visualization parameters:");
        System.out.println("Max coordinate: " + maxCoord);
        System.out.println("Min range: " + minRange);
        System.out.println("Max range: " + maxRange);
        System.out.println();
        
        // Test 5 entities
        for (int i = 0; i < 5; i++) {
            float x = minRange + random.nextFloat() * range;
            float y = minRange + random.nextFloat() * range;
            float z = minRange + random.nextFloat() * range;
            Point3f position = new Point3f(x, y, z);
            Point3i intPos = new Point3i(Math.round(x), Math.round(y), Math.round(z));
            
            System.out.printf("=== Entity %d at (%.0f, %.0f, %.0f) ===%n", i, x, y, z);
            
            // Find enclosing before insertion
            var enclosing = tetree.enclosing(intPos, level);
            assertNotNull(enclosing, "Should find enclosing tetrahedron");
            
            // Get the actual tetrahedron
            Tet tet = Tet.tetrahedron(enclosing.sfcIndex());
            System.out.printf("Enclosing tet: anchor=(%d,%d,%d), type=%d, level=%d%n",
                tet.x(), tet.y(), tet.z(), tet.type(), tet.l());
            
            // Check containment with standard coordinates
            boolean contains = tet.contains(position);
            System.out.printf("tet.contains(position) = %s%n", contains);
            
            // Get the vertices
            Point3i[] coords = tet.coordinates();
            System.out.println("Tet vertices (standard):");
            for (int v = 0; v < 4; v++) {
                System.out.printf("  V%d: (%d, %d, %d)%n", v, coords[v].x, coords[v].y, coords[v].z);
            }
            
            // Check with subdivision coordinates  
            Point3i[] subCoords = tet.subdivisionCoordinates();
            System.out.println("Tet vertices (subdivision):");
            for (int v = 0; v < 4; v++) {
                System.out.printf("  V%d: (%d, %d, %d)%n", v, subCoords[v].x, subCoords[v].y, subCoords[v].z);
            }
            
            // Insert the entity
            var entityId = tetree.insert(position, level, "Entity " + i);
            
            // Verify it's in the expected node
            var afterEnclosing = tetree.enclosing(intPos, level);
            assertTrue(afterEnclosing.entityIds().contains(entityId),
                "Entity should be in the enclosing node after insertion");
            
            // Check if this is a t8code gap issue
            if (!contains) {
                System.out.println("WARNING: This appears to be a t8code gap - entity not contained by its enclosing tet");
                
                // Try to find which tet actually contains it
                System.out.println("Searching for containing tet at same level...");
                int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
                
                // Check neighboring locations
                int searchRadius = 2; // Check 2 cells in each direction
                boolean found = false;
                for (int dx = -searchRadius; dx <= searchRadius && !found; dx++) {
                    for (int dy = -searchRadius; dy <= searchRadius && !found; dy++) {
                        for (int dz = -searchRadius; dz <= searchRadius && !found; dz++) {
                            int testX = intPos.x + dx * cellSize;
                            int testY = intPos.y + dy * cellSize;
                            int testZ = intPos.z + dz * cellSize;
                            
                            // Skip if out of bounds
                            if (testX < 0 || testY < 0 || testZ < 0 ||
                                testX >= maxCoord || testY >= maxCoord || testZ >= maxCoord) {
                                continue;
                            }
                            
                            try {
                                var neighborEnclosing = tetree.enclosing(new Point3i(testX, testY, testZ), level);
                                if (neighborEnclosing != null) {
                                    Tet neighborTet = Tet.tetrahedron(neighborEnclosing.sfcIndex());
                                    if (neighborTet.contains(position)) {
                                        System.out.printf("Found containing tet at offset (%d,%d,%d): anchor=(%d,%d,%d), type=%d%n",
                                            dx, dy, dz, neighborTet.x(), neighborTet.y(), neighborTet.z(), neighborTet.type());
                                        found = true;
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore errors for out-of-bounds coordinates
                            }
                        }
                    }
                }
                
                if (!found) {
                    System.out.println("No containing tet found in neighborhood!");
                }
            }
            
            System.out.println();
        }
        
        System.out.println("=== Summary ===");
        System.out.println("Total entities: " + tetree.size());
        System.out.println("Total nodes: " + tetree.nodeCount());
    }
}