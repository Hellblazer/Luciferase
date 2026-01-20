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
package com.hellblazer.luciferase.esvo.dag.config;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.CompressionStrategy;

/**
 * Analyzes scene characteristics to recommend compression strategies.
 *
 * <p>Examines octree structure metrics to infer scene type and suggest
 * optimal compression configuration.
 *
 * @author hal.hildebrand
 */
public class SceneTypeDetector {

    /**
     * Scene type classification.
     */
    public enum SceneType {
        /** Static architectural scenes with balanced, deep trees. */
        STATIC,

        /** Dynamic scenes with frequent updates and shallow trees. */
        DYNAMIC,

        /** Mixed-use scenes with both static and dynamic elements. */
        MIXED
    }

    /**
     * Update frequency estimate.
     */
    public enum UpdateFrequency {
        /** Rarely or never updated. */
        RARE,

        /** Occasionally updated (seconds to minutes between updates). */
        OCCASIONAL,

        /** Frequently updated (multiple times per second). */
        FREQUENT
    }

    /**
     * Detect scene type based on octree structure.
     *
     * @param data octree to analyze
     * @return inferred scene type
     */
    public SceneType detectSceneType(ESVOOctreeData data) {
        var nodeCount = data.nodeCount();
        var depth = data.maxDepth();

        // Deep, balanced trees suggest static architectural scenes
        if (depth >= 8 && nodeCount > 1000) {
            return SceneType.STATIC;
        }

        // Shallow trees with minimal nodes suggest dynamic/sparse scenes
        if (depth < 5 || nodeCount < 100) {
            return SceneType.DYNAMIC;
        }

        // Mixed characteristics
        return SceneType.MIXED;
    }

    /**
     * Estimate update frequency based on structure.
     *
     * <p>Currently uses heuristics; future versions may track actual update history.
     *
     * @param data octree to analyze
     * @return estimated update frequency
     */
    public UpdateFrequency estimateUpdateFrequency(ESVOOctreeData data) {
        var sceneType = detectSceneType(data);

        return switch (sceneType) {
            case STATIC -> UpdateFrequency.RARE;
            case DYNAMIC -> UpdateFrequency.FREQUENT;
            case MIXED -> UpdateFrequency.OCCASIONAL;
        };
    }

    /**
     * Recommend compression strategy based on scene analysis.
     *
     * @param data octree to analyze
     * @return recommended compression strategy
     */
    public CompressionStrategy recommendedStrategy(ESVOOctreeData data) {
        var sceneType = detectSceneType(data);

        return switch (sceneType) {
            case STATIC -> CompressionStrategy.CONSERVATIVE;
            case DYNAMIC -> CompressionStrategy.AGGRESSIVE;
            case MIXED -> CompressionStrategy.BALANCED;
        };
    }
}
