package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first development for beam optimization.
 * Beam optimization groups coherent rays to reduce redundant traversal.
 */
@DisplayName("Beam Optimizer Tests")
class BeamOptimizerTest {
    
    private static final float EPSILON = 0.001f;
    
    @Test
    @DisplayName("Should create beam from coherent rays")
    void testBeamCreation() {
        var optimizer = new BeamOptimizer();
        
        // Create 2x2 coherent rays (screen-space neighbors)
        float[][] origins = {
            {0, 0, -5, 1},
            {0.1f, 0, -5, 1},
            {0, 0.1f, -5, 1},
            {0.1f, 0.1f, -5, 1}
        };
        
        float[][] directions = {
            {0, 0, 1, 0},
            {0.01f, 0, 1, 0},
            {0, 0.01f, 1, 0},
            {0.01f, 0.01f, 1, 0}
        };
        
        var beam = optimizer.createBeam(origins, directions);
        
        assertNotNull(beam);
        assertEquals(4, beam.getRayCount());
        assertTrue(beam.isCoherent(), "Rays should be coherent");
    }
    
    @Test
    @DisplayName("Should detect non-coherent rays")
    void testNonCoherentRays() {
        var optimizer = new BeamOptimizer();
        
        // Create divergent rays
        float[][] origins = {
            {0, 0, -5, 1},
            {10, 0, -5, 1},  // Far apart
            {0, 10, -5, 1},  // Far apart
            {10, 10, -5, 1}
        };
        
        float[][] directions = {
            {0, 0, 1, 0},
            {-1, 0, 0, 0},  // Different direction
            {0, -1, 0, 0},  // Different direction
            {1, 1, 0, 0}    // Different direction
        };
        
        var beam = optimizer.createBeam(origins, directions);
        
        assertNotNull(beam);
        assertFalse(beam.isCoherent(), "Rays should not be coherent");
    }
    
    @Test
    @DisplayName("Should compute beam frustum bounds")
    void testBeamFrustum() {
        var optimizer = new BeamOptimizer();
        
        // Create simple beam
        float[][] origins = {
            {-1, -1, 0, 1},
            {1, -1, 0, 1},
            {-1, 1, 0, 1},
            {1, 1, 0, 1}
        };
        
        float[][] directions = {
            {0, 0, 1, 0},
            {0, 0, 1, 0},
            {0, 0, 1, 0},
            {0, 0, 1, 0}
        };
        
        var beam = optimizer.createBeam(origins, directions);
        var frustum = beam.getFrustum();
        
        assertNotNull(frustum);
        
        // Check near plane bounds
        float[] nearMin = frustum.getNearMin();
        float[] nearMax = frustum.getNearMax();
        
        assertEquals(-1, nearMin[0], EPSILON);
        assertEquals(-1, nearMin[1], EPSILON);
        assertEquals(0, nearMin[2], EPSILON);
        
        assertEquals(1, nearMax[0], EPSILON);
        assertEquals(1, nearMax[1], EPSILON);
        assertEquals(0, nearMax[2], EPSILON);
    }
    
    @Test
    @DisplayName("Should track beam traversal state")
    void testBeamTraversalState() {
        var optimizer = new BeamOptimizer();
        
        float[][] origins = {{0, 0, 0, 1}};
        float[][] directions = {{0, 0, 1, 0}};
        
        var beam = optimizer.createBeam(origins, directions);
        var traversalState = beam.createTraversalState();
        
        assertNotNull(traversalState);
        assertEquals(0, traversalState.getCurrentDepth());
        assertFalse(traversalState.isComplete());
        
        // Simulate traversal
        traversalState.pushNode(100, 0.5f, 1.0f);
        assertEquals(1, traversalState.getCurrentDepth());
        
        traversalState.popNode();
        assertEquals(0, traversalState.getCurrentDepth());
    }
    
    @Test
    @DisplayName("Should compute beam-box intersection")
    void testBeamBoxIntersection() {
        var optimizer = new BeamOptimizer();
        
        // Beam going straight down Z axis
        float[][] origins = {
            {-0.1f, -0.1f, -1, 1},
            {0.1f, -0.1f, -1, 1},
            {-0.1f, 0.1f, -1, 1},
            {0.1f, 0.1f, -1, 1}
        };
        
        float[][] directions = {
            {0, 0, 1, 0},
            {0, 0, 1, 0},
            {0, 0, 1, 0},
            {0, 0, 1, 0}
        };
        
        var beam = optimizer.createBeam(origins, directions);
        
        // Box at origin
        float[] boxMin = {-1, -1, 0};
        float[] boxMax = {1, 1, 2};
        
        var intersection = beam.intersectBox(boxMin, boxMax);
        
        assertTrue(intersection.intersects());
        assertEquals(1.0f, intersection.getTMin(), EPSILON);
        assertEquals(3.0f, intersection.getTMax(), EPSILON);
    }
    
