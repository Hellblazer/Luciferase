package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.portal.Tetrahedral;
import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import java.util.List;

/**
 * Represents bubble spatial extent using native tetrahedral model.
 * <p>
 * CRITICAL ARCHITECTURE:
 * - Uses TetreeKey + RDGCS coordinates (NOT AABB)
 * - Centroid formula: (v0+v1+v2+v3)/4 (NOT cube center)
 * - RDGCS transformations via Tetrahedral.toRDG() and toCartesian()
 * <p>
 * This class represents spatial bounds using S0-S5 characteristic tetrahedra
 * from the tetrahedral subdivision model. It maintains bounds in both the
 * tetrahedral space (TetreeKey) and RDGCS coordinate system.
 *
 * @author hal.hildebrand
 */
public final class BubbleBounds {

    private final TetreeKey<?> rootKey;
    private final Point3i rdgMin;
    private final Point3i rdgMax;
    private final Tetrahedral coordSystem;

    /**
     * Create BubbleBounds from tetrahedral and RDGCS coordinates.
     *
     * @param rootKey  The root TetreeKey for this bounds
     * @param rdgMin   Minimum RDGCS coordinates
     * @param rdgMax   Maximum RDGCS coordinates
     */
    private BubbleBounds(TetreeKey<?> rootKey, Point3i rdgMin, Point3i rdgMax) {
        this.rootKey = rootKey;
        this.rdgMin = rdgMin;
        this.rdgMax = rdgMax;
        this.coordSystem = new Tetrahedral();
    }

    /**
     * Create bounds from a TetreeKey.
     * <p>
     * Computes RDGCS min/max from the tetrahedron vertices.
     *
     * @param key The TetreeKey to create bounds from
     * @return BubbleBounds enclosing the tetrahedron
     */
    public static BubbleBounds fromTetreeKey(TetreeKey<?> key) {
        var tet = key.toTet();
        var coords = tet.coordinates();

        // Compute RDGCS coordinates for all 4 vertices
        var tetrahedral = new Tetrahedral();
        var rdgCoords = new Point3i[4];
        for (int i = 0; i < 4; i++) {
            rdgCoords[i] = tetrahedral.toRDG(new Point3f(coords[i].x, coords[i].y, coords[i].z));
        }

        // Find min/max in RDGCS space
        var rdgMin = new Point3i(
            Math.min(Math.min(rdgCoords[0].x, rdgCoords[1].x),
                    Math.min(rdgCoords[2].x, rdgCoords[3].x)),
            Math.min(Math.min(rdgCoords[0].y, rdgCoords[1].y),
                    Math.min(rdgCoords[2].y, rdgCoords[3].y)),
            Math.min(Math.min(rdgCoords[0].z, rdgCoords[1].z),
                    Math.min(rdgCoords[2].z, rdgCoords[3].z))
        );

        var rdgMax = new Point3i(
            Math.max(Math.max(rdgCoords[0].x, rdgCoords[1].x),
                    Math.max(rdgCoords[2].x, rdgCoords[3].x)),
            Math.max(Math.max(rdgCoords[0].y, rdgCoords[1].y),
                    Math.max(rdgCoords[2].y, rdgCoords[3].y)),
            Math.max(Math.max(rdgCoords[0].z, rdgCoords[1].z),
                    Math.max(rdgCoords[2].z, rdgCoords[3].z))
        );

        return new BubbleBounds(key, rdgMin, rdgMax);
    }

    /**
     * Create bounds from entity positions.
     * <p>
     * Finds the smallest tetrahedron (at level 10) that encompasses all positions.
     *
     * @param positions List of entity positions
     * @return BubbleBounds enclosing all positions
     */
    public static BubbleBounds fromEntityPositions(List<Point3f> positions) {
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("Cannot create bounds from empty position list");
        }

        var tetrahedral = new Tetrahedral();

        // Convert all positions to RDGCS
        var rdgPositions = positions.stream()
                                   .map(tetrahedral::toRDG)
                                   .toList();

        // Find RDGCS min/max
        int minX = rdgPositions.stream().mapToInt(p -> p.x).min().orElseThrow();
        int minY = rdgPositions.stream().mapToInt(p -> p.y).min().orElseThrow();
        int minZ = rdgPositions.stream().mapToInt(p -> p.z).min().orElseThrow();
        int maxX = rdgPositions.stream().mapToInt(p -> p.x).max().orElseThrow();
        int maxY = rdgPositions.stream().mapToInt(p -> p.y).max().orElseThrow();
        int maxZ = rdgPositions.stream().mapToInt(p -> p.z).max().orElseThrow();

        var rdgMin = new Point3i(minX, minY, minZ);
        var rdgMax = new Point3i(maxX, maxY, maxZ);

        // Find centroid in Cartesian space
        float cx = (float) positions.stream().mapToDouble(p -> p.x).average().orElseThrow();
        float cy = (float) positions.stream().mapToDouble(p -> p.y).average().orElseThrow();
        float cz = (float) positions.stream().mapToDouble(p -> p.z).average().orElseThrow();

        // Locate tetrahedron containing centroid at level 10
        var tet = Tet.locatePointBeyRefinementFromRoot(cx, cy, cz, (byte) 10);
        TetreeKey<?> key;

        if (tet != null) {
            key = tet.tmIndex();
        } else {
            // Fallback to root tetrahedron if position is outside valid range
            key = TetreeKey.create((byte) 10, 0L, 0L);
        }

