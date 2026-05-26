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
 * children whose types form the Bey multiset {@code [b, b, b, 1-b]}: three children keep the
 * orientation, one flips. See {@link #getVertices()} and {@link #child(int)}.
 *
 * <p><b>Indexing (RDR-009 P2): the real tetrahedral-Morton model.</b> {@link #child(int)},
 * {@link #parent()} and {@link #getChildIndex()} implement true Bey refinement via the t8code 2D
 * transition tables (parent-type Pt, child-type Ct, local-index Iloc, the σ permutation, and the
 * child anchor offsets). {@link #consecutiveIndex()} is the t8 <em>consecutive</em> index
 * {@code I(T)} — the per-level base-4 string of local indices — so children are contiguous
 * ({@code I(child_i) = I(T)*4 + i}) and the locality / ancestor-grouping property holds.
 *
 * <p><b>Two-prism full-cube cover (RDR-009 P3).</b> A triangle belongs to one of two root halves
 * (see {@link #getHalf()}): S0 (half 0) is the lower-right Kuhn triangle {@code y ≤ x}; S1 (half 1)
 * is the upper-left root {@code y ≥ x}, the reflection of S0 across the main diagonal {@code y = x}.
 * Together they tile the full square. A half-1 triangle stores its anchor in the S0 frame (so the
 * stored {@code x, y} always satisfy {@code y ≤ x}) and reuses all of the indexing/refinement
 * logic above; only the geometry ({@link #getVertices()}, {@link #getWorldBounds()},
 * {@link #contains}) reflects across {@code y = x}. {@link #fromWorldCoordinates} routes
 * {@code y > x} points to S1 (the diagonal {@code y == x} belongs to S0, so coverage has no gaps
 * or double-counting). The consecutive index is per-half; {@code PrismKey.compareTo} orders by
 * half first, so the two roots form contiguous SFC blocks.
 *
 * <p>The coordinate system stores {@code (level, type, x, y)} — the t8 Tet-id — which is the
 * identity used by {@link #equals(Object)}/{@link #hashCode()} and the SFC index. The legacy
 * {@code n} field is the derived auxiliary coordinate {@code min(x, y)}; it carries no term in the
 * consecutive index and is excluded from identity. It is retained only so the 5-arg constructor
 * signature is unchanged; {@link #getN()} is deprecated and the field is slated for removal in
 * RDR-009 Phase 7.
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
    private final byte half;           // Root half: 0 = S0 (lower-right y<=x), 1 = S1 (reflected upper-left) — RDR-009 P3

    // Lazily-computed cache of consecutiveIndex(). Triangle is immutable and the index is a
    // deterministic function of (level, type, x, y), so a benign race is harmless; volatile
    // avoids a torn read of the long. -1 is the "not yet computed" sentinel (the index is >= 0).
    private volatile long cachedIndex = -1L;
    
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
        this(level, type, x, y, n, 0);
    }

    /**
     * Create a new Triangle element in the given root half.
     *
     * @param level the hierarchical level (0-21)
     * @param type the triangle type (0 or 1)
     * @param x the x coordinate (in the S0 frame — see {@link #getHalf()})
     * @param y the y coordinate (in the S0 frame)
     * @param n the auxiliary n coordinate
     * @param half the root half: 0 = S0 (lower-right {@code y <= x}), 1 = S1 (reflected upper-left)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public Triangle(int level, int type, int x, int y, int n, int half) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be 0-" + MAX_LEVEL + ", got: " + level);
        }
        if (type < 0 || type >= TYPES) {
            throw new IllegalArgumentException("Type must be 0 or 1, got: " + type);
        }
        if (half < 0 || half >= TYPES) {
            throw new IllegalArgumentException("Half must be 0 (S0) or 1 (S1), got: " + half);
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
        this.half = (byte) half;
    }

    /**
     * The level-0 root of the S1 family — the upper-left Kuhn triangle {@code y >= x}, the
     * reflection of the S0 root across the main diagonal. Together with {@code new Triangle(0,...)}
     * (the S0 root) the two roots tile the full square (RDR-009 P3).
     *
     * @return the S1 root triangle
     */
    public static Triangle rootS1() {
        return new Triangle(0, 0, 0, 0, 0, 1);
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

        // Two-prism cover (RDR-009 P3): the square is split along the main diagonal y = x into
        // two root simplices. y <= x is the S0 root (half 0); y > x is the S1 root (half 1), the
        // reflection of S0 across y = x. For S1 we reflect the point into the S0 frame, locate the
        // S0 leaf there, and tag it half 1 — so all of the t8 index/refinement logic is reused and
        // S0 keys are unchanged. The diagonal y == x belongs to S0 (no double-counting).
        int half = (worldY > worldX) ? 1 : 0;
        float fx = (half == 1) ? worldY : worldX; // S0-frame coords: fx >= fy
        float fy = (half == 1) ? worldX : worldY;

        // For level 0, return the root triangle of the appropriate half.
        if (level == 0) {
            return new Triangle(0, 0, 0, 0, 0, half);
        }

        var scale = 1 << level;
        var quantX = Math.min((int) (fx * scale), scale - 1);
        var quantY = Math.min((int) (fy * scale), scale - 1);

        // t8code/Bey orientation within the S0 frame: the cell is split along its main diagonal;
        // the lower-right half (localY <= localX) is type 0, the upper-left half is type 1.
        float localX = (fx * scale) - quantX;
        float localY = (fy * scale) - quantY;
        var type = (localY > localX) ? 1 : 0;

        // n is the derived auxiliary coordinate min(x, y) — the single source of truth.
        var n = Math.min(quantX, quantY);

        return new Triangle(level, type, quantX, quantY, n, half);
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
        var cached = cachedIndex;
        if (cached >= 0L) {
            return cached;
        }
        // Single allocation-free backward pass (finest level first): walk the ancestor chain,
        // contributing each level's base-4 local index LOCAL_INDEX[type_k][cubeId_k] at place
        // 4^(level-k), and reconstruct the next-coarser ancestor type via PARENT_TYPE. (compareTo
        // is on the ConcurrentSkipListMap hot path, so this avoids per-call heap allocation; the
        // result is then cached since Triangle is immutable.)
        long index = 0L;
        int ancestorType = type;
        long place = 1L;
        for (int k = level; k >= 1; k--) {
            int shift = level - k;
            int cubeId = ((x >>> shift) & 1) | (((y >>> shift) & 1) << 1);
            index += (long) LOCAL_INDEX[ancestorType][cubeId] * place;
            place <<= 2;
            ancestorType = PARENT_TYPE[cubeId][ancestorType];
        }
        cachedIndex = index;
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
        // half is preserved — refinement/ancestry stays within the same root (S0 or S1).
        return new Triangle(level - 1, parentType, parentX, parentY, Math.min(parentX, parentY), half);
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
        // half is preserved — children stay within the same root (S0 or S1).
        return new Triangle(level + 1, childType, childX, childY, Math.min(childX, childY), half);
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
     * <p><b>Diagonal boundary (RDR-009 P3).</b> The S0 and S1 roots share the main diagonal
     * {@code y == x} as a closed edge, and the barycentric test is inclusive, so a point exactly on
     * the diagonal is contained by <em>both</em> halves (geometrically correct — it lies on the
     * shared edge). For canonical single-half point classification (which prism owns a point) use
     * {@link #fromWorldCoordinates}, which assigns {@code y == x} to S0 by convention; do not rely
     * on {@code contains()} alone to pick a half on the diagonal.
     *
     * @param worldX the world x-coordinate [0.0, 1.0)
     * @param worldY the world y-coordinate [0.0, 1.0)
     * @return true if the coordinates are contained in this triangle
     */
    public boolean contains(float worldX, float worldY) {
        if (worldX < 0.0f || worldX >= 1.0f || worldY < 0.0f || worldY >= 1.0f) {
            return false;
        }

        // Per-root containment (RDR-009 P3): there is no level-0 full-square special case any more.
        // getWorldBounds()/getVertices() already reflect for the S1 half, so the bounding-box +
        // barycentric test below is correct for both roots — the S0 root contains only its
        // lower-right half {y <= x} and the S1 root only its upper-left half {y >= x}.
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
        
        // Simplified same-type neighbor finding. A neighbor is only returned when it stays in the
        // S0 root (y <= x): a neighbor with y > x lies in the S1 root, which (with cross-diagonal
        // traversal) is RDR-009 Phase 3/Phase 4. Suppressing y > x is also REQUIRED for correctness
        // — such a triangle is not a valid S0 key and would collide with a coordinate-swapped S0 key
        // under consecutiveIndex()/compareTo(). n is recomputed as min(x,y) for each neighbor.
        var max = 1 << level;
        // Edge 0: right neighbor (x+1, y) — stays in S0 since y <= x < x+1.
        if (x + 1 < max && y <= x + 1) {
            neighbors[0] = new Triangle(level, type, x + 1, y, Math.min(x + 1, y), half);
        }
        // Edge 1: top neighbor (x, y+1) — only valid when y+1 <= x (else it crosses into S1).
        if (y + 1 < max && y + 1 <= x) {
            neighbors[1] = new Triangle(level, type, x, y + 1, Math.min(x, y + 1), half);
        }
        // Edge 2: diagonal neighbor (x-1, y-1) — preserves y <= x.
        if (x > 0 && y > 0 && y - 1 <= x - 1) {
            neighbors[2] = new Triangle(level, type, x - 1, y - 1, Math.min(x - 1, y - 1), half);
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
        
        // Same-type S0 neighbor (see neighbors()): only returned when it stays in y <= x; a
        // y > x neighbor is S1 (RDR-009 P3/P4) and would collide in consecutiveIndex()/compareTo().
        var max = 1 << level;
        switch (edge) {
            case 0: // right neighbor (x+1, y)
                if (x + 1 < max && y <= x + 1) {
                    return new Triangle(level, type, x + 1, y, Math.min(x + 1, y), half);
                }
                break;
            case 1: // top neighbor (x, y+1) — only valid when y+1 <= x
                if (y + 1 < max && y + 1 <= x) {
                    return new Triangle(level, type, x, y + 1, Math.min(x, y + 1), half);
                }
                break;
            case 2: // diagonal neighbor (x-1, y-1)
                if (x > 0 && y > 0 && y - 1 <= x - 1) {
                    return new Triangle(level, type, x - 1, y - 1, Math.min(x - 1, y - 1), half);
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
        // S1 (half 1) is the reflection of the S0 frame across the main diagonal y = x, so its
        // world bounds are the S0-frame bounds with x/y swapped.
        if (half == 1) {
            return new float[]{minY, minX, minY + scale, minX + scale};
        }
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
        // Reflect across y = x for the S1 half (see getWorldBounds).
        return (half == 1) ? new float[]{centerY, centerX} : new float[]{centerX, centerY};
    }
    
    // Accessors
    
    public byte getLevel() {
        return level;
    }
    
    public byte getType() {
        return type;
    }

    /**
     * The root half this triangle belongs to: {@code 0} = S0 (lower-right {@code y <= x}),
     * {@code 1} = S1 (the upper-left root, the reflection of S0 across {@code y = x}). The stored
     * {@code (x, y)} anchor is always in the S0 frame ({@code y <= x}); for the S1 half the
     * geometry ({@link #getVertices()}, {@link #getWorldBounds()}, {@link #contains}) reflects it.
     *
     * @return 0 for S0, 1 for S1
     */
    public byte getHalf() {
        return half;
    }

    public int getX() {
        return x;
    }
    
    public int getY() {
        return y;
    }
    
    /**
     * @deprecated The auxiliary coordinate {@code n} is the derived value {@code min(x, y)}; it
     *     carries no identity or ordering information (RDR-009 P2 excluded it from
     *     {@code equals}/{@code hashCode}/{@code consecutiveIndex}). The field and this accessor
     *     are slated for removal with the 5-arg constructor in RDR-009 Phase 7.
     */
    @Deprecated
    public int getN() {
        return n;
    }
    
    // Object methods
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Triangle other)) return false;
        // Identity is (half, level, type, x, y) — the t8 Tet-id plus the root half (RDR-009 P3).
        // n is the derived min(x,y) and carries no independent information, so it is excluded
        // (including it would make equals inconsistent with consecutiveIndex()/PrismKey.compareTo).
        return half == other.half && level == other.level && type == other.type && x == other.x && y == other.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(half, level, type, x, y);
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
        // Build the vertices in the S0 frame from the (unreflected) cell bounds, then reflect for
        // the S1 half. Note: we compute S0-frame bounds directly here rather than via
        // getWorldBounds() (which already reflects for half 1) — reflecting the bounding box does
        // not reflect the triangle's orientation, so we must swap the vertices themselves.
        var scale = 1.0f / (1 << level);
        float minX = x * scale;
        float minY = y * scale;
        float maxX = minX + scale;
        float maxY = minY + scale;

        // t8code/Bey orientation: split the cell along its MAIN diagonal (minX,minY)→(maxX,maxY).
        float[][] v;
        if (type == 0) {
            // Lower-right Kuhn simplex {anchor, anchor+x̂, anchor+x̂+ŷ} (the S0 orientation, y ≤ x).
            v = new float[][] { { minX, minY }, { maxX, minY }, { maxX, maxY } };
        } else {
            // Upper-left Kuhn simplex {anchor, anchor+ŷ, anchor+x̂+ŷ} (the S1 orientation, x ≤ y).
            v = new float[][] { { minX, minY }, { minX, maxY }, { maxX, maxY } };
        }
        // S1 (half 1) is the reflection of the S0 frame across y = x — swap each vertex.
        if (half == 1) {
            for (var p : v) {
                float t = p[0];
                p[0] = p[1];
                p[1] = t;
            }
        }
        return v;
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