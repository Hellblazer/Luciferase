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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SpatialKey;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;

/**
 * Abstract base class for Tetree spatial keys supporting up to 21 refinement levels. Provides common functionality for
 * both compact (single-long) and extended (dual-long) representations.
 *
 * <h3>Architecture Overview</h3>
 * The TetreeKey system uses a dual implementation strategy for optimal performance:
 * <ul>
 * <li><b>CompactTetreeKey</b>: Single 64-bit long for levels 0-10 (optimal performance for common cases)</li>
 * <li><b>ExtendedTetreeKey</b>: Dual 64-bit longs for levels 0-21 (full Octree-equivalent capacity)</li>
 * </ul>
 *
 * <h3>Bit Layout (coarsest-at-MSB, Luciferase-tkvb)</h3>
 * Uniform 6 bits per level across two longs, no special level-21 split:
 * <ul>
 * <li>21 * 6 = 126 bits fit two longs (lowBits = 0-63, highBits = 64-125)</li>
 * <li>Shallowest refinement step occupies the most significant bits; the leaf is at bits 0-5</li>
 * <li>compareTo compares (highBits, lowBits) unsigned, giving coarse-dominant SFC order</li>
 * <li>parent() drops the leaf group via a 128-bit right shift of 6 (matches PyramidKey)</li>
 * </ul>
 *
 * <h3>Tetrahedral Space-Filling Curve</h3>
 * Each TetreeKey encodes a (level, tmIndex) tuple where:
 * <ul>
 * <li><b>Level</b>: Refinement depth (0-21), stored separately for all key types</li>
 * <li><b>tmIndex</b>: Tetrahedral Morton index encoding 6 bits per level (3 coordinate + 3 type bits)</li>
 * <li><b>SFC Ordering</b>: Keys maintain spatial locality - adjacent indices represent spatially close cells</li>
 * </ul>
 *
 * @param <K> The concrete key type
 * @author hal.hildebrand
 */
public abstract class TetreeKey<K extends TetreeKey<K>> implements SpatialKey<TetreeKey<? extends TetreeKey<?>>> {

    // Bit layout constants. Coarsest-at-MSB uniform layout (Luciferase-tkvb): 6 bits per level,
    // 21 * 6 = 126 bits across two longs, no special level-21 split.
    protected static final int  BITS_PER_LEVEL       = 6;
    protected static final int  MAX_COMPACT_LEVEL    = 10;

    // Cached root instance - root is always compact
    private static final CompactTetreeKey ROOT = new CompactTetreeKey((byte) 0, 0L);

    // The level stored separately for all key types
    protected final byte level;

    /**
     * Create a new TetreeKey.
     *
     * @param level the hierarchical level
     */
    protected TetreeKey(byte level) {
        if (level < 0 || level > MortonCurve.MAX_REFINEMENT_LEVEL) {
            throw new IllegalArgumentException(
            "Level must be between 0 and " + MortonCurve.MAX_REFINEMENT_LEVEL + ", got: " + level);
        }
        this.level = level;
    }

    /**
     * Create an appropriate TetreeKey based on the level.
     *
     * @param level    the level
     * @param lowBits  the low 64 bits
     * @param highBits the high 64 bits (ignored for levels <= 10)
     * @return CompactTetreeKey for levels <= 10, ExtendedTetreeKey for levels > 10
     */
    public static TetreeKey<? extends TetreeKey<?>> create(byte level, long lowBits, long highBits) {
        if (level <= MAX_COMPACT_LEVEL) {
            return new CompactTetreeKey(level, lowBits);
        } else {
            return new ExtendedTetreeKey(level, lowBits, highBits);
        }
    }


    public static TetreeKey<? extends TetreeKey<?>> getRoot() {
        return ROOT;
    }

    /**
     * Checks if this key can be merged with another key in a range. Keys can be merged if they are adjacent or if this
     * key's end + 1 >= other key's start.
     *
     * @param other the key to check for mergeability
     * @return true if the keys can be merged, false otherwise
     */
    public boolean canMergeWith(TetreeKey<?> other) {
        if (other == null || this.level != other.level) {
            return false;
        }

        // Keys at the same level can be merged if they are adjacent or overlapping
        // Since we're dealing with ranges, we consider them mergeable if they're adjacent
        return this.isAdjacentTo(other) || this.equals(other);
    }

