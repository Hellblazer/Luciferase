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
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;

/**
 * Beam optimization for ESVT ray traversal.
 *
 * <p>Implements a two-pass rendering algorithm that exploits ray coherence
 * to reduce traversal overhead:
 *
 * <ol>
 *   <li><b>Coarse pass:</b> Render at reduced resolution (1/4 by default),
 *       storing minimum t-values (first hit distances)</li>
 *   <li><b>Fine pass:</b> For each fine ray, sample surrounding coarse pixels
 *       and use the minimum t-value as a conservative starting point</li>
 * </ol>
 *
 * <p>This optimization typically reduces traversal by 30-50% by skipping
 * empty space that was already confirmed empty by the coarse pass.
 *
 * <p><b>Reference:</b> Adapted from ESVO (Efficient Sparse Voxel Octrees)
 * beam optimization technique.
 *
 * @author hal.hildebrand
 */
public final class ESVTBeamOptimization {

    /** Default coarse size (renders at 1/coarseSize resolution) */
    public static final int DEFAULT_COARSE_SIZE = 4;

    /** Safety epsilon for conservative t-min (0.99x) */
    private static final float SAFETY_EPSILON = 0.99f;

    /** Missing t-value marker */
    private static final float NO_HIT = Float.MAX_VALUE;

    private final int coarseSize;
    private final int coarseWidth;
    private final int coarseHeight;
    private final float[] coarseBuffer;

    private final ESVTTraversal traversal;

    /**
     * Create beam optimization for given frame dimensions.
     *
     * @param frameWidth Full resolution width
     * @param frameHeight Full resolution height
     * @param coarseSize Resolution divisor (default 4 = 1/4 resolution)
     */
    public ESVTBeamOptimization(int frameWidth, int frameHeight, int coarseSize) {
        this.coarseSize = coarseSize;
        // Add 1 to ensure coverage at boundaries
        this.coarseWidth = (frameWidth + coarseSize - 1) / coarseSize + 1;
        this.coarseHeight = (frameHeight + coarseSize - 1) / coarseSize + 1;
        this.coarseBuffer = new float[coarseWidth * coarseHeight];
        this.traversal = ESVTTraversal.create();
        clearCoarseBuffer();
    }

    /**
     * Create beam optimization with default coarse size (4).
     */
    public ESVTBeamOptimization(int frameWidth, int frameHeight) {
        this(frameWidth, frameHeight, DEFAULT_COARSE_SIZE);
    }

    /**
     * Clear the coarse buffer (reset to no-hit state).
     */
    public void clearCoarseBuffer() {
        for (int i = 0; i < coarseBuffer.length; i++) {
            coarseBuffer[i] = NO_HIT;
        }
    }

    /**
     * Execute coarse pass for a set of rays.
     *
     * <p>Casts rays at coarse resolution and stores t-min values.
     *
     * @param rays Array of rays covering the coarse grid
     * @param nodes ESVT nodes
     * @param rootIdx Root node index
     */
    public void executeCoarsePass(ESVTRay[] rays, ESVTNodeUnified[] nodes, int rootIdx) {
        clearCoarseBuffer();

        for (int cy = 0; cy < coarseHeight; cy++) {
            for (int cx = 0; cx < coarseWidth; cx++) {
                int coarseIdx = cy * coarseWidth + cx;
                if (coarseIdx < rays.length) {
                    var result = traversal.castRay(rays[coarseIdx], nodes, rootIdx);
                    coarseBuffer[coarseIdx] = result.hit ? result.t : NO_HIT;
                }
            }
        }
    }

