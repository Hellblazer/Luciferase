/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry;

import com.hellblazer.luciferase.geometry.HilbertCurveComparator;
import javax.vecmath.Tuple3f;

/**
 * A Vertex that caches its Hilbert curve index for efficient spatial sorting.
 * The Hilbert index is lazily computed and cleared when the position changes.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class HilbertVertex extends Vertex {
    
    private static final long serialVersionUID = 1L;
    
    // Cached Hilbert index, -1 indicates not computed
    private transient long hilbertIndex = -1;
    
    // The comparator used for this vertex (must be set before sorting)
    private transient HilbertCurveComparator comparator;
    
    public HilbertVertex(float x, float y, float z) {
        super(x, y, z);
    }
    
    public HilbertVertex(Tuple3f p) {
        super(p);
    }
    
    public HilbertVertex(float x, float y, float z, float scale) {
        super(x, y, z, scale);
    }
    
    /**
     * Set the Hilbert comparator to use for this vertex.
     * This must be called before using compareTo() for sorting.
     */
    public void setHilbertComparator(HilbertCurveComparator comparator) {
        this.comparator = comparator;
        this.hilbertIndex = -1; // Reset cached index
    }
    
    /**
     * Get the cached Hilbert index, computing it if necessary.
     */
    private long getHilbertIndex() {
        if (hilbertIndex == -1 && comparator != null) {
            hilbertIndex = comparator.computeHilbertIndex(this);
        }
        return hilbertIndex;
    }
    
    @Override
    public int compareTo(Vertex other) {
        if (comparator == null) {
            // Fall back to Morton curve if no Hilbert comparator set
            return super.compareTo(other);
        }
        
        if (other instanceof HilbertVertex) {
            HilbertVertex hv = (HilbertVertex) other;
            return Long.compare(getHilbertIndex(), hv.getHilbertIndex());
        } else {
            // Compare against regular vertex
            long otherIndex = comparator.computeHilbertIndex(other);
            return Long.compare(getHilbertIndex(), otherIndex);
        }
    }
    
    @Override
    public void moveBy(Tuple3f delta) {
        super.moveBy(delta);
        hilbertIndex = -1; // Clear cached index
    }
    
    @Override
    public void moveTo(Tuple3f position) {
        super.moveTo(position);
        hilbertIndex = -1; // Clear cached index
    }
    
    /**
     * Clear the cached Hilbert index, forcing recomputation on next comparison.
     */
    public void clearHilbertIndex() {
        hilbertIndex = -1;
    }
    
    /**
     * Check if this vertex has a cached Hilbert index.
     */
    public boolean hasHilbertIndex() {
        return hilbertIndex != -1;
    }
}