    /**
     * Extract the coordinate bits for a specific level from the tm-index.
     *
     * @param targetLevel the level to extract coordinates for (0 to current level)
     * @return the 3-bit coordinate value at that level
     */
    public byte getCoordBitsAtLevel(int targetLevel) {
        if (targetLevel < 0 || targetLevel > level) {
            throw new IllegalArgumentException("Target level must be between 0 and " + level);
        }
        // Coordinate bits are the upper 3 bits of the 6-bit group.
        return (byte) ((rawGroupAt(targetLevel) >> 3) & 0x7);
    }

    /**
     * Extract the raw 6-bit group for refinement step {@code targetLevel}. Coarsest-at-MSB layout
     * (Luciferase-tkvb): {@code targetLevel == 0} is the shallowest step and sits in the most
     * significant occupied bits; {@code targetLevel == level - 1} is the leaf and sits at bits 0-5.
     * The group lives at bit offset {@code (level - 1 - targetLevel) * 6} from the LSB across the
     * 128-bit {@code (highBits, lowBits)} value and may straddle the 64-bit boundary.
     *
     * @param targetLevel the 0-indexed refinement step (0 = shallowest, level-1 = leaf)
     * @return the 6-bit group value (0..63)
     */
    private long rawGroupAt(int targetLevel) {
        if (level == 0) {
            return 0L;
        }
        int bit = (level - 1 - targetLevel) * BITS_PER_LEVEL; // offset from LSB
        if (bit < 0) {
            return 0L; // targetLevel == level (one past the leaf): no group
        }
        if (bit >= 64) {
            return (getHighBits() >>> (bit - 64)) & 0x3FL;
        }
        if (bit + BITS_PER_LEVEL <= 64) {
            return (getLowBits() >>> bit) & 0x3FL;
        }
        // Straddles the low/high boundary.
        long lowPart = getLowBits() >>> bit;
        long highPart = getHighBits() << (64 - bit);
        return (lowPart | highPart) & 0x3FL;
    }

    /**
     * Get the high bits of the TM-index. For levels <= 10, this returns 0. For levels > 10, this contains levels 10+.
     *
     * @return the high bits of the TM-index
     */
    public abstract long getHighBits();

    @Override
    public byte getLevel() {
        return level;
    }

    /**
     * Uniform equality across all {@code TetreeKey} implementations (Luciferase-567m). Two keys are equal iff they
     * share {@code (level, lowBits, highBits)}, regardless of runtime class. Declared {@code final} so the three
     * implementations ({@code CompactTetreeKey}, {@code ExtendedTetreeKey}, {@code LazyTetreeKey}) cannot
     * reintroduce the {@code instanceof}-keyed-to-own-class asymmetry that violated {@link Object#equals} symmetry
     * and diverged from {@code compareTo == 0}. A {@code LazyTetreeKey} resolves its bits here (via the overridden
     * {@code getLowBits}/{@code getHighBits}); the spatial index's {@code compareTo}-based skip-list path is
     * unaffected since it never calls this method.
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TetreeKey<?> that)) {
            return false;
        }
        return level == that.getLevel() && getLowBits() == that.getLowBits() && getHighBits() == that.getHighBits();
    }

    /**
     * Get the low bits of the TM-index (index bits 0-63). Coarsest-at-MSB layout (Luciferase-tkvb):
     * these are the deeper refinement steps, with the leaf group at bits 0-5. For levels <= 10 the
     * whole index fits here and the high bits are 0.
     *
     * @return the low bits of the TM-index
     */
    public abstract long getLowBits();

    /**
     * Extract the type bits for a specific level from the tm-index.
     *
     * @param targetLevel the level to extract type for (0 to current level)
     * @return the 3-bit type value at that level
     */
    public byte getTypeAtLevel(int targetLevel) {
        if (targetLevel < 0 || targetLevel > level) {
            throw new IllegalArgumentException("Target level must be between 0 and " + level);
        }
        // Type bits are the lower 3 bits of the 6-bit group.
        return (byte) (rawGroupAt(targetLevel) & 0x7);
    }