        return new BubbleBounds(key, rdgMin, rdgMax);
    }

    /**
     * Create bounds encompassing two existing bounds.
     *
     * @param a First bounds
     * @param b Second bounds
     * @return BubbleBounds encompassing both inputs
     */
    public static BubbleBounds encompassing(BubbleBounds a, BubbleBounds b) {
        // Union of RDGCS bounds
        var rdgMin = new Point3i(
            Math.min(a.rdgMin.x, b.rdgMin.x),
            Math.min(a.rdgMin.y, b.rdgMin.y),
            Math.min(a.rdgMin.z, b.rdgMin.z)
        );

        var rdgMax = new Point3i(
            Math.max(a.rdgMax.x, b.rdgMax.x),
            Math.max(a.rdgMax.y, b.rdgMax.y),
            Math.max(a.rdgMax.z, b.rdgMax.z)
        );

        // Use the lower-level (larger) tetrahedron as root
        var key = a.level() <= b.level() ? a.rootKey : b.rootKey;

        return new BubbleBounds(key, rdgMin, rdgMax);
    }

    /**
     * Convert Cartesian coordinates to RDGCS.
     *
     * @param cartesian Cartesian position
     * @return RDGCS coordinates
     */
    public Point3i toRDG(Tuple3f cartesian) {
        return coordSystem.toRDG(cartesian);
    }

    /**
     * Convert RDGCS coordinates to Cartesian.
     *
     * @param rdg RDGCS coordinates
     * @return Cartesian position
     */
    public Point3D toCartesian(Tuple3i rdg) {
        return coordSystem.toCartesian(rdg);
    }

    /**
     * Check if a Cartesian point is contained in these bounds.
     * <p>
     * Uses RDGCS bounding box containment. The rootKey tetrahedron is used
     * as a reference for the coordinate system, but bounds can grow beyond it.
     *
     * @param position Cartesian position
     * @return true if position is within bounds
     */
    public boolean contains(Point3f position) {
        var rdg = toRDG(position);
        return contains(rdg);
    }

    /**
     * Check if an RDGCS point is contained in these bounds.
     * <p>
     * This is a bounding box test, not exact tetrahedral containment.
     *
     * @param rdgPosition RDGCS coordinates
     * @return true if position is within RDGCS bounding box
     */
    public boolean contains(Point3i rdgPosition) {
        return rdgPosition.x >= rdgMin.x && rdgPosition.x <= rdgMax.x &&
               rdgPosition.y >= rdgMin.y && rdgPosition.y <= rdgMax.y &&
               rdgPosition.z >= rdgMin.z && rdgPosition.z <= rdgMax.z;
    }

    /**
     * Check if these bounds overlap with another bounds.
     *
     * @param other Other bounds to check
     * @return true if bounds overlap
     */
    public boolean overlaps(BubbleBounds other) {
        // RDGCS AABB overlap test
        return !(rdgMax.x < other.rdgMin.x || rdgMin.x > other.rdgMax.x ||
                rdgMax.y < other.rdgMin.y || rdgMin.y > other.rdgMax.y ||
                rdgMax.z < other.rdgMin.z || rdgMin.z > other.rdgMax.z);
    }

    /**
     * Expand bounds to include a new position.
     *
     * @param position Position to include
     * @return New BubbleBounds including the position
     */
    public BubbleBounds expand(Point3f position) {
        var rdg = toRDG(position);

        var newRdgMin = new Point3i(
            Math.min(rdgMin.x, rdg.x),
            Math.min(rdgMin.y, rdg.y),
            Math.min(rdgMin.z, rdg.z)
        );

        var newRdgMax = new Point3i(
            Math.max(rdgMax.x, rdg.x),
            Math.max(rdgMax.y, rdg.y),
            Math.max(rdgMax.z, rdg.z)
        );

        return new BubbleBounds(rootKey, newRdgMin, newRdgMax);
    }

    /**
     * Recalculate bounds from a new set of entity positions.
     *
     * @param entityPositions New entity positions
     * @return New BubbleBounds computed from positions
     */
    public BubbleBounds recalculate(List<Point3f> entityPositions) {
        return fromEntityPositions(entityPositions);
    }

    /**
     * Calculate the tetrahedral centroid.
     * <p>
     * CRITICAL: Uses (v0+v1+v2+v3)/4, NOT cube center formula.
     *
     * @return Centroid of the tetrahedron
     */
    public Point3D centroid() {
        var tet = rootKey.toTet();
        var coords = tet.coordinates();

        // Tetrahedral centroid: average of 4 vertices
        double cx = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0;
        double cy = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0;
        double cz = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0;

        return new Point3D(cx, cy, cz);
    }

    /**
     * Get the root TetreeKey.
     *
     * @return Root TetreeKey for this bounds
     */
    public TetreeKey<?> rootKey() {
        return rootKey;
    }

    /**
     * Get RDGCS minimum coordinates.
     *
     * @return Minimum RDGCS coordinates
     */
    public Point3i rdgMin() {
        return rdgMin;
    }

    /**
     * Get RDGCS maximum coordinates.
     *
     * @return Maximum RDGCS coordinates
     */
    public Point3i rdgMax() {
        return rdgMax;
    }

    /**
     * Get the refinement level.
     *
     * @return Level of the root tetrahedron
     */
    public byte level() {
        return rootKey.toTet().l();
    }

    /**
     * Get the tetrahedral type (S0-S5).
     *
     * @return Type of the root tetrahedron (0-5)
     */
    public byte type() {
        return rootKey.toTet().type();
    }

    @Override
    public String toString() {
        return String.format("BubbleBounds{level=%d, type=S%d, rdg=[%s,%s]}",
                           level(), type(), rdgMin, rdgMax);
    }
}
