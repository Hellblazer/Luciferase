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

import com.hellblazer.luciferase.lucien.Constants;

import javax.vecmath.Point3i;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Comprehensive validation utilities for tetrahedral tree structures. Provides static methods for validating
 * tetrahedral indices, tree consistency, parent-child relationships, neighbor relationships, and debug utilities.
 *
 * These validations ensure correctness of the tetree implementation and can be disabled in production for performance.
 *
 * @author hal.hildebrand
 */
public final class TetreeValidator {

    // Validation error messages
    private static final String INVALID_LEVEL             = "Invalid level %d, must be in range [0, %d]";
    private static final String INVALID_TYPE              = "Invalid type %d, must be in range [0, 5]";
    private static final String INVALID_COORDINATES       = "Invalid coordinates (%d, %d, %d), must be non-negative";
    private static final String MISALIGNED_COORDINATES    = "Coordinates (%d, %d, %d) not aligned to grid at level %d";
    private static final String COORDINATES_OUT_OF_BOUNDS = "Coordinates (%d, %d, %d) exceed maximum grid size";
    private static final String INVALID_INDEX             = "Invalid SFC index %d";
    private static final String INVALID_PARENT_CHILD      = "Tet %s is not a child of parent %s";
    private static final String INVALID_FAMILY            = "Tets do not form a valid family";
    private static final String INVALID_NEIGHBOR          = "Invalid neighbor relationship between %s and %s";
    private static final String TREE_STRUCTURE_ERROR      = "Tree structure error: %s";

    // Performance flag to disable validation in production
    private static boolean validationEnabled = true;

    // Private constructor to prevent instantiation
    private TetreeValidator() {
        throw new AssertionError("TetreeValidator is a utility class and should not be instantiated");
    }

    /**
     * Analyze tree balance and depth statistics from a collection of node indices.
     *
     * @param nodeIndices the node indices to analyze
     * @return tree statistics
     */
    public static TreeStats analyzeTreeIndices(Collection<TetreeKey> nodeIndices) {
        // Collect level statistics
        Map<Byte, Long> levelCounts = new HashMap<>();
        Map<Byte, List<TetreeKey>> levelNodes = new HashMap<>();

        for (var index : nodeIndices) {
            Tet tet = Tet.tetrahedron(index);
            byte level = tet.l();
            levelCounts.merge(level, 1L, Long::sum);
            levelNodes.computeIfAbsent(level, k -> new ArrayList<>()).add(index);
        }

        // Find max depth
        byte maxDepth = levelCounts.keySet().stream().max(Byte::compare).orElse((byte) 0);

        // Calculate balance factor (variance in subtree depths)
        double balanceFactor = calculateBalanceFactor(levelNodes, maxDepth);

        return new TreeStats(nodeIndices.size(), maxDepth, levelCounts, balanceFactor);
    }

    /**
     * Assert valid family relationship.
     *
     * @param tets array of tetrahedra that should form a family
     * @throws AssertionError if not a valid family
     */
    public static void assertValidFamily(Tet[] tets) {
        if (!validationEnabled) {
            return;
        }

        if (!isValidFamily(tets)) {
            throw new AssertionError(INVALID_FAMILY);
        }
    }

    /**
     * Assert valid parent-child relationship.
     *
     * @param parent the parent tetrahedron
     * @param child  the child tetrahedron
     * @throws AssertionError if relationship is invalid
     */
    public static void assertValidParentChild(Tet parent, Tet child) {
        if (!validationEnabled) {
            return;
        }

        if (!isValidParentChild(parent, child)) {
            throw new AssertionError(String.format(INVALID_PARENT_CHILD, describeTet(child), describeTet(parent)));
        }
    }

    /**
     * Assert that a tetrahedron is valid, throwing AssertionError if not. Useful for debugging and test assertions.
     *
     * @param tet the tetrahedron to validate
     * @throws AssertionError if the tetrahedron is invalid
     */
    public static void assertValidTet(Tet tet) {
        if (!validationEnabled) {
            return;
        }

        try {
            validateTet(tet);
        } catch (ValidationException e) {
            throw new AssertionError("Invalid tetrahedron: " + e.getMessage());
        }
    }