    /**
     * Get conservative t-min for a fine pixel by sampling coarse neighbors.
     *
     * <p>Samples a 2x2 region of coarse pixels and returns the minimum t-value
     * multiplied by a safety factor (0.99).
     *
     * @param fineX Fine pixel X coordinate
     * @param fineY Fine pixel Y coordinate
     * @return Conservative t-min value, or 0 if no coarse data available
     */
    public float getConservativeTmin(int fineX, int fineY) {
        int coarseX = fineX / coarseSize;
        int coarseY = fineY / coarseSize;

        float minT = NO_HIT;

        // Sample 2x2 coarse pixels
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int cx = coarseX + dx;
                int cy = coarseY + dy;

                if (cx >= 0 && cx < coarseWidth && cy >= 0 && cy < coarseHeight) {
                    int coarseIdx = cy * coarseWidth + cx;
                    float t = coarseBuffer[coarseIdx];
                    if (t < minT) {
                        minT = t;
                    }
                }
            }
        }

        // Return conservative value (slightly before the coarse hit)
        if (minT < NO_HIT) {
            return minT * SAFETY_EPSILON;
        }
        return 0.0f; // No coarse data, start from beginning
    }

    /**
     * Apply beam optimization to a ray before traversal.
     *
     * @param ray The ray to optimize
     * @param fineX Fine pixel X coordinate
     * @param fineY Fine pixel Y coordinate
     */
    public void applyToRay(ESVTRay ray, int fineX, int fineY) {
        float conservativeTmin = getConservativeTmin(fineX, fineY);
        if (conservativeTmin > ray.tMin) {
            ray.tMin = conservativeTmin;
        }
    }

    /**
     * Execute fine pass with beam optimization.
     *
     * <p>Casts rays using coarse data to skip empty space.
     *
     * @param rays Array of fine rays
     * @param pixelCoords Array of [x,y] pixel coordinates for each ray
     * @param nodes ESVT nodes
     * @param rootIdx Root node index
     * @return Array of traversal results
     */
    public ESVTResult[] executeFinePass(ESVTRay[] rays, int[][] pixelCoords,
                                        ESVTNodeUnified[] nodes, int rootIdx) {
        var results = new ESVTResult[rays.length];

        for (int i = 0; i < rays.length; i++) {
            var ray = rays[i];
            if (pixelCoords != null && i < pixelCoords.length) {
                applyToRay(ray, pixelCoords[i][0], pixelCoords[i][1]);
            }
            results[i] = traversal.castRay(ray, nodes, rootIdx);
        }

        return results;
    }

    /**
     * Execute full two-pass beam-optimized rendering.
     *
     * @param coarseRays Rays for coarse pass
     * @param fineRays Rays for fine pass
     * @param finePixelCoords Pixel coordinates for fine rays
     * @param nodes ESVT nodes
     * @param rootIdx Root node index
     * @return Fine pass results
     */
    public ESVTResult[] executeBeamOptimizedRender(
            ESVTRay[] coarseRays, ESVTRay[] fineRays, int[][] finePixelCoords,
            ESVTNodeUnified[] nodes, int rootIdx) {

        // Coarse pass
        executeCoarsePass(coarseRays, nodes, rootIdx);

        // Fine pass with beam optimization
        return executeFinePass(fineRays, finePixelCoords, nodes, rootIdx);
    }

    /**
     * Get statistics about beam optimization effectiveness.
     */
    public BeamStats getStats() {
        int coarseHits = 0;
        float totalT = 0;

        for (float t : coarseBuffer) {
            if (t < NO_HIT) {
                coarseHits++;
                totalT += t;
            }
        }

        return new BeamStats(
            coarseWidth * coarseHeight,
            coarseHits,
            coarseHits > 0 ? totalT / coarseHits : 0
        );
    }

    // Accessors
    public int getCoarseWidth() { return coarseWidth; }
    public int getCoarseHeight() { return coarseHeight; }
    public int getCoarseSize() { return coarseSize; }
    public float[] getCoarseBuffer() { return coarseBuffer; }

    /**
     * Statistics about beam optimization effectiveness.
     */
    public record BeamStats(int coarsePixels, int coarseHits, float avgTmin) {
        public float hitRate() {
            return coarsePixels > 0 ? (float) coarseHits / coarsePixels : 0;
        }

        @Override
        public String toString() {
            return String.format("BeamStats[pixels=%d, hits=%d (%.1f%%), avgTmin=%.3f]",
                coarsePixels, coarseHits, hitRate() * 100, avgTmin);
        }
    }
}
