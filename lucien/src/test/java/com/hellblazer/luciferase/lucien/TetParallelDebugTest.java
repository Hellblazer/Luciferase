package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test for TetParallelSpatialProcessor issues
 */
public class TetParallelDebugTest {
    
    @Test
    void debugSpatialQuery() {
        // Create a simple tetree with one point
        Tetree<String> tetree = new Tetree<>(new TreeMap<>());
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte level = 15;
        
        Point3f testPoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        long index = tetree.insert(testPoint, level, "test1");
        System.out.println("Inserted at index: " + index);
        
        // Verify we can retrieve it
        String content = tetree.get(index);
        System.out.println("Retrieved content: " + content);
        assertEquals("test1", content);
        
        // Now try a spatial query with bounds that should contain the point
        Point3f center = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        float radius = scale * 0.05f;
        
        Spatial.aabb searchBounds = new Spatial.aabb(
            Math.max(0, center.x - radius),
            Math.max(0, center.y - radius),
            Math.max(0, center.z - radius),
            center.x + radius,
            center.y + radius,
            center.z + radius
        );
        
        System.out.println("Search bounds: " + searchBounds);
        
        // Test boundedBy query
        List<Tetree.Simplex<String>> results = tetree.boundedBy(searchBounds).collect(Collectors.toList());
        System.out.println("BoundedBy results count: " + results.size());
        for (var simplex : results) {
            System.out.println("  Simplex index: " + simplex.index() + ", content: " + simplex.cell());
        }
        
        // Test with larger bounds
        Spatial.aabb largerBounds = new Spatial.aabb(
            0, 0, 0,
            scale * 0.5f, scale * 0.5f, scale * 0.5f
        );
        
        results = tetree.boundedBy(largerBounds).collect(Collectors.toList());
        System.out.println("Larger bounds results count: " + results.size());
        
        // Check if the tet is actually within the bounds
        Tet tet = Tet.tetrahedron(index);
        System.out.println("Tet coordinates: " + Arrays.toString(tet.coordinates()));
        System.out.println("Tet bounds check - should be within search bounds");
        System.out.println("  Tet position: (" + tet.x() + ", " + tet.y() + ", " + tet.z() + ")");
        System.out.println("  Search bounds min: (" + searchBounds.originX() + ", " + searchBounds.originY() + ", " + searchBounds.originZ() + ")");
        System.out.println("  Search bounds max: (" + searchBounds.extentX() + ", " + searchBounds.extentY() + ", " + searchBounds.extentZ() + ")");
        
        // Test direct tetree contents using reflection
        var map = getMap(tetree);
        System.out.println("Direct tetree contents size: " + map.size());
        map.forEach((k, v) -> {
            System.out.println("  Key: " + k + ", Value: " + v);
            Tet t = Tet.tetrahedron(k);
            System.out.println("  Tet: " + t);
        });
    }
    
    private NavigableMap<Long, String> getMap(Tetree<String> tetree) {
        // Use reflection to access the contents field
        try {
            var field = Tetree.class.getDeclaredField("contents");
            field.setAccessible(true);
            return (NavigableMap<Long, String>) field.get(tetree);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}