    /**
     * Calculate balance factor for the tree. Lower values indicate better balance.
     */
    private static double calculateBalanceFactor(Map<Byte, List<TetreeKey>> levelNodes, byte maxDepth) {
        if (maxDepth == 0) {
            return 0.0;
        }

        // Get leaf nodes (nodes at max depth or with no children)
        var leafNodes = levelNodes.getOrDefault(maxDepth, new ArrayList<>());

        if (leafNodes.isEmpty()) {
            return 0.0;
        }

        // For simplicity, we assume all leaves are at max depth
        // A more sophisticated version would track actual leaf depths
        // and calculate the variance in their depths

        return 0.0; // Currently returns 0 as all leaves are assumed at max depth
    }

    /**
     * Get a human-readable description of a tetrahedron.
     *
     * @param tet the tetrahedron to describe
     * @return descriptive string
     */
    public static String describeTet(Tet tet) {
        return String.format("Tet[x=%d, y=%d, z=%d, level=%d, type=%d, tmIndex=%s]", tet.x(), tet.y(), tet.z(), tet.l(),
                             tet.type(), tet.tmIndex());
    }

    /**
     * Get detailed information about a tetrahedron for debugging.
     *
     * @param tet the tetrahedron to analyze
     * @return detailed debug information
     */
    public static String getDebugInfo(Tet tet) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Tetrahedron Debug Info ===\n");
        sb.append(describeTet(tet)).append("\n");

        // Coordinates
        sb.append("Vertices:\n");
        Point3i[] vertices = tet.coordinates();
        for (int i = 0; i < vertices.length; i++) {
            sb.append(String.format("  v%d: (%d, %d, %d)\n", i, vertices[i].x, vertices[i].y, vertices[i].z));
        }

        // Parent info
        if (tet.l() > 0) {
            try {
                Tet parent = tet.parent();
                sb.append("Parent: ").append(describeTet(parent)).append("\n");
            } catch (Exception e) {
                sb.append("Parent: ERROR - ").append(e.getMessage()).append("\n");
            }
        } else {
            sb.append("Parent: None (root level)\n");
        }

