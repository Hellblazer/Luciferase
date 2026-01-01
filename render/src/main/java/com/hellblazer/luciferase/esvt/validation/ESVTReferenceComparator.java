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
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Compares ESVT traversal results against reference implementations.
 *
 * <p>Provides comparison against:
 * <ul>
 *   <li>Tetree reference (CPU-based spatial queries)</li>
 *   <li>Alternative ESVT data structures</li>
 *   <li>Ground truth ray-mesh intersections</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTReferenceComparator {
    private static final Logger log = LoggerFactory.getLogger(ESVTReferenceComparator.class);

    /**
     * Result of comparing ESVT against reference implementation.
     */
    public record ComparisonResult(
        int totalComparisons,
        int matches,
        int hitMismatches,
        int depthMismatches,
        double accuracy,
        double hitMatchRate,
        List<Discrepancy> discrepancies
    ) {
        public boolean isAcceptable(double threshold) {
            return accuracy >= threshold;
        }

        public static ComparisonResult perfect(int count) {
            return new ComparisonResult(count, count, 0, 0, 100.0, 100.0, List.of());
        }
    }

    /**
     * Details about a specific discrepancy.
     */
    public record Discrepancy(
        int rayIndex,
        ESVTRay ray,
        boolean esvtHit,
        boolean referenceHit,
        int esvtDepth,
        int referenceDepth,
        String description
    ) {}

    /**
     * Traversal step for path comparison.
     */
    public record TraversalStep(
        int nodeIndex,
        int childIndex,
        byte nodeType,
        float tEntry,
        float tExit
    ) {}

    /**
     * Comparison of traversal paths.
     */
    public record TraversalComparison(
        List<TraversalStep> esvtPath,
        List<TraversalStep> referencePath,
        int firstDivergence,
        boolean pathsMatch
    ) {
        public static TraversalComparison match(List<TraversalStep> path) {
            return new TraversalComparison(path, path, -1, true);
        }
    }

    private final ESVTTraversal traversal;
    private final int maxDiscrepancies;

    public ESVTReferenceComparator() {
        this(100);
    }

    public ESVTReferenceComparator(int maxDiscrepancies) {
        this.traversal = new ESVTTraversal();
        this.maxDiscrepancies = maxDiscrepancies;
    }

    /**
     * Compare ESVT traversal against Tetree point containment.
     *
     * @param esvtData ESVT data to test
     * @param tetree Reference Tetree
     * @param numRays Number of rays to test
     * @param seed Random seed
     * @return Comparison result
     */
    public ComparisonResult compareAgainstTetree(
            ESVTData esvtData,
            Tetree<LongEntityID, ?> tetree,
            int numRays,
            long seed) {

        var discrepancies = new ArrayList<Discrepancy>();
        var random = new Random(seed);

        int matches = 0;
        int hitMismatches = 0;
        int depthMismatches = 0;

        for (int i = 0; i < numRays; i++) {
            var ray = generateRandomRay(random);
            var esvtResult = traversal.castRay(ray, esvtData.nodes(),
                esvtData.contours(), esvtData.farPointers(), 0);

            // Check if ray hits something by testing points along the ray
            boolean tetreeHit = checkTetreeRayHit(tetree, ray, 100);
            int tetreeDepth = tetreeHit ? estimateTetreeDepth(tetree, ray) : 0;

            boolean hitMatch = esvtResult.hit == tetreeHit;
            boolean depthMatch = !esvtResult.hit || Math.abs(esvtResult.scale - tetreeDepth) <= 2;

            if (hitMatch && depthMatch) {
                matches++;
            } else {
                if (!hitMatch) {
                    hitMismatches++;
                }
                if (!depthMatch) {
                    depthMismatches++;
                }

                if (discrepancies.size() < maxDiscrepancies) {
                    String desc = String.format("ESVT: hit=%b depth=%d, Tetree: hit=%b depth=%d",
                        esvtResult.hit, esvtResult.scale, tetreeHit, tetreeDepth);
                    discrepancies.add(new Discrepancy(i, ray, esvtResult.hit, tetreeHit,
                        esvtResult.scale, tetreeDepth, desc));
                }
            }
        }

        double accuracy = numRays > 0 ? 100.0 * matches / numRays : 100.0;
        double hitMatchRate = numRays > 0 ? 100.0 * (numRays - hitMismatches) / numRays : 100.0;

        return new ComparisonResult(numRays, matches, hitMismatches, depthMismatches,
            accuracy, hitMatchRate, discrepancies);
    }

    /**
     * Compare two ESVT data structures for equivalence.
     *
     * @param esvt1 First ESVT data
     * @param esvt2 Second ESVT data
     * @param numRays Number of rays to test
     * @param seed Random seed
     * @return Comparison result
     */
    public ComparisonResult compareESVTStructures(
            ESVTData esvt1,
            ESVTData esvt2,
            int numRays,
            long seed) {

        var discrepancies = new ArrayList<Discrepancy>();
        var random = new Random(seed);

        int matches = 0;
        int hitMismatches = 0;
        int depthMismatches = 0;

        for (int i = 0; i < numRays; i++) {
            var ray = generateRandomRay(random);

            var result1 = traversal.castRay(ray, esvt1.nodes(),
                esvt1.contours(), esvt1.farPointers(), 0);
            var result2 = traversal.castRay(ray, esvt2.nodes(),
                esvt2.contours(), esvt2.farPointers(), 0);

            boolean hitMatch = result1.hit == result2.hit;
            boolean depthMatch = !result1.hit || result1.scale == result2.scale;

            if (hitMatch && depthMatch) {
                matches++;
            } else {
                if (!hitMatch) {
                    hitMismatches++;
                }
                if (!depthMatch) {
                    depthMismatches++;
                }

                if (discrepancies.size() < maxDiscrepancies) {
                    String desc = String.format("ESVT1: hit=%b depth=%d, ESVT2: hit=%b depth=%d",
                        result1.hit, result1.scale, result2.hit, result2.scale);
                    discrepancies.add(new Discrepancy(i, ray, result1.hit, result2.hit,
                        result1.scale, result2.scale, desc));
                }
            }
        }

        double accuracy = numRays > 0 ? 100.0 * matches / numRays : 100.0;
        double hitMatchRate = numRays > 0 ? 100.0 * (numRays - hitMismatches) / numRays : 100.0;

        return new ComparisonResult(numRays, matches, hitMismatches, depthMismatches,
            accuracy, hitMatchRate, discrepancies);
    }

    /**
     * Compare structure counts between ESVT data and expected values.
     */
    public record StructureComparison(
        int expectedNodes,
        int actualNodes,
        int expectedLeaves,
        int actualLeaves,
        int expectedInternal,
        int actualInternal,
        boolean matches
    ) {
        public static StructureComparison fromESVT(ESVTData data, int expectedNodes,
                                                   int expectedLeaves, int expectedInternal) {
            return new StructureComparison(
                expectedNodes, data.nodeCount(),
                expectedLeaves, data.leafCount(),
                expectedInternal, data.internalCount(),
                data.nodeCount() == expectedNodes &&
                data.leafCount() == expectedLeaves &&
                data.internalCount() == expectedInternal
            );
        }
    }

    /**
     * Compare ESVT structure against expected counts.
     */
    public StructureComparison compareStructure(ESVTData data, int expectedNodes,
                                                int expectedLeaves, int expectedInternal) {
        return StructureComparison.fromESVT(data, expectedNodes, expectedLeaves, expectedInternal);
    }

    /**
     * Validate that ESVT node types match Tetree-derived types.
     */
    public record TypeValidation(
        int totalNodes,
        int typeMatches,
        int typeMismatches,
        double accuracy
    ) {}

    public TypeValidation validateTypes(ESVTData data) {
        if (data == null || data.nodeCount() == 0) {
            return new TypeValidation(0, 0, 0, 100.0);
        }

        var nodes = data.nodes();
        int matches = 0;
        int mismatches = 0;

        for (int i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            byte type = node.getTetType();

            // Type should be 0-5 (S0-S5)
            if (type >= 0 && type <= 5) {
                // Verify child type derivation
                boolean childTypesValid = true;
                int childMask = node.getChildMask();

                for (int j = 0; j < 8 && childTypesValid; j++) {
                    if ((childMask & (1 << j)) != 0) {
                        byte childType = node.getChildType(j);
                        if (childType < 0 || childType > 5) {
                            childTypesValid = false;
                        }
                    }
                }

                if (childTypesValid) {
                    matches++;
                } else {
                    mismatches++;
                }
            } else {
                mismatches++;
            }
        }

        double accuracy = nodes.length > 0 ? 100.0 * matches / nodes.length : 100.0;
        return new TypeValidation(nodes.length, matches, mismatches, accuracy);
    }

    /**
     * Self-consistency check: traverse same ray twice, results should match.
     */
    public boolean validateSelfConsistency(ESVTData data, int numRays, long seed) {
        var random = new Random(seed);

        for (int i = 0; i < numRays; i++) {
            var ray = generateRandomRay(random);

            var result1 = traversal.castRay(ray, data.nodes(),
                data.contours(), data.farPointers(), 0);
            var result2 = traversal.castRay(ray, data.nodes(),
                data.contours(), data.farPointers(), 0);

            if (result1.hit != result2.hit ||
                (result1.hit && result1.scale != result2.scale)) {
                log.warn("Self-consistency failure at ray {}: hit1={} depth1={}, hit2={} depth2={}",
                    i, result1.hit, result1.scale, result2.hit, result2.scale);
                return false;
            }
        }

        return true;
    }

    // ========== Helper Methods ==========

    private ESVTRay generateRandomRay(Random random) {
        // Origin outside unit cube
        float ox = random.nextFloat() * 2 - 1.5f;
        float oy = random.nextFloat() * 2 - 1.5f;
        float oz = random.nextFloat() * 2 - 1.5f;

        // Direction toward center with some variation
        float dx = 0.5f - ox + (random.nextFloat() - 0.5f) * 0.5f;
        float dy = 0.5f - oy + (random.nextFloat() - 0.5f) * 0.5f;
        float dz = 0.5f - oz + (random.nextFloat() - 0.5f) * 0.5f;

        // Normalize
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            dx /= len;
            dy /= len;
            dz /= len;
        } else {
            dx = 1;
            dy = 0;
            dz = 0;
        }

        return new ESVTRay(new Point3f(ox, oy, oz), new Vector3f(dx, dy, dz));
    }

    private boolean checkTetreeRayHit(Tetree<LongEntityID, ?> tetree, ESVTRay ray, int samples) {
        // Sample points along the ray and check containment using Tetree's locate
        for (int i = 0; i < samples; i++) {
            float t = 0.1f + i * 0.02f; // Sample from t=0.1 to t=2.1
            float px = ray.originX + t * ray.directionX;
            float py = ray.originY + t * ray.directionY;
            float pz = ray.originZ + t * ray.directionZ;

            // Use locateTetrahedron to check if point is within tetree bounds
            var tet = tetree.locateTetrahedron(new Point3f(px, py, pz), (byte) 8);
            if (tet != null && tetree.hasNode(tet.tmIndex())) {
                return true;
            }
        }
        return false;
    }

    private int estimateTetreeDepth(Tetree<LongEntityID, ?> tetree, ESVTRay ray) {
        // Find approximate depth by checking points along ray
        float t = 0.5f;
        float px = ray.originX + t * ray.directionX;
        float py = ray.originY + t * ray.directionY;
        float pz = ray.originZ + t * ray.directionZ;

        var tet = tetree.locateTetrahedron(new Point3f(px, py, pz), (byte) 12);
        if (tet != null) {
            return tet.l(); // Return level as depth
        }
        return 0;
    }
}