    /**
     * Hash aligned to {@link #equals} on the {@code (level, lowBits, highBits)} tuple. Declared {@code final}
     * (Luciferase-567m) so every implementation hashes identically for equal keys — in particular a
     * {@code LazyTetreeKey} resolves to the same hash as the concrete key it represents, rather than the old
     * Tet-coordinate polynomial that diverged from the concrete tmIndex hash.
     */
    @Override
    public final int hashCode() {
        return Objects.hash(level, getLowBits(), getHighBits());
    }

    /**
     * Checks if this key is adjacent to another key in the space-filling curve. Two keys are considered adjacent if
     * they are at the same level and their indices differ by exactly 1.
     *
     * @param other the key to compare with
     * @return true if the keys are adjacent, false otherwise
     */
    public boolean isAdjacentTo(TetreeKey<?> other) {
        if (other == null || this.level != other.level) {
            return false;
        }

        // For keys at the same level, check if indices differ by 1
        // We need to handle the case where keys might span the boundary between low and high bits
        long thisLow = this.getLowBits();
        long thisHigh = this.getHighBits();
        long otherLow = other.getLowBits();
        long otherHigh = other.getHighBits();

        // Compare as 128-bit values
        if (thisHigh == otherHigh) {
            // High bits are equal, check if low bits differ by 1
            long diff = Math.abs(thisLow - otherLow);
            return diff == 1;
        } else if (Math.abs(thisHigh - otherHigh) == 1) {
            // High bits differ by 1, check for boundary crossing
            if (thisHigh < otherHigh) {
                // This key is smaller, check if it's at max low bits and other is at 0
                return thisLow == 0xFFFFFFFFFFFFFFFFL && otherLow == 0;
            } else {
                // Other key is smaller, check if it's at max low bits and this is at 0
                return otherLow == 0xFFFFFFFFFFFFFFFFL && thisLow == 0;
            }
        }

        return false;
    }

    public boolean isKuhn() {
        return false;
    }

    @Override
    public boolean isValid() {
        // Check basic constraints
        if (level < 0 || level > MortonCurve.MAX_REFINEMENT_LEVEL) {
            return false;
        }

        // Special case: root tetrahedron
        if (level == 0) {
            return getLowBits() == 0L && getHighBits() == 0L;
        }

        // Subclasses may add additional validation
        return true;
    }


