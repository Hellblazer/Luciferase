package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.stream.Stream;

/**
 * Helper methods for Tetree operations with workarounds for known issues
 */
public class TetreeHelper {
    
    /**
     * Direct scan approach for finding simplices within bounds
     * This is a workaround for the current issue with Tetree.boundedBy()
     */
    public static <Content> Stream<Tetree.Simplex<Content>> directScanBoundedBy(
            Tetree<Content> tetree, Spatial.aabb bounds) {
        
        // Use reflection to access the contents map
        var map = getContentsMap(tetree);
        
        return map.entrySet().stream()
            .filter(entry -> {
                Tet tet = Tet.tetrahedron(entry.getKey());
                return isTetrahedronInBounds(tet, bounds);
            })
            .map(entry -> new Tetree.Simplex<>(entry.getKey(), entry.getValue()));
    }
    
    /**
     * Check if a tetrahedron intersects with or is contained in the given bounds
     */
    private static boolean isTetrahedronInBounds(Tet tet, Spatial.aabb bounds) {
        // Get tetrahedron vertices
        Point3i[] vertices = tet.coordinates();
        
        // Check if any vertex is within bounds
        for (Point3i vertex : vertices) {
            if (vertex.x >= bounds.originX() && vertex.x <= bounds.extentX() &&
                vertex.y >= bounds.originY() && vertex.y <= bounds.extentY() &&
                vertex.z >= bounds.originZ() && vertex.z <= bounds.extentZ()) {
                return true;
            }
        }
        
        // Check if bounds center is within tetrahedron
        Point3f boundsCenter = new Point3f(
            (bounds.originX() + bounds.extentX()) / 2,
            (bounds.originY() + bounds.extentY()) / 2,
            (bounds.originZ() + bounds.extentZ()) / 2
        );
        
        return tet.contains(boundsCenter);
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
}