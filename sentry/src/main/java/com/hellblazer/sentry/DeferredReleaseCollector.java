/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
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
package com.hellblazer.sentry;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects tetrahedra for deferred release to the allocator.
 * This allows us to safely delete tetrahedra during flip operations
 * without premature release that could cause crashes.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class DeferredReleaseCollector {
    private final List<Tetrahedron> pendingRelease = new ArrayList<>();
    private final TetrahedronAllocator allocator;
    
    public DeferredReleaseCollector(TetrahedronAllocator allocator) {
        this.allocator = allocator;
    }
    
    /**
     * Mark a tetrahedron for deferred release.
     * The tetrahedron should already be deleted.
     */
    public void markForRelease(Tetrahedron t) {
        if (t != null && t.isDeleted()) {
            pendingRelease.add(t);
        }
    }
    
    /**
     * Release all collected tetrahedra to the allocator.
     * This should be called after all flip operations are complete.
     */
    public void releaseAll() {
        if (!pendingRelease.isEmpty()) {
            // Use batch release for efficiency
            allocator.releaseBatch(pendingRelease.toArray(new Tetrahedron[0]), pendingRelease.size());
            pendingRelease.clear();
        }
    }
    
    /**
     * Get the number of tetrahedra pending release.
     */
    public int getPendingCount() {
        return pendingRelease.size();
    }
}