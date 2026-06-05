package com.hellblazer.luciferase.render.tile;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTree;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HybridTileDispatcher.
 *
 * <p>Verifies that dispatchFrame correctly routes tiles to GPU-batch, GPU-single, and CPU
 * execution paths, and that the reported gpuTimeNs/cpuTimeNs are sequential (non-overlapping)
 * spans — i.e. the "hybrid" name refers to work partitioning across three execution paths,
 * not concurrent CPU/GPU overlap.
 */
class HybridTileDispatcherTest {

    private static final int FRAME_WIDTH  = 64;
    private static final int FRAME_HEIGHT = 64;
    private static final int TILE_SIZE    = 16; // 4×4 = 16 tiles

    private TileConfiguration    tileConfig;
    private TrackingHybridExecutor executor;
    private FixedCoherenceAnalyzer coherenceAnalyzer;
    private StubBeamTreeFactory   beamTreeFactory;

    @BeforeEach
    void setUp() {
        tileConfig         = TileConfiguration.from(FRAME_WIDTH, FRAME_HEIGHT, TILE_SIZE);
        executor           = new TrackingHybridExecutor();
        coherenceAnalyzer  = new FixedCoherenceAnalyzer(0.5);
        beamTreeFactory    = new StubBeamTreeFactory();
    }

    // -------------------------------------------------------------------
    // Routing tests
    // -------------------------------------------------------------------

    /**
     * High coherence (>= 0.7 default) → GPU_BATCH.
     */
    @Test
    void highCoherenceRoutesToGpuBatch() {
        coherenceAnalyzer.coherence = 0.9; // above highCoherenceThreshold
        var dispatcher = new HybridTileDispatcher(tileConfig, coherenceAnalyzer, beamTreeFactory);
        var rays       = makeRays(FRAME_WIDTH, FRAME_HEIGHT);

        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, executor);

