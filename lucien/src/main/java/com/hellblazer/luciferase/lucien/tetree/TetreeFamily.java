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
package com.hellblazer.luciferase.lucien.tetree;

import java.util.HashSet;
import java.util.Set;

/**
 * Family relationship operations for tetrahedral trees. A family consists of 8 tetrahedra that share the same parent
 * and can be merged. Based on t8code's family relationship algorithms.
 *
 * @author hal.hildebrand
 */
public class TetreeFamily {

    /**
     * Check if a set of tetrahedra can be merged (i.e., they form a complete family). This is used by tree balancing
     * algorithms.
     *
     * @param tets set of tetrahedra to check
     * @return true if they can be merged into their parent
     */
    public static boolean canMerge(Set<Tet> tets) {
        if (tets.size() != TetreeConnectivity.CHILDREN_PER_TET) {
            return false;
        }

        Tet[] tetArray = tets.toArray(new Tet[0]);
        return isFamily(tetArray);
    }

    /**
     * Recursive helper to fill descendants array.
     */
    private static int fillDescendants(Tet current, byte targetLevel, Tet[] descendants, int index) {
        if (current.l() == targetLevel) {
            descendants[index] = current;
            return index + 1;
        }

        // Generate all children and recurse
        int nextIndex = index;
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_TET; i++) {
            try {
                Tet child = current.child(i);
                nextIndex = fillDescendants(child, targetLevel, descendants, nextIndex);
            } catch (IllegalStateException e) {
                // Max level reached
                break;
            }
        }

        return nextIndex;
    }

    /**
     * Find the common ancestor of two tetrahedra.
     *
     * @param tet1 first tetrahedron
     * @param tet2 second tetrahedron
     * @return the lowest common ancestor, or null if they have no common ancestor
     */
    public static Tet findCommonAncestor(Tet tet1, Tet tet2) {
        // Build path from tet1 to root
        Set<Tet> ancestors1 = new HashSet<>();
        Tet current = tet1;
        while (current.l() > 0) {
            ancestors1.add(current);
            current = current.parent();
        }
        ancestors1.add(current); // Add root

        // Traverse from tet2 to root and find first common ancestor
        current = tet2;
        while (current.l() > 0) {
            if (ancestors1.contains(current)) {
                return current;
            }
            current = current.parent();
        }

        // Check root
        if (ancestors1.contains(current)) {
            return current;
        }

        return null; // No common ancestor (shouldn't happen in valid tree)
    }

    /**
     * Get the index of a child within its parent's children array.
     *
     * @param child the child tetrahedron
     * @return the child index (0-7), or -1 if not a valid child
     */
    public static int getChildIndex(Tet child) {
        if (child.l() == 0) {
            return -1; // Root has no parent
        }

        Tet parent = child.parent();

        // Check all possible child indices
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_TET; i++) {
            Tet expectedChild = parent.child(i);
            if (expectedChild.equals(child)) {
                return i;
            }
        }

        return -1; // Not a valid child (shouldn't happen)
    }

    /**
     * Get all descendants of a tetrahedron at a specific level.
     *
     * @param ancestor    the ancestor tetrahedron
     * @param targetLevel the target level (must be >= ancestor.l())
     * @return array of all descendants at the target level
     */
    public static Tet[] getDescendantsAtLevel(Tet ancestor, byte targetLevel) {
        if (targetLevel < ancestor.l()) {
            throw new IllegalArgumentException("Target level must be >= ancestor level");
        }

        if (targetLevel == ancestor.l()) {
            return new Tet[] { ancestor };
        }

        // Calculate number of descendants
        int levelDiff = targetLevel - ancestor.l();
        int numDescendants = 1 << (3 * levelDiff); // 8^levelDiff

        Tet[] descendants = new Tet[numDescendants];

        // Use recursive approach to generate all descendants
        fillDescendants(ancestor, targetLevel, descendants, 0);

        return descendants;
    }

    /**
     * Get all siblings of a tetrahedron. Siblings are the other 7 tetrahedra that share the same parent.
     *
     * @param tet the tetrahedron
     * @return array of sibling tetrahedra (including the input tet)
     */
    public static Tet[] getSiblings(Tet tet) {
        if (tet.l() == 0) {
            // Root has no siblings, return just itself
            return new Tet[] { tet };
        }

        Tet parent = tet.parent();
        Tet[] siblings = new Tet[TetreeConnectivity.CHILDREN_PER_TET];

        // Generate all children of the parent
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_TET; i++) {
            siblings[i] = parent.child(i);
        }

        return siblings;
    }

    /**
     * Check if a tetrahedron is an ancestor of another at any level.
     *
     * @param ancestor   the potential ancestor
     * @param descendant the potential descendant
     * @return true if ancestor contains descendant in its subtree
     */
    public static boolean isAncestorOf(Tet ancestor, Tet descendant) {
        // Ancestor must be at a coarser level
        if (ancestor.l() >= descendant.l()) {
            return false;
        }

        // Traverse up from descendant to see if we reach ancestor
        Tet current = descendant;
        while (current.l() > ancestor.l()) {
            current = current.parent();
        }

        return current.equals(ancestor);
    }

    /**
     * Check if 8 tetrahedra form a refinement family. A valid family consists of all 8 children of the same parent.
     *
     * @param tets array of tetrahedra to check
     * @return true if they form a valid family
     */
    public static boolean isFamily(Tet[] tets) {
        if (tets == null || tets.length != TetreeConnectivity.CHILDREN_PER_TET) {
            return false;
        }

        // All must be at same level
        byte level = tets[0].l();
        for (Tet tet : tets) {
            if (tet.l() != level) {
                return false;
            }
        }

        // Check if they all have the same parent
        Tet parent0 = tets[0].parent();
        for (int i = 1; i < tets.length; i++) {
            Tet parent = tets[i].parent();
            if (!parent.equals(parent0)) {
                return false;
            }
        }

        // Verify they are all distinct children of the parent
        boolean[] childFound = new boolean[TetreeConnectivity.CHILDREN_PER_TET];
        for (Tet tet : tets) {
            // Determine which child this is
            for (int childIdx = 0; childIdx < TetreeConnectivity.CHILDREN_PER_TET; childIdx++) {
                Tet expectedChild = parent0.child(childIdx);
                if (tet.equals(expectedChild)) {
                    if (childFound[childIdx]) {
                        return false; // Duplicate child
                    }
                    childFound[childIdx] = true;
                    break;
                }
            }
        }

        // All children must be present
        for (boolean found : childFound) {
            if (!found) {
                return false;
            }
        }

        return true;
    }

    /**
     * Validate parent-child relationships.
     *
     * @param parent the potential parent tetrahedron
     * @param child  the potential child tetrahedron
     * @return true if parent is the direct parent of child
     */
    public static boolean isParentOf(Tet parent, Tet child) {
        // Child must be one level deeper
        if (child.l() != parent.l() + 1) {
            return false;
        }

        // Check if child's parent equals the given parent
        try {
            Tet childParent = child.parent();
            return childParent.equals(parent);
        } catch (IllegalStateException e) {
            // Child is at level 0 and has no parent
            return false;
        }
    }
}