        // Children info (if not at max level)
        if (tet.l() < Constants.getMaxRefinementLevel()) {
            sb.append("Children:\n");
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_TET; i++) {
                try {
                    Tet child = tet.child(i);
                    sb.append(String.format("  Child %d: %s\n", i, describeTet(child)));
                } catch (Exception e) {
                    sb.append(String.format("  Child %d: ERROR - %s\n", i, e.getMessage()));
                }
            }
        }

        // Neighbor info
        sb.append("Face Neighbors:\n");
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            try {
                Tet.FaceNeighbor neighbor = tet.faceNeighbor(face);
                if (neighbor == null) {
                    sb.append(String.format("  Face %d: BOUNDARY (outside positive octant)\n", face));
                } else {
                    sb.append(
                    String.format("  Face %d: %s (face %d)\n", face, describeTet(neighbor.tet()), neighbor.face()));
                }
            } catch (Exception e) {
                sb.append(String.format("  Face %d: ERROR - %s\n", face, e.getMessage()));
            }
        }

        return sb.toString();
    }

    /**
     * Validate that a set of tetrahedra form a valid family. A family consists of 8 siblings that can be merged into
     * their parent.
     *
     * @param tets array of tetrahedra to check
     * @return true if they form a valid family
     */
    public static boolean isValidFamily(Tet[] tets) {
        if (!validationEnabled) {
            return true;
        }
        // Delegate to TetreeFamily to avoid duplication
        return TetreeFamily.isFamily(tets);
    }

    /**
     * Validate a tetrahedral SFC index.
     *
     * @param index the SFC index to validate
     * @return true if the index is valid
     */
    public static boolean isValidIndex(long index) {
        if (!validationEnabled) {
            return true;
        }

        if (index < 0) {
            return false;
        }

        try {
            // Try to create a tetrahedron from the index
            Tet tet = Tet.tetrahedron(index);
            return isValidTet(tet);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate neighbor relationship between two tetrahedra.
     *
     * @param tet1 first tetrahedron
     * @param tet2 second tetrahedron
     * @return true if they are valid neighbors
     */
    public static boolean isValidNeighbor(Tet tet1, Tet tet2) {
        if (!validationEnabled) {
            return true;
        }

        // Neighbors must be at the same level or adjacent levels
        int levelDiff = Math.abs(tet1.l() - tet2.l());
        if (levelDiff > 1) {
            return false;
        }

        // Check if tet2 is a face neighbor of tet1
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet.FaceNeighbor neighbor = tet1.faceNeighbor(face);
            if (neighbor != null && neighbor.tet().equals(tet2)) {
                return true;
            }
        }

        // Check reverse direction
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet.FaceNeighbor neighbor = tet2.faceNeighbor(face);
            if (neighbor != null && neighbor.tet().equals(tet1)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Validate parent-child relationship between two tetrahedra.
     *
     * @param parent the parent tetrahedron
     * @param child  the child tetrahedron
     * @return true if child is a valid child of parent
     */
    public static boolean isValidParentChild(Tet parent, Tet child) {
        if (!validationEnabled) {
            return true;
        }
        // Delegate to TetreeFamily to avoid duplication
        return TetreeFamily.isParentOf(parent, child);
    }

    /**
     * Validate SFC ordering of a list of tetrahedra.
     *
     * @param tets list of tetrahedra to check
     * @return true if they are in valid SFC order
     */
    public static boolean isValidSFCOrder(List<Tet> tets) {
        if (!validationEnabled) {
            return true;
        }

        if (tets.size() < 2) {
            return true;
        }

        // Check that indices are in ascending order
        TetreeKey prevIndex = tets.get(0).tmIndex();
        for (int i = 1; i < tets.size(); i++) {
            TetreeKey currentIndex = tets.get(i).tmIndex();
            if (currentIndex.compareTo(prevIndex) <= 0) {
                return false;
            }
            prevIndex = currentIndex;
        }

        return true;
    }

    /**
     * Validate a single tetrahedron structure.
     *
     * @param tet the tetrahedron to validate
     * @return true if the tetrahedron is valid
     */
    public static boolean isValidTet(Tet tet) {
        if (!validationEnabled) {
            return true;
        }

        try {
            validateTet(tet);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    /**
     * Check if validation is currently enabled.
     *
     * @return true if validation is enabled
     */
    public static boolean isValidationEnabled() {
        return validationEnabled;
    }

    /**
     * Enable or disable validation checks. Validation should be enabled during development and testing, but can be
     * disabled in production for performance.
     *
     * @param enabled true to enable validation, false to disable
     */
    public static void setValidationEnabled(boolean enabled) {
        validationEnabled = enabled;
    }

    /**
     * Validate a batch of tetrahedra indices.
     *
     * @param indices collection of SFC indices
     * @return validation result with any errors found
     */
    public static ValidationResult validateIndices(Collection<Long> indices) {
        if (!validationEnabled) {
            return ValidationResult.valid();
        }

        List<String> errors = new ArrayList<>();

        for (Long index : indices) {
            if (!isValidIndex(index)) {
                errors.add(String.format(INVALID_INDEX, index));
            }
        }

        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    /**
     * Validate a tetrahedron and throw exception if invalid.
     *
     * @param tet the tetrahedron to validate
     * @throws ValidationException if the tetrahedron is invalid
     */
    public static void validateTet(Tet tet) throws ValidationException {
        if (!validationEnabled) {
            return;
        }

        // Check level bounds
        if (tet.l() < 0 || tet.l() > Constants.getMaxRefinementLevel()) {
            throw new ValidationException(String.format(INVALID_LEVEL, tet.l(), Constants.getMaxRefinementLevel()));
        }

        // Check type bounds
        if (tet.type() < 0 || tet.type() >= TetreeConnectivity.TET_TYPES) {
            throw new ValidationException(String.format(INVALID_TYPE, tet.type()));
        }

        // Check coordinate bounds
        if (tet.x() < 0 || tet.y() < 0 || tet.z() < 0) {
            throw new ValidationException(String.format(INVALID_COORDINATES, tet.x(), tet.y(), tet.z()));
        }

        // Check coordinate alignment to grid
        int cellSize = Constants.lengthAtLevel(tet.l());
        if (tet.x() % cellSize != 0 || tet.y() % cellSize != 0 || tet.z() % cellSize != 0) {
            throw new ValidationException(String.format(MISALIGNED_COORDINATES, tet.x(), tet.y(), tet.z(), tet.l()));
        }

        // Check coordinates don't exceed maximum grid size
        int maxCoord = Constants.lengthAtLevel((byte) 0);
        if (tet.x() >= maxCoord || tet.y() >= maxCoord || tet.z() >= maxCoord) {
            throw new ValidationException(String.format(COORDINATES_OUT_OF_BOUNDS, tet.x(), tet.y(), tet.z()));
        }
    }

    /**
     * Validate tree structure consistency by checking a collection of indices. This method validates indices that are
     * known to be in the tree.
     *
     * @param nodeIndices the node indices to validate
     * @return validation result with any errors found
     */
    public static ValidationResult validateTreeStructure(Collection<TetreeKey> nodeIndices) {
        if (!validationEnabled) {
            return ValidationResult.valid();
        }

        List<String> errors = new ArrayList<>();

        // Check each node
        for (var index : nodeIndices) {
            try {
                Tet tet = Tet.tetrahedron(index);
                validateTet(tet);

                // Check parent-child relationships
                if (tet.l() > 0) {
                    Tet parent = tet.parent();
                    if (!nodeIndices.contains(parent.tmIndex()) && parent.l() > 0) {
                        // Parent should exist unless it's the root
                        errors.add(String.format("Orphan node: %s has no parent in tree", describeTet(tet)));
                    }
                }

            } catch (ValidationException e) {
                errors.add(String.format("Invalid node at index %d: %s", index, e.getMessage()));
            }
        }

        // Check for level consistency
        Map<Byte, Long> levelCounts = nodeIndices.stream().collect(
        Collectors.groupingBy(index -> Tet.tetrahedron(index).l(), Collectors.counting()));

        // Verify no gaps in levels
        byte maxLevel = levelCounts.keySet().stream().max(Byte::compare).orElse((byte) 0);
        for (byte level = 0; level <= maxLevel; level++) {
            if (!levelCounts.containsKey(level) && level > 0) {
                errors.add(String.format("Missing nodes at level %d", level));
            }
        }

        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    /**
     * Result of validation operations.
     */
    public static class ValidationResult {
        private final boolean      valid;
        private final List<String> errors;

        private ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors != null ? errors : Collections.emptyList();
        }

        public static ValidationResult invalid(List<String> errors) {
            return new ValidationResult(false, errors);
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public List<String> getErrors() {
            return Collections.unmodifiableList(errors);
        }

        public boolean isValid() {
            return valid;
        }

        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult: VALID";
            } else {
                return "ValidationResult: INVALID\nErrors:\n" + errors.stream().collect(
                Collectors.joining("\n  - ", "  - ", ""));
            }
        }
    }

    /**
     * Tree statistics for analysis.
     */
    public static class TreeStats {
        private final long            totalNodes;
        private final byte            maxDepth;
        private final Map<Byte, Long> levelCounts;
        private final double          balanceFactor;

        public TreeStats(long totalNodes, byte maxDepth, Map<Byte, Long> levelCounts, double balanceFactor) {
            this.totalNodes = totalNodes;
            this.maxDepth = maxDepth;
            this.levelCounts = Collections.unmodifiableMap(levelCounts);
            this.balanceFactor = balanceFactor;
        }

        public double getBalanceFactor() {
            return balanceFactor;
        }

        public Map<Byte, Long> getLevelCounts() {
            return levelCounts;
        }

        public byte getMaxDepth() {
            return maxDepth;
        }

        public long getTotalNodes() {
            return totalNodes;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Tree Statistics:\n");
            sb.append(String.format("  Total nodes: %d\n", totalNodes));
            sb.append(String.format("  Max depth: %d\n", maxDepth));
            sb.append(String.format("  Balance factor: %.2f\n", balanceFactor));
            sb.append("  Nodes per level:\n");

            for (byte level = 0; level <= maxDepth; level++) {
                long count = levelCounts.getOrDefault(level, 0L);
                sb.append(String.format("    Level %2d: %d nodes\n", level, count));
            }

            return sb.toString();
        }
    }

    /**
     * Custom exception for validation errors.
     */
    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
