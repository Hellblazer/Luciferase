package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to understand why spatial range queries are returning 0 entities
 */
public class TetreeSpatialRangeDebugTest {
    
    private Tetree<LongEntityID, String> tetree;
    
    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void debugSpatialRangeQuery() {
        // Insert a single entity at a known position
        Point3f entityPos = new Point3f(500, 500, 500);
        byte level = 15;
        
        System.out.println("=== Debug Spatial Range Query ===");
        System.out.println("Entity position: " + entityPos);
        System.out.println("Level: " + level);
        System.out.println("Cell size at level: " + com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(level));
        
        LongEntityID id = tetree.insert(entityPos, level, "TestEntity");
        System.out.println("Inserted entity ID: " + id.getValue());
        
        // Find which tetrahedron contains this entity
        Tet tet = tetree.locateTetrahedron(entityPos, level);
        TetreeKey key = tet.tmIndex();
        System.out.println("\nTetrahedron info:");
        System.out.println("  Tet: " + tet);
        System.out.println("  Key: " + key);
        System.out.println("  Node exists? " + tetree.getSortedSpatialIndices().contains(key));
        
        // Print tetrahedron vertices
        var vertices = tet.coordinates();
        System.out.println("  Vertices:");
        for (int i = 0; i < vertices.length; i++) {
            System.out.println("    v" + i + ": " + vertices[i]);
        }
        
        // Create a query region that should definitely contain the entity
        var region = new com.hellblazer.luciferase.lucien.Spatial.Cube(400, 400, 400, 200);
        System.out.println("\nQuery region: (400,400,400) to (600,600,600)");
        
        // Test direct intersection
        var bounds = new VolumeBounds(400, 400, 400, 600, 600, 600);
        boolean intersects = Tet.tetrahedronIntersectsVolumeBounds(tet, bounds);
        System.out.println("Direct tetrahedronIntersectsVolumeBounds: " + intersects);
        
        // Check what VolumeBounds.from returns for the region
        var convertedBounds = VolumeBounds.from(region);
        System.out.println("VolumeBounds.from(region) returned: " + convertedBounds);
        System.out.println("Original bounds: " + bounds);
        System.out.println("Are they equal? " + bounds.equals(convertedBounds));
        
        // Check if Tet.tetrahedron(key) produces the same tet
        Tet tetFromKey = Tet.tetrahedron(key);
        System.out.println("\nTet from key: " + tetFromKey);
        System.out.println("Original tet: " + tet);
        System.out.println("Are they equal? " + tet.equals(tetFromKey));
        
        // Test with the tet from key
        boolean intersectsFromKey = Tet.tetrahedronIntersectsVolumeBounds(tetFromKey, bounds);
        System.out.println("tetrahedronIntersectsVolumeBounds with tet from key: " + intersectsFromKey);
        
        // Check if the tetree's doesNodeIntersectVolume works
        boolean nodeIntersects = tetree.doesNodeIntersectVolume(key, region);
        System.out.println("\ntetree.doesNodeIntersectVolume: " + nodeIntersects);
        
        // Check getSpatialIndexRange
        var candidates = tetree.getSpatialIndexRange(bounds);
        System.out.println("\ngetSpatialIndexRange returned " + candidates.size() + " candidates");
        if (!candidates.isEmpty()) {
            System.out.println("Candidates: " + candidates);
        }
        
        // Check sorted indices
        System.out.println("\nTotal nodes in tree: " + tetree.getSortedSpatialIndices().size());
        System.out.println("First few nodes: ");
        int count = 0;
        for (TetreeKey k : tetree.getSortedSpatialIndices()) {
            System.out.println("  " + k);
            if (++count >= 5) break;
        }
        
        // Finally, test entitiesInRegion
        List<LongEntityID> results = tetree.entitiesInRegion(region);
        System.out.println("\nentitiesInRegion found: " + results.size() + " entities");
        
        assertEquals(1, results.size(), "Should find the entity");
    }
    
    @Test
    void testSimplestCase() {
        // Use the root tetrahedron at level 0
        Point3f entityPos = new Point3f(100, 100, 100);
        byte level = 0;
        
        System.out.println("\n=== Simplest Case Test ===");
        System.out.println("Entity at: " + entityPos);
        System.out.println("Level: " + level);
        
        LongEntityID id = tetree.insert(entityPos, level, "RootEntity");
        
        // The entire positive octant should intersect
        var region = new com.hellblazer.luciferase.lucien.Spatial.Cube(0, 0, 0, 1000);
        List<LongEntityID> results = tetree.entitiesInRegion(region);
        
        System.out.println("Found " + results.size() + " entities");
        assertEquals(1, results.size(), "Should find entity at root level");
    }
}