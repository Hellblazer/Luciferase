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

import com.hellblazer.luciferase.esvo.core.ESVORay;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.1 P1: Coherence Profiler Test Suite
 *
 * Tests detailed coherence analysis for GPU performance profiling.
 *
 * @author hal.hildebrand
 */
@DisplayName("CoherenceProfiler Tests")
class CoherenceProfilerTest {

    private CoherenceProfiler profiler;
    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        profiler = new CoherenceProfiler();

        // Create test DAG (simple 2-level octree)
        var svo = TestOctreeFactory.createSimpleTestOctree();
        testDAG = DAGBuilder.from(svo).build();
    }

    @Test
    @DisplayName("Create coherence profiler")
    void testCreateProfiler() {
        assertNotNull(profiler);
    }

    @Test
    @DisplayName("Analyze coherent ray batch")
    void testAnalyzeCoherentBatch() {
        // Create coherent rays (all pointing in same direction)
        var rays = createCoherentRays(100);

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertNotNull(metrics);
        assertTrue(metrics.coherenceScore() > 0.5, "Coherent rays should have high score");
        assertTrue(metrics.uniqueNodesVisited() > 0);
        assertTrue(metrics.totalNodeVisits() >= metrics.uniqueNodesVisited());
    }

    @Test
    @DisplayName("Analyze incoherent ray batch")
    void testAnalyzeIncoherentBatch() {
        // Create incoherent rays (random directions)
        var rays = createIncoherentRays(100);

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertNotNull(metrics);
        // Incoherent rays should have lower coherence than coherent rays
        assertTrue(metrics.coherenceScore() >= 0.0);
        assertTrue(metrics.coherenceScore() <= 1.0);
    }

    @Test
    @DisplayName("Analyze empty ray batch")
    void testAnalyzeEmptyBatch() {
        var rays = new ESVORay[0];

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertNotNull(metrics);
        assertEquals(0.0, metrics.coherenceScore(), 0.001);
        assertEquals(0, metrics.uniqueNodesVisited());
        assertEquals(0, metrics.totalNodeVisits());
    }

    @Test
    @DisplayName("Analyze single ray")
    void testAnalyzeSingleRay() {
        var rays = new ESVORay[]{createRay(0.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f)};

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertNotNull(metrics);
        assertEquals(1.0, metrics.coherenceScore(), 0.001);
    }

    @Test
    @DisplayName("Upper level sharing detected for coherent rays")
    void testUpperLevelSharing() {
        var rays = createCoherentRays(100);

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertTrue(metrics.upperLevelSharingPercent() > 0.0,
                   "Coherent rays should share upper-level nodes");
    }

    @Test
    @DisplayName("Depth distribution calculated")
    void testDepthDistribution() {
        var rays = createCoherentRays(50);

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertNotNull(metrics.depthDistribution());
        assertTrue(metrics.depthDistribution().length > 0);

        // At least root level should have visits
        assertTrue(metrics.depthDistribution()[0] > 0);
    }

    @Test
    @DisplayName("Cache reuse factor calculated correctly")
    void testCacheReuseFactor() {
        var rays = createCoherentRays(100);

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        var reuseFactor = metrics.cacheReuseFactor();
        assertTrue(reuseFactor >= 1.0, "Reuse factor should be >= 1.0");

        // For coherent rays, reuse should be > 1 (nodes visited multiple times)
        assertTrue(reuseFactor > 1.0, "Coherent rays should have cache reuse > 1.0");
    }

    @Test
    @DisplayName("Coherence metrics meet threshold")
    void testCoherenceThreshold() {
        var rays = createCoherentRays(100);
        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertTrue(metrics.meetsThreshold(0.5),
                   "Coherent rays should meet 0.5 threshold");

        var incoherentRays = createIncoherentRays(100);
        var incoherentMetrics = profiler.analyzeDetailed(incoherentRays, testDAG);

        // May or may not meet threshold depending on randomness
        assertNotNull(incoherentMetrics);
    }

    @Test
    @DisplayName("Total node visits >= unique nodes")
    void testNodeVisitCounts() {
        var rays = createCoherentRays(50);

        var metrics = profiler.analyzeDetailed(rays, testDAG);

        assertTrue(metrics.totalNodeVisits() >= metrics.uniqueNodesVisited(),
                   "Total visits must be >= unique nodes");
    }

    @Test
    @DisplayName("Coherence score normalized to [0, 1]")
    void testCoherenceScoreNormalized() {
        var coherentRays = createCoherentRays(100);
        var coherentMetrics = profiler.analyzeDetailed(coherentRays, testDAG);

        assertTrue(coherentMetrics.coherenceScore() >= 0.0);
        assertTrue(coherentMetrics.coherenceScore() <= 1.0);

        var incoherentRays = createIncoherentRays(100);
        var incoherentMetrics = profiler.analyzeDetailed(incoherentRays, testDAG);

        assertTrue(incoherentMetrics.coherenceScore() >= 0.0);
        assertTrue(incoherentMetrics.coherenceScore() <= 1.0);
    }

    @Test
    @DisplayName("Profiler extends RayCoherenceAnalyzer")
    void testExtendsRayCoherenceAnalyzer() {
        // CoherenceProfiler should extend RayCoherenceAnalyzer
        assertTrue(profiler instanceof com.hellblazer.luciferase.esvo.gpu.beam.RayCoherenceAnalyzer,
                   "CoherenceProfiler should extend RayCoherenceAnalyzer");
    }

    @Test
    @DisplayName("Analyze coherence matches base implementation")
    void testAnalyzeCoherenceConsistency() {
        var rays = createCoherentRays(50);

        // Call base analyzeCoherence method
        var baseCoherence = profiler.analyzeCoherence(rays, testDAG);

        // Call detailed analysis
        var detailedMetrics = profiler.analyzeDetailed(rays, testDAG);

        // Coherence scores should match
        assertEquals(baseCoherence, detailedMetrics.coherenceScore(), 0.01,
                     "Detailed analysis should match base coherence calculation");
    }

    // Helper methods

    private ESVORay[] createCoherentRays(int count) {
        var rays = new ESVORay[count];
        for (var i = 0; i < count; i++) {
            // All rays point in same direction (coherent)
            rays[i] = createRay(-1.0f, 0.5f + i * 0.01f, 0.5f, 1.0f, 0.0f, 0.0f);
        }
        return rays;
    }

    private ESVORay[] createIncoherentRays(int count) {
        var rays = new ESVORay[count];
        for (var i = 0; i < count; i++) {
            // Random directions (incoherent)
            var angle = (i * 137.5f) % 360.0f; // Golden angle for distribution
            var radians = (float) Math.toRadians(angle);
            var dx = (float) Math.cos(radians);
            var dy = (float) Math.sin(radians);
            rays[i] = createRay(-1.0f, 0.5f, 0.5f, dx, dy, 0.0f);
        }
        return rays;
    }

    private ESVORay createRay(float ox, float oy, float oz, float dx, float dy, float dz) {
        return new ESVORay(ox, oy, oz, dx, dy, dz);
    }
}
