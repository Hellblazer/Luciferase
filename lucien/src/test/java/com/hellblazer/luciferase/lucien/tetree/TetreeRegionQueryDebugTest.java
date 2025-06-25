package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to understand why entitiesInRegion is not working correctly for Tetree
 */
public class TetreeRegionQueryDebugTest {
    
    private Tetree<LongEntityID, String> tetree;
    
    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void debugRegionQuery() {
        // Insert a single entity at a known position
        Point3f entityPos = new Point3f(50, 50, 50);
        byte level = 5;
        LongEntityID id = tetree.insert(entityPos, level, "TestEntity");
        
        System.out.println("=== Debug Region Query ===");
        System.out.println("Entity inserted at: " + entityPos + " at level " + level);
        System.out.println("Entity ID: " + id);
        
        // Find which tetrahedron contains this entity
        Tet tet = tetree.locateTetrahedron(entityPos, level);
        System.out.println("Entity is in tetrahedron: " + tet);
        System.out.println("Tetrahedron index: " + tet.tmIndex());
        System.out.println("Tetrahedron bounds: " + java.util.Arrays.toString(tet.coordinates()));
        
        // Test 1: Region that should contain the entity
        Spatial.Cube containingRegion = new Spatial.Cube(0, 0, 0, 100);
        List<LongEntityID> results1 = tetree.entitiesInRegion(containingRegion);
        System.out.println("\nRegion (0,0,0) to (100,100,100):");
        System.out.println("  Found " + results1.size() + " entities");
        assertEquals(1, results1.size(), "Should find the entity in containing region");
        
        // Test 2: Region that should NOT contain the entity
        Spatial.Cube nonContainingRegion = new Spatial.Cube(200, 200, 200, 100);
        List<LongEntityID> results2 = tetree.entitiesInRegion(nonContainingRegion);
        System.out.println("\nRegion (200,200,200) to (300,300,300):");
        System.out.println("  Found " + results2.size() + " entities");
        if (!results2.isEmpty()) {
            System.out.println("  ERROR: Found entities that shouldn't be in region!");
            for (LongEntityID foundId : results2) {
                Point3f foundPos = tetree.getEntityPosition(foundId);
                System.out.println("    Entity " + foundId + " at position " + foundPos);
            }
        }
        assertTrue(results2.isEmpty(), "Should NOT find the entity in non-containing region");
        
        // Test 3: Very small region around the entity
        Spatial.Cube tinyRegion = new Spatial.Cube(49, 49, 49, 2);
        List<LongEntityID> results3 = tetree.entitiesInRegion(tinyRegion);
        System.out.println("\nRegion (49,49,49) to (51,51,51):");
        System.out.println("  Found " + results3.size() + " entities");
        assertEquals(1, results3.size(), "Should find the entity in tiny region around it");
        
        // Test 4: Check how entitiesInRegion works internally
        System.out.println("\n=== Internal Debug ===");
        System.out.println("Total nodes in tree: " + tetree.nodeCount());
        System.out.println("Total entities: " + tetree.entityCount());
        
        // Let's see what getSpatialIndexRange returns for the non-containing region
        var bounds = new com.hellblazer.luciferase.lucien.VolumeBounds(200, 200, 200, 300, 300, 300);
        System.out.println("\nChecking which nodes intersect bounds (200,200,200) to (300,300,300):");
        
        // Check if the node containing our entity thinks it intersects the test bounds
        TetreeKey entityKey = tet.tmIndex();
        
        // Add more debug to understand why entitiesInRegion returns empty
        System.out.println("\n=== Debug entitiesInRegion ===");
        System.out.println("sortedSpatialIndices size: " + tetree.getSortedSpatialIndices().size());
        System.out.println("spatialIndex contains entity key? " + tetree.getSortedSpatialIndices().contains(entityKey));
        
        // Try to manually check what getSpatialIndexRange would return
        var testBounds = new com.hellblazer.luciferase.lucien.VolumeBounds(0, 0, 0, 100, 100, 100);
        System.out.println("Testing bounds (0,0,0) to (100,100,100)");
        boolean intersects = tetree.doesNodeIntersectVolume(entityKey, 
            new Spatial.Cube(0, 0, 0, 100));
        System.out.println("Does entity's tetrahedron intersect test bounds? " + intersects);
        
        if (!intersects) {
            System.out.println("ERROR: Tetrahedron should intersect the test bounds!");
            System.out.println("Tetrahedron: " + tet);
            System.out.println("Tetrahedron vertices: " + java.util.Arrays.toString(tet.coordinates()));
        }
    }
    
    @Test
    void testSimpleNonOverlap() {
        // Even simpler test with clear non-overlapping regions
        tetree.insert(new Point3f(10, 10, 10), (byte)5, "Near origin");
        tetree.insert(new Point3f(1000, 1000, 1000), (byte)5, "Far away");
        
        // Query small region near origin
        List<LongEntityID> nearResults = tetree.entitiesInRegion(
            new Spatial.Cube(0, 0, 0, 50));
        assertEquals(1, nearResults.size(), "Should find only near entity");
        
        // Query small region far away
        List<LongEntityID> farResults = tetree.entitiesInRegion(
            new Spatial.Cube(900, 900, 900, 200));
        assertEquals(1, farResults.size(), "Should find only far entity");
        
        // Query region that contains neither
        List<LongEntityID> emptyResults = tetree.entitiesInRegion(
            new Spatial.Cube(400, 400, 400, 100));
        assertEquals(0, emptyResults.size(), "Should find no entities in middle region");
    }
}