        assertTrue(executor.gpuBatchCalls > 0, "GPU batch must be called for high-coherence tiles");
        assertEquals(0, executor.gpuSingleCalls, "GPU single must not be called");
        assertEquals(0, executor.cpuCalls,       "CPU must not be called");
        assertEquals(16, metrics.totalTiles());
        assertEquals(16, metrics.gpuBatchTiles());
        assertEquals(0,  metrics.gpuSingleTiles());
        assertEquals(0,  metrics.cpuTiles());
    }

    /**
     * Medium coherence (0.3–0.7) → GPU_SINGLE.
     */
    @Test
    void mediumCoherenceRoutesToGpuSingle() {
        coherenceAnalyzer.coherence = 0.5;
        var dispatcher = new HybridTileDispatcher(tileConfig, coherenceAnalyzer, beamTreeFactory);
        var rays       = makeRays(FRAME_WIDTH, FRAME_HEIGHT);

        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, executor);

        assertEquals(0, executor.gpuBatchCalls, "GPU batch must not be called");
        assertTrue(executor.gpuSingleCalls > 0, "GPU single must be called for medium-coherence tiles");
        assertEquals(0, executor.cpuCalls,      "CPU must not be called");
        assertEquals(16, metrics.gpuSingleTiles());
    }

    /**
     * Low coherence (< 0.3) → CPU.
     */
    @Test
    void lowCoherenceRoutesToCpu() {
        coherenceAnalyzer.coherence = 0.1;
        var dispatcher = new HybridTileDispatcher(tileConfig, coherenceAnalyzer, beamTreeFactory);
        var rays       = makeRays(FRAME_WIDTH, FRAME_HEIGHT);

        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, executor);

        assertEquals(0, executor.gpuBatchCalls,  "GPU batch must not be called");
        assertEquals(0, executor.gpuSingleCalls, "GPU single must not be called");
        assertTrue(executor.cpuCalls > 0,        "CPU must be called for low-coherence tiles");
        assertEquals(16, metrics.cpuTiles());
    }

    /**
     * When GPU is saturated (>= 0.8) and coherence is in GPU-single range, falls back to CPU.
     */
    @Test
    void gpuSaturationForcesRoutingToCpu() {
        coherenceAnalyzer.coherence = 0.5; // would normally be GPU_SINGLE
        executor.gpuSaturation      = 0.9; // saturated → prefer CPU

        var dispatcher = new HybridTileDispatcher(tileConfig, coherenceAnalyzer, beamTreeFactory);
        var rays       = makeRays(FRAME_WIDTH, FRAME_HEIGHT);

        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, executor);

        assertEquals(0, executor.gpuSingleCalls,
                "GPU single must not be called when GPU is saturated");
        assertTrue(executor.cpuCalls > 0, "CPU must absorb work when GPU is saturated");
        assertEquals(16, metrics.cpuTiles());
    }

    // -------------------------------------------------------------------
    // Timing contract: sequential (non-overlapping) spans
    // -------------------------------------------------------------------

    /**
     * gpuTimeNs + cpuTimeNs ≤ dispatchTimeNs.
     *
     * <p>Execution is strictly sequential: all GPU tiles run first, then all CPU tiles.
     * Both timing windows are sequential spans measured with System.nanoTime(); they
     * cannot sum to more than the total dispatch window. This test asserts the invariant
     * that documents the sequential nature of the "hybrid" execution.
     */
    @Test
    void gpuAndCpuTimingsAreSequentialSpansWithinTotalDispatch() {
        // Mix: half high-coherence (GPU batch), half low-coherence (CPU)
        var alternating = new AlternatingCoherenceAnalyzer(0.9, 0.1);
        var dispatcher  = new HybridTileDispatcher(tileConfig, alternating, beamTreeFactory);
        var rays        = makeRays(FRAME_WIDTH, FRAME_HEIGHT);

        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, executor);

        // Both GPU and CPU paths must have been used
        assertTrue(metrics.gpuBatchTiles() > 0, "Should have GPU-batch tiles");
        assertTrue(metrics.cpuTiles() > 0,      "Should have CPU tiles");

        // Sequential invariant: the sum of the two spans must not exceed total dispatch time
        // (they are measured from start-of-GPU to end-of-GPU and start-of-CPU to end-of-CPU,
        //  so their sum equals total dispatch time minus any overhead between the two phases)
        assertTrue(metrics.gpuTimeNs() >= 0, "gpuTimeNs must be non-negative");
        assertTrue(metrics.cpuTimeNs() >= 0, "cpuTimeNs must be non-negative");
        assertTrue(metrics.dispatchTimeNs() >= 0, "dispatchTimeNs must be non-negative");
        // Sum cannot exceed total (sequential, not concurrent)
        assertTrue(metrics.gpuTimeNs() + metrics.cpuTimeNs() <= metrics.dispatchTimeNs() + 1_000_000L,
                "gpuTimeNs + cpuTimeNs must not exceed dispatchTimeNs (sequential execution): "
                + "gpu=" + metrics.gpuTimeNs() + " cpu=" + metrics.cpuTimeNs()
                + " total=" + metrics.dispatchTimeNs());
    }

    // -------------------------------------------------------------------
    // Metrics invariants
    // -------------------------------------------------------------------

    /**
     * gpuBatchTiles + gpuSingleTiles + cpuTiles == totalTiles.
     */
    @Test
    void metricsTileCountsSum() {
        var dispatcher = new HybridTileDispatcher(tileConfig, coherenceAnalyzer, beamTreeFactory);
        var rays       = makeRays(FRAME_WIDTH, FRAME_HEIGHT);

        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, executor);

        assertEquals(metrics.totalTiles(),
                     metrics.gpuBatchTiles() + metrics.gpuSingleTiles() + metrics.cpuTiles(),
                     "Tile counts must sum to totalTiles");
    }

    /**
     * gpuRatio + cpuRatio == 1.0 (when total > 0).
     */
    @Test
    void metricsRatiosSumToOne() {
        var dispatcher = new HybridTileDispatcher(tileConfig, coherenceAnalyzer, beamTreeFactory);
        var rays       = makeRays(FRAME_WIDTH, FRAME_HEIGHT);

        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, executor);

        assertEquals(1.0, metrics.gpuRatio() + metrics.cpuRatio(), 0.001,
                "gpuRatio + cpuRatio must equal 1.0");
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static Ray[] makeRays(int w, int h) {
        var rays = new Ray[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                rays[y * w + x] = new Ray(new Point3f(x, y, 0), new Vector3f(0, 0, 1));
            }
        }
        return rays;
    }

    // -------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------

    private static class TrackingHybridExecutor implements HybridKernelExecutor {
        int    gpuBatchCalls  = 0;
        int    gpuSingleCalls = 0;
        int    cpuCalls       = 0;
        double gpuSaturation  = 0.0;

        @Override
        public void executeBatch(Ray[] rays, int[] rayIndices, int raysPerItem) {
            gpuBatchCalls++;
        }

        @Override
        public void executeSingleRay(Ray[] rays, int[] rayIndices) {
            gpuSingleCalls++;
        }

        @Override
        public void executeCPU(Ray[] rays, int[] rayIndices) {
            cpuCalls++;
        }

        @Override
        public RayResult getResult(int rayIndex) {
            return new RayResult(0f, 0f, 1f, 1.0f);
        }

        @Override
        public double getGPUSaturation() {
            return gpuSaturation;
        }

        @Override
        public boolean supportsCPU() {
            return true;
        }
    }

    private static class FixedCoherenceAnalyzer implements HybridTileDispatcher.CoherenceAnalyzer {
        double coherence;

        FixedCoherenceAnalyzer(double coherence) {
            this.coherence = coherence;
        }

        @Override
        public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
            return coherence;
        }
    }

    private static class AlternatingCoherenceAnalyzer implements HybridTileDispatcher.CoherenceAnalyzer {
        private final double high;
        private final double low;
        private       int    count = 0;

        AlternatingCoherenceAnalyzer(double high, double low) {
            this.high = high;
            this.low  = low;
        }

        @Override
        public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
            return (count++ % 2 == 0) ? high : low;
        }
    }

    private static class StubBeamTreeFactory implements HybridTileDispatcher.BeamTreeFactory {
        @Override
        public BeamTree buildBeamTree(Ray[] rays, int[] rayIndices, DAGOctreeData dag, double coherenceScore) {
            return null; // not needed for routing/timing tests
        }
    }
}
