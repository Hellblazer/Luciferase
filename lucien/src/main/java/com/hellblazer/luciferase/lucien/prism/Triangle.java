/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.prism;

import java.util.Objects;

/**
 * 2D triangular element for prism horizontal (x,y) subdivision.
 * 
 * Triangle elements provide 4-way subdivision in the horizontal plane, forming the base component
 * of prism spatial keys. The space-filling curve is complex, adapted from t8code's triangular SFC
 * algorithm that preserves spatial locality through recursive subdivision.
 * 
 * Triangles use a type system (0 or 1) encoding the t8code/Bey <em>orientation</em>:
 * {@code Type(T) = i ⟺ T ≃ Sᵢ}. Geometrically each grid cell is split along its
 * <em>main</em> diagonal (anchor → opposite corner): a type-0 triangle is the lower-right
 * Kuhn simplex {@code {anchor, anchor+x̂, anchor+x̂+ŷ}} (≃ the reference root S0, the region
 * {@code y ≤ x}), and a type-1 triangle is the upper-left simplex
 * {@code {anchor, anchor+ŷ, anchor+x̂+ŷ}} (≃ S1). Refining a triangle of type {@code b} yields
 * children whose types form the Bey multiset {@code [b, b, b, 1-b]}: three corner-children keep
 * the orientation, one flips. See {@link #getVertices()}, {@link #computeChildType(int)} and
 * {@link #computeParentType()}.
 *
 * <p><b>Refinement is square-quadrant, not yet true Bey nesting.</b> {@link #child(int)} subdivides
 * by Morton grid-quadrant ({@code childX = 2x + bit}), and the type transition is a
 * <em>placeholder</em> that reproduces the Bey multiset shape and round-trips, but the
 * geometrically-faithful Bey interior-child (which flips orientation) is type-dependent and is
 * <em>not</em> the fixed cube-id-3 used here. True triangle-into-four Bey nesting is inseparable
 * from the tetrahedral Morton index and is deferred to RDR-009 Phase 2/Phase 7 along with the
 * SFC packing.
 *
 * <p>The coordinate system uses {@code (x, y, n)} coordinates. The auxiliary coordinate
 * {@code n} is <b>derived</b> as {@code min(x, y)}: every triangle produced by the world-coordinate
 * constructors or by {@link #child(int)}/{@link #parent()} satisfies the invariant
 * {@code n == min(x, y)}, so it is conceptually a cache of {@code min(x, y)} in the
 * construction/navigation API. It carries no term in the true tetrahedral Morton index. However,
 * the <em>current</em> {@link #consecutiveIndex()} packing is not the TM-index — it is the
 * literature-rejected positional ("semiquadcode") form that still treats {@code n} as an
 * independent coordinate dimension. That is why the field cannot be eliminated yet and the raw
 * 5-arg constructor preserves {@code n} verbatim as a low-level escape hatch (e.g. exhaustive
 * collision-freeness sweeps over arbitrary {@code n}). Dropping {@code n} from the key is deferred
 * to RDR-009 Phase 2/Phase 7, which replaces the packing with the real TM-index; until then
 * {@link #consecutiveIndex()} and {@code PrismKey.compareTo} are left unchanged.
 *
 * @author hal.hildebrand
 */
public final class Triangle {
    
    /** Maximum refinement level (same as Octree/Tetree) */
    public static final int MAX_LEVEL = 21;
    
    /** Maximum coordinate value (2^21 - 1) */
    public static final int MAX_COORDINATE = (1 << MAX_LEVEL) - 1;
    
    /** Number of children per triangle (4-way subdivision) */
    public static final int CHILDREN = 4;
    
    /** Number of triangle types (0 and 1) */
    public static final int TYPES = 2;
    
    /** Number of edges per triangle */
    public static final int EDGES = 3;