    @Test
    @DisplayName("Should handle beam splitting for divergence")
    void testBeamSplitting() {
        var optimizer = new BeamOptimizer();
        
        // Create slightly divergent rays that might need splitting deeper in tree
        float[][] origins = new float[16][4];
        float[][] directions = new float[16][4];
        
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int idx = i * 4 + j;
                origins[idx] = new float[]{i * 0.1f, j * 0.1f, -5, 1};
                // Small divergence
                directions[idx] = new float[]{
                    i * 0.01f - 0.015f,
                    j * 0.01f - 0.015f,
                    1, 0
                };
            }
        }
        
        var beam = optimizer.createBeam(origins, directions);
        
        // Should be able to split into sub-beams
        var subBeams = beam.split(2, 2); // Split into 2x2 sub-beams
        
        assertEquals(4, subBeams.length);
        for (var subBeam : subBeams) {
            assertEquals(4, subBeam.getRayCount());
        }
    }
    
    @Test
    @DisplayName("Should compute beam coherence metric")
    void testCoherenceMetric() {
        var optimizer = new BeamOptimizer();
        
        // Perfectly coherent rays
        float[][] coherentOrigins = {
            {0, 0, 0, 1},
            {0.01f, 0, 0, 1}
        };
        float[][] coherentDirs = {
            {0, 0, 1, 0},
            {0, 0, 1, 0}
        };
        
        var coherentBeam = optimizer.createBeam(coherentOrigins, coherentDirs);
        float coherence = coherentBeam.getCoherenceMetric();
        assertEquals(1.0f, coherence, 0.1f, "Perfect coherence should be ~1.0");
        
        // Divergent rays
        float[][] divergentOrigins = {
            {0, 0, 0, 1},
            {10, 10, 0, 1}
        };
        float[][] divergentDirs = {
            {0, 0, 1, 0},
            {1, 1, 0, 0}
        };
        
        var divergentBeam = optimizer.createBeam(divergentOrigins, divergentDirs);
        float divergence = divergentBeam.getCoherenceMetric();
        assertTrue(divergence < 0.5f, "Divergent rays should have low coherence");
    }
    
    @Test
    @DisplayName("Should optimize traversal order for beam")
    void testTraversalOrderOptimization() {
        var optimizer = new BeamOptimizer();
        
        float[][] origins = {
            {0, 0, 0, 1},
            {1, 0, 0, 1}
        };
        float[][] directions = {
            {0, 0, 1, 0},
            {0, 0, 1, 0}
        };
        
        var beam = optimizer.createBeam(origins, directions);
        
        // Get optimized child traversal order for octree node
        int[] childOrder = beam.getOptimalChildOrder(
            new float[]{-1, -1, 0},  // node min
            new float[]{1, 1, 2}      // node max
        );
        
        assertNotNull(childOrder);
        assertEquals(8, childOrder.length);
        
        // Verify it's a valid permutation
        boolean[] seen = new boolean[8];
        for (int child : childOrder) {
            assertTrue(child >= 0 && child < 8);
            assertFalse(seen[child], "Each child should appear exactly once");
            seen[child] = true;
        }
    }
    
    @Test
    @DisplayName("Should merge beam results")
    void testBeamResultMerging() {
        var optimizer = new BeamOptimizer();
        
        float[][] origins = {
            {0, 0, 0, 1},
            {0.1f, 0, 0, 1},
            {0, 0.1f, 0, 1},
            {0.1f, 0.1f, 0, 1}
        };
        float[][] directions = {
            {0, 0, 1, 0},
            {0, 0, 1, 0},
            {0, 0, 1, 0},
            {0, 0, 1, 0}
        };
        
        var beam = optimizer.createBeam(origins, directions);
        
        // Simulate hit results for each ray
        float[][] hitResults = {
            {1, 2, 3, 1},  // Ray 0 hit
            {2, 3, 4, 1},  // Ray 1 hit
            {0, 0, 0, 0},  // Ray 2 miss
            {3, 4, 5, 1}   // Ray 3 hit
        };
        
        beam.setResults(hitResults);
        
        // Check individual results
        for (int i = 0; i < 4; i++) {
            var result = beam.getRayResult(i);
            assertArrayEquals(hitResults[i], result, "Result should match");
        }
        
        // Check statistics
        assertEquals(3, beam.getHitCount());
        assertEquals(1, beam.getMissCount());
    }
}