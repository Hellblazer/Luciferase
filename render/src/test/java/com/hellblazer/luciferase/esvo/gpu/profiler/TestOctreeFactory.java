/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.gpu.profiler;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;

/**
 * Factory for creating test octree structures for GPU profiler tests.
 *
 * @author hal.hildebrand
 */
class TestOctreeFactory {

    /**
     * Create an empty octree (single root node, no children).
     *
     * @return empty octree
     */
    static ESVOOctreeData createEmpty() {
        var octree = new ESVOOctreeData(1);
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0); // No children
        octree.setNode(0, root);
        return octree;
    }

    /**
     * Create a simple test octree (2-level tree with 8 leaf children).
     *
     * @return simple test octree
     */
    static ESVOOctreeData createSimpleTestOctree() {
        var octree = new ESVOOctreeData(16);

        // Root node with all 8 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF); // All 8 children present
        root.setChildPtr(1);     // Children start at index 1
        octree.setNode(0, root);

        // Create 8 leaf children
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0); // Leaf node
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    /**
     * Create a medium test octree (2-level tree with some children).
     *
     * @return medium test octree
     */
    static ESVOOctreeData createMediumTestOctree() {
        var octree = new ESVOOctreeData(32);

        // Root node with first 4 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0x0F); // First 4 children present
        root.setChildPtr(1);     // Children start at index 1
        octree.setNode(0, root);

        // Level 1: 4 leaf children
        for (int i = 0; i < 4; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0); // Leaf node
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }
}