    /**
     * Returns the maximum of two TetreeKeys at the same level. This is used for determining the end of a merged range.
     *
     * @param other the other key to compare
     * @return the larger of the two keys
     * @throws IllegalArgumentException if keys are at different levels
     */
    public TetreeKey<?> max(TetreeKey<?> other) {
        if (other == null) {
            return this;
        }
        if (this.level != other.level) {
            throw new IllegalArgumentException("Cannot compare keys at different levels");
        }

        return this.compareTo(other) >= 0 ? this : other;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final K root() {
        // Root is always level 0 and always fits in compact representation
        // This cast is safe because all implementations must accept CompactTetreeKey as a valid key
        return (K) ROOT;
    }

    public Tet toTet() {
        return Tet.tetrahedron(this);
    }
    
    @Override
    public String toString() {
        // For fast execution, we'll provide essential info without computing the full Tet
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName()).append("[L").append(level);
        
        // Add low bits in base64 for compactness
        sb.append(",tm:").append(longToBase64(getLowBits()));
        
        // Add high bits only if non-zero (for ExtendedTetreeKey)
        if (getHighBits() != 0) {
            sb.append("/").append(longToBase64(getHighBits()));
        }
        
        // For debugging, optionally add the anchor coordinates by converting to Tet
        // This is commented out by default for performance, but can be enabled when needed
        // Tet tet = toTet();
        // sb.append(",@(").append(tet.x).append(",").append(tet.y).append(",").append(tet.z).append(")");
        
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * Convert a long to a compact base64 string representation.
     * Uses URL-safe base64 encoding without padding for compactness.
     */
    private static String longToBase64(long value) {
        // Convert long to byte array
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        
        // Use URL-safe base64 encoding without padding
        String base64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        
        // Remove leading A's (zeros) for compactness
        int firstNonA = 0;
        while (firstNonA < base64.length() - 1 && base64.charAt(firstNonA) == 'A') {
            firstNonA++;
        }
        return base64.substring(firstNonA);
    }

    // ===== SFC Range Estimation for k-NN Optimization =====
    
    /**
     * Represents a range of Tetree keys for spatial queries.
     * Used to prune k-NN search using ConcurrentSkipListMap.subMap().
     * 
     * @param lower the lower bound (inclusive) of the TetreeKey range
     * @param upper the upper bound (exclusive) of the TetreeKey range
     */
    public record SFCRange(TetreeKey<?> lower, TetreeKey<?> upper) {
        public SFCRange {
            Objects.requireNonNull(lower, "Lower bound cannot be null");
            Objects.requireNonNull(upper, "Upper bound cannot be null");
        }
    }
    
    /**
     * Estimate the appropriate Tetree depth for a given search radius.
     * This maps a geometric distance to the corresponding SFC depth where cells
     * are approximately the size of the search radius.
     * 
     * From Paper 4 (Space-Filling Trees for Motion Planning):
     * - Larger radius → coarser level (fewer, larger cells)
     * - Smaller radius → finer level (more, smaller cells)
     * 
     * Note: Uses the same geometric sizing as Octree (Constants.lengthAtLevel)
     * since tetrahedra are inscribed in cubes of the same cell size.
     * 
     * @param radius the search radius in world coordinates
     * @return the estimated Tetree depth (level) appropriate for this radius
     */
    public static byte estimateSFCDepth(float radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("Search radius must be positive, got: " + radius);
        }
        
        // Find the finest level where cell diagonal >= radius
        // Start from finest level (small cells) and work towards coarser levels (large cells)
        // Return the first level where the cell is large enough to cover the radius
        for (byte level = MortonCurve.MAX_REFINEMENT_LEVEL; level >= 0; level--) {
            float cellSize = Constants.lengthAtLevel(level);
            float cellDiagonal = (float) (cellSize * Math.sqrt(3.0));
            
            // If cell diagonal at this level >= radius, this is our target level
            if (cellDiagonal >= radius) {
                return level;
            }
        }
        
        // Radius is larger than even the root cell (extremely large), use root level
        return 0;
    }
    
    /**
     * Estimate the SFC range (TetreeKey range) that covers a spherical region.
     * This enables pruned k-NN search using ConcurrentSkipListMap.subMap().
     * 
     * Algorithm from Paper 4:
     * 1. Compute axis-aligned bounding box (AABB) around the sphere
     * 2. Find tetrahedra that contain the AABB corners at the estimated depth
     * 3. Return range [min_key, max_key] covering all entities in the sphere
     * 
     * Note: Returns a conservative estimate (may include entities outside the sphere).
     * Caller must filter by actual distance.
     * 
     * @param center the center point of the search sphere
     * @param radius the search radius
     * @return SFCRange covering the spherical region (conservative estimate)
     */
    public static SFCRange estimateSFCRange(Point3f center, float radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("Search radius must be positive, got: " + radius);
        }
        
        // Step 1: Estimate appropriate depth for this radius
        byte level = estimateSFCDepth(radius);
        float cellSize = Constants.lengthAtLevel(level);
        
        // Step 2: Compute AABB around sphere
        // Expand by cell size to ensure complete coverage (conservative)
        float expansion = cellSize;
        
        // Clamp coordinates to valid range [0, MAX_COORD]
        // MAX_COORD = 2^21 - 1 = 2,097,151
        float maxCoord = Constants.MAX_COORD;
        
        Point3f min = new Point3f(
            Math.max(0, center.x - radius - expansion),
            Math.max(0, center.y - radius - expansion),
            Math.max(0, center.z - radius - expansion)
        );
        Point3f max = new Point3f(
            Math.min(maxCoord, center.x + radius + expansion),
            Math.min(maxCoord, center.y + radius + expansion),
            Math.min(maxCoord, center.z + radius + expansion)
        );
        
