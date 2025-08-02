/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility to verify transform-based rendering is working correctly.
 *
 * @author hal.hildebrand
 */
public class TransformBasedVerification {

    /**
     * Count total MeshView instances. This represents the number of tetrahedra displayed.
     */
    public static int countMeshViews(Node root) {
        int[] count = { 0 };
        countMeshViewsRecursive(root, count);
        return count[0];
    }

    private static void countMeshViewsRecursive(Node node, int[] count) {
        if (node instanceof MeshView) {
            count[0]++;
        }

        if (node instanceof final Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                countMeshViewsRecursive(child, count);
            }
        }
    }

    private static void countMeshesRecursive(Node node, Set<TriangleMesh> meshes) {
        if (node instanceof final MeshView meshView) {
            if (meshView.getMesh() instanceof TriangleMesh) {
                meshes.add((TriangleMesh) meshView.getMesh());
            }
        }

        if (node instanceof final Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                countMeshesRecursive(child, meshes);
            }
        }
    }

    /**
     * Count unique TriangleMesh instances in a scene graph. In transform-based rendering, this should be at most 6.
     */
    public static int countUniqueMeshes(Node root) {
        Set<TriangleMesh> uniqueMeshes = new HashSet<>();
        countMeshesRecursive(root, uniqueMeshes);


        return uniqueMeshes.size();
    }

    /**
     * Print verification statistics.
     */
    public static void printVerificationStats(Node root, String mode) {
        String stats = getVerificationStats(root, mode);
        System.out.println(stats);
    }
    
    /**
     * Get verification statistics as a string.
     */
    public static String getVerificationStats(Node root, String mode) {
        int uniqueMeshes = countUniqueMeshes(root);
        int meshViews = countMeshViews(root);

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ").append(mode).append(" Rendering Statistics ===\n");
        sb.append("Unique TriangleMesh instances: ").append(uniqueMeshes).append("\n");
        sb.append("Total MeshView instances found: ").append(meshViews).append("\n");
        sb.append("Memory efficiency ratio: ");
        sb.append(meshViews > 0 ? String.format("%.1f:1", (double) meshViews / uniqueMeshes) : "N/A").append("\n");

        if (mode.contains("Transform") && uniqueMeshes > 6) {
            sb.append("WARNING: More than 6 unique meshes detected!\n");
        }
        sb.append("=====================================\n");
        
        return sb.toString();
    }
}
