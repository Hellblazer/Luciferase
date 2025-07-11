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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;

import javax.vecmath.Point3f;

/**
 * Utility methods for forest tests
 */
public class ForestTestUtil {
    
    /**
     * Add a tree with bounds to the forest
     */
    public static <ID extends EntityID, Content> String addTreeWithBounds(
            Forest<MortonKey, ID, Content> forest,
            Octree<ID, Content> tree,
            EntityBounds bounds,
            String name) {
        
        var metadata = TreeMetadata.builder()
            .name(name)
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("bounds", bounds)
            .build();
        
        var treeId = forest.addTree(tree, metadata);
        
        // Store bounds in tree node metadata and expand global bounds
        var treeNode = forest.getTree(treeId);
        if (treeNode != null && bounds != null) {
            treeNode.setMetadata("bounds", bounds);
            // Initialize the tree's global bounds
            treeNode.expandGlobalBounds(bounds);
        }
        
        return treeId;
    }
    
    /**
     * Get bounds from tree metadata
     */
    public static EntityBounds getTreeBounds(TreeNode<?, ?, ?> treeNode) {
        var bounds = treeNode.getMetadata("bounds");
        if (bounds instanceof EntityBounds) {
            return (EntityBounds) bounds;
        }
        
        // Check if stored in TreeMetadata
        var metadata = treeNode.getMetadata("metadata");
        if (metadata instanceof TreeMetadata) {
            var treeMeta = (TreeMetadata) metadata;
            var boundsObj = treeMeta.getProperty("bounds");
            if (boundsObj instanceof EntityBounds) {
                return (EntityBounds) boundsObj;
            }
        }
        
        return null;
    }
}