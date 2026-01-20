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
package com.hellblazer.luciferase.esvo.dag.pipeline;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;

/**
 * Factory for creating test octree data.
 *
 * @author hal.hildebrand
 */
class TestDataFactory {

    /**
     * Create a simple test octree with the specified number of leaf nodes.
     *
     * <p>Creates a minimal valid octree structure for testing compression pipeline.
     *
     * @param leafCount approximate number of nodes (will be adjusted to valid structure)
     * @return test octree
     */
    static ESVOOctreeData createSimpleOctree(int leafCount) {
        // Create octree with sufficient capacity
        var capacity = Math.max(1024, leafCount * 2);
        var octree = new ESVOOctreeData(capacity);

        // Create root node with children
        var root = new ESVONodeUnified();
        root.setValid(true);

        if (leafCount <= 8) {
            // Small octree - root is leaf
            root.setChildMask(0);
        } else {
            // Larger octree - root has some children
            var childCount = Math.min(8, (leafCount + 7) / 8);
            var childMask = (1 << childCount) - 1;
            root.setChildMask(childMask);
            root.setChildPtr(1);

            octree.setNode(0, root);

            // Create leaf children
            for (int i = 0; i < childCount; i++) {
                var leaf = new ESVONodeUnified();
                leaf.setValid(true);
                leaf.setChildMask(0);  // Leaf
                octree.setNode(1 + i, leaf);
            }

            return octree;
        }

        octree.setNode(0, root);
        return octree;
    }
}