    // ── t8code 2D tetrahedral-Morton (dtri) transition tables (RDR-009 P2) ──
    // cube-id c = (x&1) | ((y&1)<<1); type b in {0,1}. Derived from Burstedde & Holke,
    // "A tetrahedral space-filling curve for non-conforming adaptive meshes" (Tables 1/2/6,
    // Fig 8) and verified by child/parent round-trip + child-contiguity. The reference root is
    // S0, the lower-right Kuhn triangle {y <= x}; these tables operate in Luciferase's
    // level-local coordinates (x,y in [0,2^level)). See T2 luciferase_rdr/009-p2-t8-dtri-2d-tables.
    /** Parent type PARENT_TYPE[cubeId][type] -> parent type (Pt, Fig 8). */
    private static final int[][] PARENT_TYPE = { { 0, 1 }, { 0, 0 }, { 1, 1 }, { 0, 1 } };
    /** Child type CHILD_TYPE[type][beyId] (Ct, Table 1): type b -> children [b,b,b,1-b]. */
    private static final int[][] CHILD_TYPE = { { 0, 0, 0, 1 }, { 1, 1, 1, 0 } };
    /** TM local index LOCAL_INDEX[type][cubeId] (Iloc, Table 6); also == getChildIndex(). */
    private static final int[][] LOCAL_INDEX = { { 0, 1, 1, 3 }, { 0, 2, 2, 3 } };
    /** TM local index -> Bey child id, sigma_b^{-1}[type][tmIndex] (Table 2). */
    private static final int[][] TM_TO_BEY = { { 0, 1, 3, 2 }, { 0, 3, 1, 2 } };
    /** Child anchor offset added to (2x,2y): CHILD_OFFSET[parentType][beyId] = {dx, dy}. */
    private static final int[][][] CHILD_OFFSET = { { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 1, 0 } },
                                                    { { 0, 0 }, { 0, 1 }, { 1, 1 }, { 0, 1 } } };

    private final byte level;          // Hierarchical level (0-21)
    private final byte type;           // Triangle type (0 or 1)
    private final int x;               // X coordinate
    private final int y;               // Y coordinate  
    private final int n;               // Derived auxiliary coordinate, cached as min(x, y) (see class javadoc)
    
    /**
     * Create a Triangle from world coordinates at a specific level.
     * 
     * @param worldX X coordinate in [0,1)
     * @param worldY Y coordinate in [0,1)
     * @param level The desired level (0-21)
     * @return Triangle containing the given point at the specified level
     * @throws IllegalArgumentException if coordinates are invalid
     */
    public static Triangle fromWorldCoordinate(float worldX, float worldY, int level) {
        // Single source of truth: delegate to fromWorldCoordinates. The prior body assigned a
        // non-Bey type from coordinate parity ((x+y)%2), derived n from min(scale-x-y, scale-1)
        // (inconsistent with the min(x,y) source of truth), and silently relocated x+y >= scale
        // points onto the diagonal — a latent data-relocation hazard. All three are removed
        // (RDR-009 Phase 1); both construction paths now agree.
        return fromWorldCoordinates(worldX, worldY, level);
    }
    
    /**
     * Create a new Triangle element.
     * 
     * @param level the hierarchical level (0-21)
     * @param type the triangle type (0 or 1)
     * @param x the x coordinate
     * @param y the y coordinate
     * @param n the auxiliary n coordinate
     * @throws IllegalArgumentException if parameters are invalid
     */
    public Triangle(int level, int type, int x, int y, int n) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be 0-" + MAX_LEVEL + ", got: " + level);
        }
        if (type < 0 || type >= TYPES) {
            throw new IllegalArgumentException("Type must be 0 or 1, got: " + type);
        }
        if (x < 0 || x > MAX_COORDINATE) {
            throw new IllegalArgumentException("X coordinate must be 0-" + MAX_COORDINATE + ", got: " + x);
        }
        if (y < 0 || y > MAX_COORDINATE) {
            throw new IllegalArgumentException("Y coordinate must be 0-" + MAX_COORDINATE + ", got: " + y);
        }
        if (n < 0 || n > MAX_COORDINATE) {
            throw new IllegalArgumentException("N coordinate must be 0-" + MAX_COORDINATE + ", got: " + n);
        }
        
        // For level 0, coordinates must be 0
        if (level == 0) {
            if (x != 0 || y != 0 || n != 0) {
                throw new IllegalArgumentException("Level 0 coordinates must all be 0");
            }
        } else {
            // For other levels, validate coordinates are reasonable (relaxed validation)
            var maxCoordForLevel = 1 << level;
            if (x >= maxCoordForLevel || y >= maxCoordForLevel || n >= maxCoordForLevel) {
                throw new IllegalArgumentException(
                    String.format("Coordinates (%d,%d,%d) exceed maximum %d for level %d", 
                                x, y, n, maxCoordForLevel - 1, level));
            }
        }
        
        this.level = (byte) level;
        this.type = (byte) type;
        this.x = x;
        this.y = y;
        this.n = n;
    }
    
    /**
     * Create a triangle from world coordinates at specified level.
     * 
     * @param worldX the world x-coordinate [0.0, 1.0)
     * @param worldY the world y-coordinate [0.0, 1.0)  
     * @param level the target level for quantization
     * @return the triangle element containing this coordinate
     */
    public static Triangle fromWorldCoordinates(float worldX, float worldY, int level) {
        if (worldX < 0.0f || worldX >= 1.0f) {
            throw new IllegalArgumentException("World X coordinate must be [0.0, 1.0), got: " + worldX);
        }
        if (worldY < 0.0f || worldY >= 1.0f) {
            throw new IllegalArgumentException("World Y coordinate must be [0.0, 1.0), got: " + worldY);
        }
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be 0-" + MAX_LEVEL + ", got: " + level);
        }

        // For level 0, return root triangle
        if (level == 0) {
            return new Triangle(0, 0, 0, 0, 0);
        }

        // The single Triangle index tiles the root S0 = lower-right half-cube {y <= x}. A point in
        // the upper-left half belongs to the S1 root, which is RDR-009 Phase 3 (Luciferase-7iu).
        if (worldY > worldX) {
            throw new IllegalArgumentException(String.format(
                "Point (%.4f, %.4f) is in the upper-left half (y > x), outside the S0 root triangle. "
                + "Full-cube coverage (the S1 root) is RDR-009 Phase 3.", worldX, worldY));
        }

        var scale = 1 << level;
        var quantX = Math.min((int) (worldX * scale), scale - 1);
        var quantY = Math.min((int) (worldY * scale), scale - 1);

        // Determine which sub-triangle of the grid cell contains the point.
        // Local coordinates within the cell [0,1) x [0,1):
        float localX = (worldX * scale) - quantX;
        float localY = (worldY * scale) - quantY;

        // t8code/Bey orientation: the cell is split along its MAIN diagonal (anchor → opposite
        // corner). The lower-right half (localY <= localX) is the type-0 (≃ S0) triangle; the
        // upper-left half (localY > localX) is the type-1 (≃ S1) triangle. This matches
        // getVertices() so that the located triangle always contains its own point.
        var type = (localY > localX) ? 1 : 0;

        // n is the derived auxiliary coordinate min(x, y) — the single source of truth.
        var n = Math.min(quantX, quantY);

        return new Triangle(level, type, quantX, quantY, n);
    }
    
    /**
     * Compute the space-filling curve index for this triangle element.
     *
     * <p>This implements a simplified version of t8code's triangular SFC algorithm.
     * The full algorithm involves complex type transitions and cube_id computation
     * that preserves spatial locality through the triangular subdivision hierarchy.
     *
     * <p>This is the t8code <em>consecutive</em> index {@code I(T)} (Burstedde &amp; Holke, §4.5,
     * eq. 55): the level-digit base-{@code 2^d}=4 number whose digits are the per-level local
     * indices {@code I_loc}. It respects the tetrahedral-Morton order and gives the locality /
     * ancestor-grouping property (Theorem 16): the four children of a triangle occupy the
     * contiguous block {@code [I(T)*4, I(T)*4 + 4)}, i.e. {@code I(child_i) = I(T)*4 + i}.
     *
     * <p>Computation walks the ancestor chain: the type of each ancestor is reconstructed from
     * the leaf type via {@link #PARENT_TYPE}, and each level contributes
     * {@code LOCAL_INDEX[type_k][cubeId_k]} as a base-4 digit (most-significant = level 1). At
     * {@code MAX_LEVEL=21} this is {@code 2*21 = 42} bits — comfortably non-negative in a signed
     * {@code long}, with no sign-flip. This replaces the prior positional packing
     * ({@code x + y*2^L + n*2^{2L} + type*2^{3L}}, the literature-rejected "semiquadcode" that
     * broke ancestor-grouping and sign-flipped at {@code type=1}); {@code n} carries no term.
     *
     * @return the consecutive SFC index {@code I(T)}
     */
    public long consecutiveIndex() {
        if (level == 0) {
            return 0L;
        }
        // Reconstruct ancestor types from the leaf type by walking up via PARENT_TYPE.
        var ell = level;
        var cubeId = new int[ell + 1];
        var ancestorType = new int[ell + 1];
        ancestorType[ell] = type;
        for (int k = ell; k >= 1; k--) {
            int xb = (x >>> (ell - k)) & 1;
            int yb = (y >>> (ell - k)) & 1;
            cubeId[k] = xb | (yb << 1);
            if (k > 1) {
                ancestorType[k - 1] = PARENT_TYPE[cubeId[k]][ancestorType[k]];
            }
        }
        // Assemble the base-4 consecutive index, most-significant digit = level 1.
        long index = 0L;
        for (int k = 1; k <= ell; k++) {
            index = (index << 2) | LOCAL_INDEX[ancestorType[k]][cubeId[k]];
        }
        return index;
    }
    
    
    /**
     * Get the parent triangle in the hierarchy.
     * 
     * @return the parent triangle, or null if this is level 0
     */
    public Triangle parent() {
        if (level == 0) {
            return null;
        }
        
        // True t8code dtri parent (Algorithm 4.3): clear the finest coordinate bit and recover
        // the parent's type from this triangle's cube-id and type via PARENT_TYPE (Pt).
        var cubeId = (x & 1) | ((y & 1) << 1);
        var parentX = x >>> 1;
        var parentY = y >>> 1;
        var parentType = PARENT_TYPE[cubeId][type];
        // n remains the derived auxiliary coordinate min(x,y) (vestigial; not in the SFC index).
        return new Triangle(level - 1, parentType, parentX, parentY, Math.min(parentX, parentY));
    }
    
    /**
     * Get a child triangle by child index.
     * 
     * @param childIndex the child index (0-3)
     * @return the child triangle
     * @throws IllegalArgumentException if childIndex is invalid or triangle is at max level
     */
    public Triangle child(int childIndex) {
        if (childIndex < 0 || childIndex >= CHILDREN) {
            throw new IllegalArgumentException("Child index must be 0-3, got: " + childIndex);
        }
        if (level >= MAX_LEVEL) {
            throw new IllegalArgumentException("Cannot get child of triangle at maximum level " + MAX_LEVEL);
        }
        
        // True t8code dtri child in tetrahedral-Morton order (Algorithms 4.4/4.5): the TM local
        // index is mapped to a Bey child id, which selects the anchor offset and child type.
        // The four children carry distinct local indices {0,1,2,3}, giving contiguous indices.
        var beyId = TM_TO_BEY[type][childIndex];
        var offset = CHILD_OFFSET[type][beyId];
        var childX = (x << 1) + offset[0];
        var childY = (y << 1) + offset[1];
        var childType = CHILD_TYPE[type][beyId];
        // n remains the derived auxiliary coordinate min(x,y) (vestigial; not in the SFC index).
        return new Triangle(level + 1, childType, childX, childY, Math.min(childX, childY));
    }
    
    /**
     * Get the child index of this triangle relative to its parent.
     * 
     * @return the child index (0-3), or -1 if this is level 0
     */
    public int getChildIndex() {
        if (level == 0) {
            return -1;
        }
        // Tetrahedral-Morton local index of this triangle within its parent: a function of the
        // cube-id and type (LOCAL_INDEX = Iloc). child(i).getChildIndex() == i for all i.
        var cubeId = (x & 1) | ((y & 1) << 1);
        return LOCAL_INDEX[type][cubeId];
    }
    
    /**
     * Test if this triangle contains the given world coordinates.
     * 
     * @param worldX the world x-coordinate [0.0, 1.0)
     * @param worldY the world y-coordinate [0.0, 1.0)
     * @return true if the coordinates are contained in this triangle
     */
    public boolean contains(float worldX, float worldY) {
        if (worldX < 0.0f || worldX >= 1.0f || worldY < 0.0f || worldY >= 1.0f) {
            return false;
        }
        
        // Level-0 root covers the entire [0,1)² square. This is intentionally retained as a
        // half-cube placeholder: with the main-diagonal geometry established here (P1), a strict
        // type-0 root would only contain the lower-right half {y ≤ x}, leaving upper-left points
        // unreachable until the S1 root is added. Removing this special case is therefore coupled
        // to the two-prism cover — RDR-009 Phase 3 (Luciferase-7iu) "remove/specialize per root".
        // TODO(RDR-009 P3): replace with type-based triangular containment once the S1 root exists.
        if (level == 0) {
            return true; // Already passed the [0,1) range check above
        }
        
        // Get the world bounds for this triangle
        var bounds = getWorldBounds();
        float minX = bounds[0];
        float minY = bounds[1];
        float maxX = bounds[2];
        float maxY = bounds[3];
        
        // First check if point is within bounding box
        if (worldX < minX || worldX > maxX || worldY < minY || worldY > maxY) {
            return false;
        }
        
        // Use proper geometric test based on triangle type and vertices
        float[][] vertices = getVertices();
        
        // Use barycentric coordinates or point-in-triangle test
        return isPointInTriangle(worldX, worldY, vertices[0], vertices[1], vertices[2]);
    }
    
    /**
     * Test if a point is inside a triangle using barycentric coordinates.
     * 
     * @param px point x coordinate
     * @param py point y coordinate
     * @param v1 triangle vertex 1
     * @param v2 triangle vertex 2
     * @param v3 triangle vertex 3
     * @return true if point is inside triangle
     */
    private boolean isPointInTriangle(float px, float py, float[] v1, float[] v2, float[] v3) {
        // Calculate barycentric coordinates
        float denom = (v2[1] - v3[1]) * (v1[0] - v3[0]) + (v3[0] - v2[0]) * (v1[1] - v3[1]);
        
        if (Math.abs(denom) < 1e-10f) {
            // Degenerate triangle
            return false;
        }
        
        float a = ((v2[1] - v3[1]) * (px - v3[0]) + (v3[0] - v2[0]) * (py - v3[1])) / denom;
        float b = ((v3[1] - v1[1]) * (px - v3[0]) + (v1[0] - v3[0]) * (py - v3[1])) / denom;
        float c = 1 - a - b;
        
        // Point is inside if all barycentric coordinates are non-negative
        return a >= 0 && b >= 0 && c >= 0;
    }
    
    /**
     * Find the neighbors of this triangle on its three edges.
     * 
     * @return array of 3 neighbor triangles (may contain nulls for boundary edges)
     */
    public Triangle[] neighbors() {
        var neighbors = new Triangle[EDGES];
        
        // Simplified same-type neighbor finding (the cross-diagonal S0↔S1 crossing logic is
        // RDR-009 Phase 4); n is recomputed as min(x,y) for each neighbor's coordinates.
        // Edge 0: right neighbor
        if (x + 1 < (1 << level)) {
            neighbors[0] = new Triangle(level, type, x + 1, y, Math.min(x + 1, y));
        }

        // Edge 1: top neighbor
        if (y + 1 < (1 << level)) {
            neighbors[1] = new Triangle(level, type, x, y + 1, Math.min(x, y + 1));
        }

        // Edge 2: diagonal neighbor (simplified)
        if (x > 0 && y > 0) {
            neighbors[2] = new Triangle(level, type, x - 1, y - 1, Math.min(x - 1, y - 1));
        }

        return neighbors;
    }
    
    /**
     * Find the neighbor of this triangle across a specific edge.
     * 
     * @param edge the edge index (0-2)
     * @return the neighbor triangle, or null if at boundary
     * @throws IllegalArgumentException if edge index is invalid
     */
    public Triangle neighbor(int edge) {
        if (edge < 0 || edge >= EDGES) {
            throw new IllegalArgumentException("Edge index must be 0-2, got: " + edge);
        }
        
        // Simplified same-type neighbor finding (cross-diagonal crossing is RDR-009 Phase 4);
        // n is recomputed as min(x,y) for the neighbor's coordinates.
        switch (edge) {
            case 0: // right neighbor
                if (x + 1 < (1 << level)) {
                    return new Triangle(level, type, x + 1, y, Math.min(x + 1, y));
                }
                break;
            case 1: // top neighbor
                if (y + 1 < (1 << level)) {
                    return new Triangle(level, type, x, y + 1, Math.min(x, y + 1));
                }
                break;
            case 2: // diagonal neighbor (simplified)
                if (x > 0 && y > 0) {
                    return new Triangle(level, type, x - 1, y - 1, Math.min(x - 1, y - 1));
                }
                break;
        }
        
        return null; // At boundary
    }
    
    /**
     * Get the world coordinate range for this triangle.
     * 
     * @return array of [minX, minY, maxX, maxY] coordinates in world space
     */
    public float[] getWorldBounds() {
        var scale = 1.0f / (1 << level);
        var minX = x * scale;
        var minY = y * scale;
        return new float[]{minX, minY, minX + scale, minY + scale};
    }
    
    /**
     * Get the centroid world coordinates of this triangle.
     * 
     * @return array of [centerX, centerY] coordinates in world space
     */
    public float[] getCentroidWorldCoordinates() {
        var scale = 1.0f / (1 << level);
        var centerX = x * scale + scale * 0.5f;
        var centerY = y * scale + scale * 0.5f;
        return new float[]{centerX, centerY};
    }
    
    // Accessors
    
    public byte getLevel() {
        return level;
    }
    
    public byte getType() {
        return type;
    }
    
    public int getX() {
        return x;
    }
    
    public int getY() {
        return y;
    }
    
    public int getN() {
        return n;
    }
    
    // Object methods
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Triangle other)) return false;
        // Identity is (level, type, x, y) — the t8 Tet-id. n is a derived auxiliary coordinate
        // (min(x,y)) carrying no independent information, so it is NOT part of identity; including
        // it would make equals inconsistent with consecutiveIndex()/PrismKey.compareTo (RDR-009 P2).
        return level == other.level && type == other.type && x == other.x && y == other.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, type, x, y);
    }
    
    @Override
    public String toString() {
        var centroid = getCentroidWorldCoordinates();
        return String.format("Triangle(level=%d, type=%d, coords=(%d,%d,%d), center=(%.4f,%.4f))", 
                           level, type, x, y, n, centroid[0], centroid[1]);
    }
    
    /**
     * Get the vertices of this triangle.
     * 
     * @return array of 3 vertices as [x,y] coordinates
     */
    public float[][] getVertices() {
        var bounds = getWorldBounds();
        float minX = bounds[0];
        float minY = bounds[1];
        float maxX = bounds[2];
        float maxY = bounds[3];
        
        // t8code/Bey orientation: split the cell along its MAIN diagonal (minX,minY)→(maxX,maxY).
        if (type == 0) {
            // Lower-right Kuhn simplex {anchor, anchor+x̂, anchor+x̂+ŷ} (the S0 orientation, y ≤ x).
            return new float[][]{
                {minX, minY},
                {maxX, minY},
                {maxX, maxY}
            };
        } else {
            // Upper-left Kuhn simplex {anchor, anchor+ŷ, anchor+x̂+ŷ} (the S1 orientation, x ≤ y).
            return new float[][]{
                {minX, minY},
                {minX, maxY},
                {maxX, maxY}
            };
        }
    }
    
    
    /**
     * Set the bounds of this triangle (stub for API compatibility).
     * Note: This is a simplified implementation.
     * 
     * @param minX minimum X
     * @param minY minimum Y  
     * @param maxX maximum X
     * @param maxY maximum Y
     */
    public void setBounds(float minX, float minY, float maxX, float maxY) {
        // This is a stub - in a full implementation, this would
        // update the triangle's coordinates based on the bounds
    }
}