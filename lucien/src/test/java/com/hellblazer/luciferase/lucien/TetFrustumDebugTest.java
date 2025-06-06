package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class TetFrustumDebugTest {
    
    @Test
    void debugTetreeInsertion() {
        TreeMap<Long, String> contents = new TreeMap<>();
        Tetree<String> tetree = new Tetree<>(contents);
        
        // Insert a single point
        Point3f testPoint = new Point3f(500.0f, 500.0f, 500.0f);
        long index = tetree.insert(testPoint, (byte)10, "Test");
        
        System.out.println("Inserted at index: " + index);
        System.out.println("Contents size: " + contents.size());
        
        // Get the tetrahedron that was created
        var tet = Tet.tetrahedron(index);
        System.out.println("Tetrahedron vertices:");
        var coords = tet.coordinates();
        for (int i = 0; i < coords.length; i++) {
            System.out.println("  Vertex " + i + ": " + coords[i]);
        }
        
        // Test with different AABB sizes
        testAABB(tetree, new Spatial.aabb(400f, 400f, 400f, 600f, 600f, 600f), "Small AABB around point");
        testAABB(tetree, new Spatial.aabb(0f, 0f, 0f, 1000f, 1000f, 1000f), "Large AABB");
        testAABB(tetree, new Spatial.aabb(0f, 0f, 0f, 3000f, 3000f, 3000f), "Very large AABB");
        
        // Test with AABB that exactly matches tetrahedron bounds
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        for (var coord : coords) {
            minX = Math.min(minX, coord.x);
            minY = Math.min(minY, coord.y);
            minZ = Math.min(minZ, coord.z);
            maxX = Math.max(maxX, coord.x);
            maxY = Math.max(maxY, coord.y);
            maxZ = Math.max(maxZ, coord.z);
        }
        testAABB(tetree, new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ), "Exact tetrahedron AABB");
    }
    
    private void testAABB(Tetree<String> tetree, Spatial.aabb bounds, String description) {
        System.out.println("\nTesting " + description + ": " + bounds);
        var results = tetree.bounding(bounds).collect(Collectors.toList());
        System.out.println("Found " + results.size() + " results with tetree.bounding()");
        
        // Also test with TetreeHelper
        var helperResults = TetreeHelper.directScanBoundedBy(tetree, bounds).collect(Collectors.toList());
        System.out.println("Found " + helperResults.size() + " results with TetreeHelper.directScanBoundedBy()");
        
        // Debug the tetrahedron containing logic
        if (helperResults.size() == 0) {
            System.out.println("Debug: checking why TetreeHelper found no results");
            // Check the first tetrahedron manually
            var map = getContentsMap(tetree);
            if (!map.isEmpty()) {
                var firstEntry = map.firstEntry();
                var tet = Tet.tetrahedron(firstEntry.getKey());
                System.out.println("First tet vertices:");
                var coords = tet.coordinates();
                for (int i = 0; i < coords.length; i++) {
                    System.out.println("  Vertex " + i + ": " + coords[i]);
                }
                
                // Check bounds center
                Point3f boundsCenter = new Point3f(
                    (bounds.originX() + bounds.extentX()) / 2,
                    (bounds.originY() + bounds.extentY()) / 2,
                    (bounds.originZ() + bounds.extentZ()) / 2
                );
                System.out.println("Bounds center: " + boundsCenter);
                System.out.println("Tet contains bounds center: " + tet.contains(boundsCenter));
                
                // Check if any vertex is in bounds
                boolean anyVertexInBounds = false;
                for (var vertex : coords) {
                    boolean inBounds = vertex.x >= bounds.originX() && vertex.x <= bounds.extentX() &&
                                     vertex.y >= bounds.originY() && vertex.y <= bounds.extentY() &&
                                     vertex.z >= bounds.originZ() && vertex.z <= bounds.extentZ();
                    if (inBounds) {
                        anyVertexInBounds = true;
                        System.out.println("Vertex " + vertex + " is in bounds");
                    }
                }
                System.out.println("Any vertex in bounds: " + anyVertexInBounds);
            }
        }
        
        for (var result : results) {
            System.out.println("  Result: index=" + result.index() + ", content=" + result.cell());
        }
    }
    
    @SuppressWarnings("unchecked")
    private static <Content> java.util.NavigableMap<Long, Content> getContentsMap(Tetree<Content> tetree) {
        try {
            var field = Tetree.class.getDeclaredField("contents");
            field.setAccessible(true);
            return (java.util.NavigableMap<Long, Content>) field.get(tetree);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access Tetree contents", e);
        }
    }
    
    @Test
    void testFrustumWithSinglePoint() {
        Tetree<String> tetree = new Tetree<>(new TreeMap<>());
        
        // Insert a single point that should be inside the frustum
        Point3f testPoint = new Point3f(500.0f, 500.0f, 500.0f);
        tetree.insert(testPoint, (byte)10, "TestPoint");
        
        // Create a frustum that should contain this point
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );
        
        // Check if the point is inside the frustum
        System.out.println("Test point " + testPoint + " is inside frustum: " + frustum.containsPoint(testPoint));
        
        // Get frustum bounds
        var frustumBounds = TetFrustumCullingSearch.computeFrustumBounds(frustum);
        System.out.println("Frustum bounds: " + frustumBounds);
        
        // Query tetree
        var simplicies = tetree.bounding(frustumBounds).collect(Collectors.toList());
        System.out.println("Bounding query found " + simplicies.size() + " simplicies");
        
        // Run the actual search
        var results = TetFrustumCullingSearch.frustumCulledAll(
            frustum, tetree, cameraPosition, 
            TetrahedralSearchBase.SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        System.out.println("Frustum culling found " + results.size() + " results");
        
        assertFalse(results.isEmpty(), "Should find at least one result");
    }
}