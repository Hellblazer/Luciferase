package com.dyada.core.linearization;

import java.util.Objects;

/**
 * Represents a range of linear indices on a space-filling curve.
 * 
 * A LinearRange defines an interval [start, end] of consecutive positions
 * on a linearized space-filling curve. This is used for range queries,
 * spatial filtering, and bulk operations on spatially coherent data.
 * 
 * The range is inclusive of both endpoints, meaning both start and end
 * indices are considered part of the range.
 */
public record LinearRange(
    long start,
    long end
) {
    
    /**
     * Compact constructor with validation.
     */
    public LinearRange {
        if (start < 0) {
            throw new IllegalArgumentException("Start index cannot be negative: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException(
                String.format("End index %d cannot be less than start index %d", end, start));
        }
    }
    
    /**
     * Creates a range containing a single linear index.
     * 
     * @param index The single index to include
     * @return LinearRange containing only the specified index
     * @throws IllegalArgumentException if index is negative
     */
    public static LinearRange of(long index) {
        return new LinearRange(index, index);
    }
    
    /**
     * Creates a range from start to end (inclusive).
     * 
     * @param start Starting index (inclusive)
     * @param end Ending index (inclusive)
     * @return LinearRange covering the specified interval
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static LinearRange of(long start, long end) {
        return new LinearRange(start, end);
    }
    
    /**
     * Returns the number of indices in this range.
     * 
     * For a range [start, end], the size is (end - start + 1).
     * 
     * @return Number of linear indices covered by this range
     */
    public long size() {
        return end - start + 1;
    }
    
    /**
     * Returns true if this range contains the specified index.
     * 
     * @param index The linear index to test
     * @return true if start <= index <= end
     */
    public boolean contains(long index) {
        return index >= start && index <= end;
    }
    
    /**
     * Returns true if this range contains the entire other range.
     * 
     * @param other The range to test for containment
     * @return true if this range completely contains the other range
     * @throws NullPointerException if other is null
     */
    public boolean contains(LinearRange other) {
        Objects.requireNonNull(other, "Other range cannot be null");
        return start <= other.start && end >= other.end;
    }
    
    /**
     * Returns true if this range overlaps with the other range.
     * 
     * Two ranges overlap if they have any indices in common.
     * 
     * @param other The range to test for overlap
     * @return true if the ranges have any common indices
     * @throws NullPointerException if other is null
     */
    public boolean overlaps(LinearRange other) {
        Objects.requireNonNull(other, "Other range cannot be null");
        return start <= other.end && end >= other.start;
    }
    
    /**
     * Returns the intersection of this range with another range.
     * 
     * @param other The range to intersect with
     * @return LinearRange representing the intersection, or null if no overlap
     * @throws NullPointerException if other is null
     */
    public LinearRange intersect(LinearRange other) {
        Objects.requireNonNull(other, "Other range cannot be null");
        
        long intersectStart = Math.max(start, other.start);
        long intersectEnd = Math.min(end, other.end);
        
        if (intersectStart <= intersectEnd) {
            return new LinearRange(intersectStart, intersectEnd);
        }
        
        return null; // No intersection
    }
    
    /**
     * Returns the union of this range with another range.
     * 
     * The ranges must overlap or be adjacent for the union to be meaningful.
     * If the ranges are disjoint with a gap, this method returns null.
     * 
     * @param other The range to union with
     * @return LinearRange representing the union, or null if ranges are disjoint
     * @throws NullPointerException if other is null
     */
    public LinearRange union(LinearRange other) {
        Objects.requireNonNull(other, "Other range cannot be null");
        
        // Check if ranges overlap or are adjacent
        if (overlaps(other) || isAdjacentTo(other)) {
            long unionStart = Math.min(start, other.start);
            long unionEnd = Math.max(end, other.end);
            return new LinearRange(unionStart, unionEnd);
        }
        
        return null; // Ranges are disjoint
    }
    
    /**
     * Returns true if this range is adjacent to the other range.
     * 
     * Two ranges are adjacent if one ends exactly where the other begins,
     * with no gap or overlap between them.
     * 
     * @param other The range to test for adjacency
     * @return true if the ranges are adjacent
     * @throws NullPointerException if other is null
     */
    public boolean isAdjacentTo(LinearRange other) {
        Objects.requireNonNull(other, "Other range cannot be null");
        return end + 1 == other.start || other.end + 1 == start;
    }
    
    /**
     * Returns true if this range represents a single index.
     * 
     * @return true if start == end
     */
    public boolean isSingleton() {
        return start == end;
    }
    
    /**
     * Returns true if this range is empty (contains no indices).
     * 
     * Note: By the constructor contract, this should never be true
     * since end >= start is enforced.
     * 
     * @return false (ranges are never empty by design)
     */
    public boolean isEmpty() {
        return false; // Ranges are never empty by construction
    }
    
    /**
     * Expands this range by the specified amount in both directions.
     * 
     * The resulting range will start at (start - expansion) and end at
     * (end + expansion), but will not go below 0 for the start index.
     * 
     * @param expansion Amount to expand in each direction
     * @return New LinearRange expanded by the specified amount
     * @throws IllegalArgumentException if expansion is negative
     */
    public LinearRange expand(long expansion) {
        if (expansion < 0) {
            throw new IllegalArgumentException("Expansion cannot be negative: " + expansion);
        }
        
        long newStart = Math.max(0, start - expansion);
        long newEnd = end + expansion;
        
        return new LinearRange(newStart, newEnd);
    }
    
    /**
     * Contracts this range by the specified amount from both ends.
     * 
     * The resulting range will start at (start + contraction) and end at
     * (end - contraction). If the contraction would make the range invalid
     * (end < start), returns null.
     * 
     * @param contraction Amount to contract from each end
     * @return New LinearRange contracted by the specified amount, or null if invalid
     * @throws IllegalArgumentException if contraction is negative
     */
    public LinearRange contract(long contraction) {
        if (contraction < 0) {
            throw new IllegalArgumentException("Contraction cannot be negative: " + contraction);
        }
        
        long newStart = start + contraction;
        long newEnd = end - contraction;
        
        if (newStart <= newEnd) {
            return new LinearRange(newStart, newEnd);
        }
        
        return null; // Contraction would make range invalid
    }
    
    /**
     * Splits this range at the specified index.
     * 
     * Returns an array of LinearRange objects representing the parts
     * before and after the split point. If the split index is outside
     * the range, returns the original range unchanged.
     * 
     * @param splitIndex Index at which to split the range
     * @return Array of LinearRange objects (1 or 2 elements)
     */
    public LinearRange[] split(long splitIndex) {
        if (splitIndex < start || splitIndex > end) {
            // Split point outside range - return original
            return new LinearRange[]{this};
        }
        
        if (splitIndex == start) {
            // Split at start - return just the range
            return new LinearRange[]{this};
        }
        
        if (splitIndex == end) {
            // Split at end - return just the range
            return new LinearRange[]{this};
        }
        
        // Split in middle
        return new LinearRange[]{
            new LinearRange(start, splitIndex - 1),
            new LinearRange(splitIndex, end)
        };
    }
    
    @Override
    public String toString() {
        if (isSingleton()) {
            return String.format("LinearRange[%d]", start);
        } else {
            return String.format("LinearRange[%d..%d] (size=%d)", start, end, size());
        }
    }
    
    /**
     * Returns a detailed string representation including range statistics.
     * 
     * @return Detailed string with range properties
     */
    public String toDetailedString() {
        return String.format(
            "LinearRange{start=%d, end=%d, size=%d, singleton=%s}",
            start, end, size(), isSingleton()
        );
    }
}