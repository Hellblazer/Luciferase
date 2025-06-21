package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Simplex;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Helper methods for Tetree operations with workarounds for known issues.
 */
public class TetreeHelper {

    /**
     * Direct scan approach for finding simplices within bounds. This method works with Tetree to find all entities
     * within the given bounds.
     *
     * @param tetree the Tetree to scan
     * @param bounds the bounding box to search within
     * @return stream of simplices containing entities within the bounds
     */
    public static <ID extends EntityID, Content> Stream<Simplex<Content>> directScanBoundedBy(
    Tetree<ID, Content> tetree, Spatial.aabb bounds) {

        // Convert AABB to Cube for the entitiesInRegion method
        Spatial.Cube region = new Spatial.Cube(bounds.originX(), bounds.originY(), bounds.originZ(),
                                               Math.max(bounds.extentX() - bounds.originX(),
                                                        Math.max(bounds.extentY() - bounds.originY(),
                                                                 bounds.extentZ() - bounds.originZ())));

        // Get entities in the region
        List<ID> entitiesInRegion = tetree.entitiesInRegion(region);

        // Convert to simplices by getting entity content and positions
        List<Simplex<Content>> simplices = new ArrayList<>();
        for (ID entityId : entitiesInRegion) {
            Content content = tetree.getEntity(entityId);
            if (content != null) {
                Point3f position = tetree.getEntityPosition(entityId);
                if (position != null) {
                    // Find the tetrahedral index for this position
                    // We use a default level here - adjust as needed
                    byte level = 10;
                    Tet tet = locate(position, level);
                    simplices.add(new Simplex<>(tet.index(), content));
                }
            }
        }

        return simplices.stream();
    }

    /**
     * Alternative method that uses the SpatialNode stream from Tetree
     *
     * @param tetree the Tetree to scan
     * @param bounds the bounding box to search within
     * @return stream of spatial nodes within the bounds
     */
    public static <ID extends EntityID, Content> Stream<SpatialNode<ID>> directScanNodes(Tetree<ID, Content> tetree,
                                                                                         Spatial.aabb bounds) {

        // Convert AABB bounds to a Spatial volume that Tetree can use
        Spatial volume = new Spatial.aabb(bounds.originX(), bounds.originY(), bounds.originZ(), bounds.extentX(),
                                          bounds.extentY(), bounds.extentZ());

        // Use the boundedBy method to get nodes within the volume
        return tetree.boundedBy(volume);
    }

    /**
     * Check if a tetrahedron intersects with or is contained in the given bounds
     */
    private static boolean isTetrahedronInBounds(Tet tet, Spatial.aabb bounds) {
        // Get tetrahedron vertices
        Point3i[] vertices = tet.coordinates();

        // Check if any vertex is within bounds
        for (Point3i vertex : vertices) {
            if (vertex.x >= bounds.originX() && vertex.x <= bounds.extentX() && vertex.y >= bounds.originY()
            && vertex.y <= bounds.extentY() && vertex.z >= bounds.originZ() && vertex.z <= bounds.extentZ()) {
                return true;
            }
        }

        // Check if bounds center is within tetrahedron
        Point3f boundsCenter = new Point3f((bounds.originX() + bounds.extentX()) / 2,
                                           (bounds.originY() + bounds.extentY()) / 2,
                                           (bounds.originZ() + bounds.extentZ()) / 2);

        return tet.contains(boundsCenter);
    }

    /**
     * Locate the tetrahedron containing a point at a given level (Copied from Tetree for convenience)
     */
    private static Tet locate(Point3f point, byte level) {
        var length = Constants.lengthAtLevel(level);
        var c0 = new Point3i((int) (Math.floor(point.x / length) * length),
                             (int) (Math.floor(point.y / length) * length),
                             (int) (Math.floor(point.z / length) * length));

        // Test all 6 tetrahedron types at this grid location to find which one contains the point
        for (byte type = 0; type < 6; type++) {
            var testTet = new Tet(c0.x, c0.y, c0.z, level, type);
            if (testTet.contains(point)) {
                return testTet;
            }
        }

        // Fallback: if no tetrahedron contains the point (shouldn't happen), return type 0
        // This could happen due to floating-point precision issues at boundaries
        return new Tet(c0.x, c0.y, c0.z, level, (byte) 0);
    }
}
