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
package com.hellblazer.luciferase.esvt.validation;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Validates ESVT tree structure integrity.
 *
 * <p>Performs comprehensive validation including:
 * <ul>
 *   <li>Node validity checks (childMask, leafMask patterns)</li>
 *   <li>Child pointer bounds checking</li>
 *   <li>Tetrahedron type propagation correctness (S0-S5)</li>
 *   <li>Leaf/internal node count consistency</li>
 *   <li>Far pointer resolution</li>
 *   <li>Contour data consistency</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTStructureValidator {
    private static final Logger log = LoggerFactory.getLogger(ESVTStructureValidator.class);

    /**
     * Validation result containing detailed error information.
     */
    public record ValidationResult(
        boolean valid,
        int totalNodes,
        int invalidNodes,
        int pointerErrors,
        int typeErrors,
        int countMismatches,
        int farPointerErrors,
        int contourErrors,
        List<String> errors,
        List<String> warnings
    ) {
        public double accuracy() {
            if (totalNodes == 0) return 100.0;
            int totalErrors = invalidNodes + pointerErrors + typeErrors +
                             countMismatches + farPointerErrors + contourErrors;
            return 100.0 * (totalNodes - totalErrors) / totalNodes;
        }

        public static ValidationResult success(int totalNodes) {
            return new ValidationResult(true, totalNodes, 0, 0, 0, 0, 0, 0,
                                       List.of(), List.of());
        }
    }

    /**
     * Validation configuration options.
     */
    public record Config(
        boolean validatePointers,
        boolean validateTypes,
        boolean validateCounts,
        boolean validateFarPointers,
        boolean validateContours,
        boolean strictMode,
        int maxErrorsToReport
    ) {
        public static Config defaultConfig() {
            return new Config(true, true, true, true, true, false, 100);
        }

        public static Config strictConfig() {
            return new Config(true, true, true, true, true, true, 1000);
        }

        public Config withStrictMode(boolean strict) {
            return new Config(validatePointers, validateTypes, validateCounts,
                            validateFarPointers, validateContours, strict, maxErrorsToReport);
        }
    }

    private final Config config;

    public ESVTStructureValidator() {
        this(Config.defaultConfig());
    }

    public ESVTStructureValidator(Config config) {
        this.config = config;
    }

    /**
     * Validate the entire ESVT structure.
     *
     * @param data The ESVT data to validate
     * @return Validation result with detailed error information
     */
    public ValidationResult validate(ESVTData data) {
        if (data == null) {
            return new ValidationResult(false, 0, 0, 0, 0, 0, 0, 0,
                List.of("ESVTData is null"), List.of());
        }

        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var nodes = data.nodes();

        if (nodes == null || nodes.length == 0) {
            // Empty tree is valid
            return ValidationResult.success(0);
        }

        int invalidNodes = 0;
        int pointerErrors = 0;
        int typeErrors = 0;
        int farPointerErrors = 0;
        int contourErrors = 0;

        // Phase 1: Validate individual nodes
        for (int i = 0; i < nodes.length; i++) {
            var node = nodes[i];

            // Check node validity
            if (!node.isValid()) {
                invalidNodes++;
                if (errors.size() < config.maxErrorsToReport) {
                    errors.add(String.format("Node %d is invalid (no children or leaves)", i));
                }
            }

            // Check type range (S0-S5)
            if (config.validateTypes) {
                int type = node.getTetType();
                if (type < 0 || type > 5) {
                    typeErrors++;
                    if (errors.size() < config.maxErrorsToReport) {
                        errors.add(String.format("Node %d has invalid type %d (expected 0-5)", i, type));
                    }
                }
            }

            // Check pointer bounds
            if (config.validatePointers && node.getChildMask() != 0) {
                int childPtr = node.getChildPtr();
                int childCount = node.getChildCount();

                if (node.isFar()) {
                    // Far pointer - validate against far pointer array
                    if (config.validateFarPointers) {
                        if (data.farPointers() == null || childPtr >= data.farPointers().length) {
                            farPointerErrors++;
                            if (errors.size() < config.maxErrorsToReport) {
                                errors.add(String.format("Node %d has far pointer %d but far pointer array has %d entries",
                                    i, childPtr, data.farPointerCount()));
                            }
                        } else {
                            int resolved = data.farPointers()[childPtr];
                            if (resolved < 0 || resolved + childCount > nodes.length) {
                                farPointerErrors++;
                                if (errors.size() < config.maxErrorsToReport) {
                                    errors.add(String.format("Node %d far pointer resolves to out-of-bounds index %d",
                                        i, resolved));
                                }
                            }
                        }
                    }
                } else {
                    // Direct pointer
                    if (childPtr < 0 || i + childPtr + childCount > nodes.length) {
                        pointerErrors++;
                        if (errors.size() < config.maxErrorsToReport) {
                            errors.add(String.format("Node %d has out-of-bounds child pointer (ptr=%d, count=%d, nodes=%d)",
                                i, childPtr, childCount, nodes.length));
                        }
                    }
                }
            }

            // Validate leaf mask is subset of child mask
            if (node.getChildMask() != 0) {
                int childMask = node.getChildMask();
                int leafMask = node.getLeafMask();
                if ((leafMask & ~childMask) != 0) {
                    invalidNodes++;
                    if (errors.size() < config.maxErrorsToReport) {
                        errors.add(String.format("Node %d leaf mask (0x%02X) has bits outside child mask (0x%02X)",
                            i, leafMask, childMask));
                    }
                }
            }

            // Validate contour pointer
            if (config.validateContours && node.hasContour()) {
                int contourPtr = node.getContourPtr();
                int contourMask = node.getContourMask();
                int contourCount = Integer.bitCount(contourMask);

                if (data.contours() == null || contourPtr + contourCount > data.contourCount()) {
                    contourErrors++;
                    if (errors.size() < config.maxErrorsToReport) {
                        errors.add(String.format("Node %d has invalid contour pointer (ptr=%d, count=%d, total=%d)",
                            i, contourPtr, contourCount, data.contourCount()));
                    }
                }
            }
        }

        // Phase 2: Validate counts
        int countMismatches = 0;
        if (config.validateCounts) {
            int actualLeaves = 0;
            int actualInternal = 0;

            for (var node : nodes) {
                if (node.getChildMask() == 0) {
                    actualLeaves++;
                } else {
                    actualInternal++;
                }
            }

            if (actualLeaves != data.leafCount()) {
                countMismatches++;
                errors.add(String.format("Leaf count mismatch: reported %d, actual %d",
                    data.leafCount(), actualLeaves));
            }

            if (actualInternal != data.internalCount()) {
                countMismatches++;
                errors.add(String.format("Internal count mismatch: reported %d, actual %d",
                    data.internalCount(), actualInternal));
            }
        }

        // Phase 3: Validate type propagation
        if (config.validateTypes) {
            typeErrors += validateTypePropagation(data, errors, warnings);
        }

        // Phase 4: Root node validation
        if (nodes.length > 0) {
            var root = nodes[0];
            // Root should be type 0 in S0 tree
            if (root.getTetType() != 0) {
                warnings.add(String.format("Root node has type %d, expected 0 for S0 tree", root.getTetType()));
            }
        }

        int totalErrors = invalidNodes + pointerErrors + typeErrors +
                         countMismatches + farPointerErrors + contourErrors;
        boolean valid = !config.strictMode || totalErrors == 0;

        if (totalErrors > 0) {
            log.debug("ESVT validation found {} errors in {} nodes", totalErrors, nodes.length);
        }

        return new ValidationResult(valid, nodes.length, invalidNodes, pointerErrors,
            typeErrors, countMismatches, farPointerErrors, contourErrors, errors, warnings);
    }

    /**
     * Validate type propagation through the tree.
     * Child types should match TetreeConnectivity lookup tables.
     */
    private int validateTypePropagation(ESVTData data, List<String> errors, List<String> warnings) {
        int typeErrors = 0;
        var nodes = data.nodes();

        // Track visited nodes to detect cycles
        var visited = new BitSet(nodes.length);
        var queue = new ArrayDeque<Integer>();
        queue.add(0);

        while (!queue.isEmpty()) {
            int idx = queue.poll();
            if (idx < 0 || idx >= nodes.length || visited.get(idx)) {
                continue;
            }
            visited.set(idx);

            var node = nodes[idx];
            int parentType = node.getTetType();
            int childMask = node.getChildMask();

            if (childMask == 0) {
                continue; // Leaf node
            }

            // Validate each child's type
            for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
                if ((childMask & (1 << mortonIdx)) == 0) {
                    continue; // No child at this position
                }

                int leafMask = node.getLeafMask();
                if ((leafMask & (1 << mortonIdx)) != 0) {
                    continue; // Child is a leaf, no further children to check
                }

                // Get the child index
                int childIdx;
                try {
                    childIdx = node.getChildIndex(mortonIdx, idx, data.farPointers());
                } catch (Exception e) {
                    continue; // Already reported as pointer error
                }

                if (childIdx < 0 || childIdx >= nodes.length) {
                    continue; // Out of bounds, already reported
                }

                var childNode = nodes[childIdx];
                byte expectedType = node.getChildType(mortonIdx);
                byte actualType = childNode.getTetType();

                if (expectedType != actualType) {
                    typeErrors++;
                    if (errors.size() < config.maxErrorsToReport) {
                        errors.add(String.format(
                            "Type propagation error: node %d (type %d) child %d has type %d, expected %d",
                            idx, parentType, mortonIdx, actualType, expectedType));
                    }
                }

                queue.add(childIdx);
            }
        }

        return typeErrors;
    }

    /**
     * Quick validation - only checks critical errors.
     */
    public boolean quickValidate(ESVTData data) {
        if (data == null || data.nodes() == null) {
            return false;
        }

        var nodes = data.nodes();
        if (nodes.length == 0) {
            return true;
        }

        // Quick checks only
        for (var node : nodes) {
            if (!node.isValid()) {
                return false;
            }
            int type = node.getTetType();
            if (type < 0 || type > 5) {
                return false;
            }
        }

        return true;
    }

    /**
     * Validate a single node in isolation.
     */
    public List<String> validateNode(ESVTNodeUnified node, int nodeIndex) {
        var errors = new ArrayList<String>();

        if (node == null) {
            errors.add("Node is null");
            return errors;
        }

        if (!node.isValid()) {
            errors.add("Node is invalid (no children or leaves)");
        }

        int type = node.getTetType();
        if (type < 0 || type > 5) {
            errors.add(String.format("Invalid type %d (expected 0-5)", type));
        }

        int childMask = node.getChildMask();
        int leafMask = node.getLeafMask();

        if (childMask != 0 && (leafMask & ~childMask) != 0) {
            errors.add(String.format("Leaf mask (0x%02X) exceeds child mask (0x%02X)", leafMask, childMask));
        }

        return errors;
    }
}