        // Step 3: Find tetrahedra containing the AABB corners
        // Use Tet.locatePointBeyRefinementFromRoot to find the tetrahedral cell at this level
        Tet minTet = Tet.locatePointBeyRefinementFromRoot(min.x, min.y, min.z, level);
        Tet maxTet = Tet.locatePointBeyRefinementFromRoot(max.x, max.y, max.z, level);
        
        if (minTet == null || maxTet == null) {
            // Fallback: use root tetrahedron range
            return new SFCRange(getRoot(), getRoot());
        }
        
        // Convert to TetreeKeys
        TetreeKey<?> minKey = minTet.tmIndex();
        TetreeKey<?> maxKey = maxTet.tmIndex();
        
        // Ensure proper ordering (min <= max)
        if (minKey.compareTo(maxKey) > 0) {
            var tmp = minKey;
            minKey = maxKey;
            maxKey = tmp;
        }
        
        // Step 4: Create inclusive range
        // For subMap(), we need [lower, upper) 
        // We need to increment the upper bound, which requires getting the next key
        TetreeKey<?> upperBound = getNextKey(maxKey);
        
        return new SFCRange(minKey, upperBound);
    }
    
    /**
     * Get the next TetreeKey in SFC order for creating exclusive upper bounds.
     * This is needed for ConcurrentSkipListMap.subMap(lower, upper) where upper is exclusive.
     * 
     * @param key the current key
     * @return the next key in SFC order, or a sentinel maximum key if at the end
     */
    // Package-private (not private) so the unsigned-increment boundary can be regression-tested
    // directly (Luciferase-tkvb): the lowBits == -1L all-ones carry path is not reachable through
    // estimateSFCRange with ordinary inputs.
    static TetreeKey<?> getNextKey(TetreeKey<?> key) {
        // Strategy: Try to increment the tm-index by 1
        // If we overflow at this level, return a key at level-1 (coarser level)
        // This ensures we don't miss any keys in the range
        
        long lowBits = key.getLowBits();
        long highBits = key.getHighBits();
        byte level = key.getLevel();

        // Coarsest-at-MSB layout (Luciferase-tkvb): lowBits is the least-significant half of the
        // 128-bit index. Increment it as an unsigned 128-bit value. -1L is the all-ones (unsigned
        // max) word, so a non-all-ones word increments without carry.
        if (lowBits != -1L) {
            return create(level, lowBits + 1, highBits);
        }

        // Low bits are all-ones: they wrap to 0, carry into high bits.
        if (highBits != -1L) {
            return create(level, 0L, highBits + 1);
        }

        // Both halves are all-ones (the maximum key at this level) - use a sentinel at parent level.
        if (level > 0) {
            var parent = key.parent();
            if (parent != null) {
                return getNextKey((TetreeKey<?>) parent);
            }
        }

        // At root and overflowed - return maximum possible key.
        return create(level, -1L, -1L);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code TetreeKey} currently returns a single range and ignores {@code indexKeys}. <strong>This
     * under-covers a multi-level index:</strong> {@link #compareTo} is level-first (level, then 128-bit TM-index
     * unsigned), so a {@code subMap} bounded at the single level chosen by {@link #estimateSFCRange} only returns
     * keys at that level — entities stored at other levels within the radius are silently skipped. The correct
     * behaviour mirrors {@code MortonKey.sfcRangesForKNN} (one range per occupied level); tracked by
     * {@code Luciferase-6gnb}. RDR-008 P3 follow-up (bead Luciferase-vpl).
     */
    @Override
    public Iterable<SpatialKey.SFCRange<TetreeKey<? extends TetreeKey<?>>>> sfcRangesForKNN(
        Point3f center, float radius, NavigableSet<TetreeKey<? extends TetreeKey<?>>> indexKeys) {
        var range = estimateSFCRange(center, radius);
        return List.of(
            new SpatialKey.SFCRange<TetreeKey<? extends TetreeKey<?>>>(range.lower(), range.upper()));
    }
}
