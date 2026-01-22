package com.hellblazer.luciferase.render.tile;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTree;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TileBasedDispatcher.
 * Tests tile partitioning, coherence measurement, and adaptive kernel dispatch.
 */
class TileBasedDispatcherTest {

    private TileConfiguration config;
    private TileBasedDispatcher dispatcher;
    private TestKernelExecutor testExecutor;
    private TestCoherenceAnalyzer testCoherenceAnalyzer;
    private TestBeamTreeFactory testBeamTreeFactory;

    private static final double COHERENCE_THRESHOLD = 0.7;
    private static final int FRAME_WIDTH = 256;
    private static final int FRAME_HEIGHT = 256;
    private static final int TILE_SIZE = 16;

    @BeforeEach
    void setUp() {
        config = TileConfiguration.from(FRAME_WIDTH, FRAME_HEIGHT, TILE_SIZE);
        testExecutor = new TestKernelExecutor();
        testCoherenceAnalyzer = new TestCoherenceAnalyzer();
        testBeamTreeFactory = new TestBeamTreeFactory();

        dispatcher = new TileBasedDispatcher(config, COHERENCE_THRESHOLD,
                                              testCoherenceAnalyzer, testBeamTreeFactory);
    }

    /**
     * Test #1: testPartitionIntoTiles
     * Verify ray array partitions correctly into tiles.
     */
    @Test
    void testPartitionIntoTiles() {
        // Given: 256×256 frame with 16×16 tile size (16×16 = 256 tiles)
        var rays = createRayArray(FRAME_WIDTH, FRAME_HEIGHT);

        // When: Partition rays into tiles
        var tiles = dispatcher.partitionIntoTiles(rays, FRAME_WIDTH, FRAME_HEIGHT);

        // Then: All 256 tiles created with correct parameters
        assertEquals(256, tiles.size(), "Should have 256 tiles for 16x16 grid");

        // Verify all rays accounted for
        int totalRays = tiles.stream().mapToInt(Tile::rayCount).sum();
        assertEquals(FRAME_WIDTH * FRAME_HEIGHT, totalRays, "All rays should be accounted for");
    }

    /**
     * Test #2: testMeasurePerTileCoherence
     * Verify coherence analysis per tile.
     */
    @Test
    void testMeasurePerTileCoherence() {
        // Given: Create rays
        var rays = createRayArray(FRAME_WIDTH, FRAME_HEIGHT);
        testCoherenceAnalyzer.setCoherenceValue(0.75); // High coherence

        // When: Dispatch frame (which measures coherence)
        var metrics = dispatcher.dispatchFrame(rays, FRAME_WIDTH, FRAME_HEIGHT, null, testExecutor);

        // Then: Coherence analyzer should be called for each tile
        assertTrue(testCoherenceAnalyzer.getCallCount() > 0, "Coherence analyzer should be called");

        // Average coherence should match what we set
        assertEquals(0.75, metrics.avgCoherence(), 0.01, "Average coherence should match");
    }

    /**
     * Test #3: testDispatchHighCoherenceTile
     * Route high-coherence tile to batch kernel.
     */
    @Test
    void testDispatchHighCoherenceTile() {
        // Given: Single tile with high coherence (0.8 > 0.7 threshold)
        var smallConfig = TileConfiguration.from(16, 16, 16); // Single 16x16 tile
        var smallDispatcher = new TileBasedDispatcher(smallConfig, COHERENCE_THRESHOLD,
                                                       testCoherenceAnalyzer, testBeamTreeFactory);
        var rays = createRayArray(16, 16);
        testCoherenceAnalyzer.setCoherenceValue(0.8);

        // When: Dispatch frame
        smallDispatcher.dispatchFrame(rays, 16, 16, null, testExecutor);

        // Then: executeBatch should be called
        assertTrue(testExecutor.batchCallCount > 0, "Batch kernel should be called");
        assertEquals(0, testExecutor.singleRayCallCount, "Single-ray kernel should NOT be called");
    }

    /**
     * Test #4: testDispatchLowCoherenceTile
     * Route low-coherence tile to single-ray kernel.
     */
    @Test
    void testDispatchLowCoherenceTile() {
        // Given: Single tile with low coherence (0.5 < 0.7 threshold)
        var smallConfig = TileConfiguration.from(16, 16, 16); // Single 16x16 tile
        var smallDispatcher = new TileBasedDispatcher(smallConfig, COHERENCE_THRESHOLD,
                                                       testCoherenceAnalyzer, testBeamTreeFactory);
        var rays = createRayArray(16, 16);
        testCoherenceAnalyzer.setCoherenceValue(0.5);

        // When: Dispatch frame
        smallDispatcher.dispatchFrame(rays, 16, 16, null, testExecutor);

        // Then: executeSingleRay should be called
        assertTrue(testExecutor.singleRayCallCount > 0, "Single-ray kernel should be called");
        assertEquals(0, testExecutor.batchCallCount, "Batch kernel should NOT be called");
    }

    /**
     * Test #5: testMixedDispatch
     * Mix of batch and single-ray tiles in single frame.
     */
    @Test
    void testMixedDispatch() {
        // Given: 4x4 tile frame (16 tiles) with alternating coherence
        var mixedConfig = TileConfiguration.from(64, 64, 16); // 4x4 tiles
        var alternatingAnalyzer = new AlternatingCoherenceAnalyzer(0.8, 0.5);
        var mixedDispatcher = new TileBasedDispatcher(mixedConfig, COHERENCE_THRESHOLD,
                                                       alternatingAnalyzer, testBeamTreeFactory);
        var rays = createRayArray(64, 64);

        // When: Dispatch frame
        var metrics = mixedDispatcher.dispatchFrame(rays, 64, 64, null, testExecutor);

        // Then: Should have 16 total tiles
        assertEquals(16, metrics.totalTiles(), "Should have 16 tiles for 4x4 grid");

        // Should have both batch and single-ray tiles
        assertTrue(metrics.batchTiles() > 0, "Should have some batch tiles");
        assertTrue(metrics.singleRayTiles() > 0, "Should have some single-ray tiles");

        // Both execution paths should be used
        assertTrue(testExecutor.batchCallCount > 0, "Batch should be called");
        assertTrue(testExecutor.singleRayCallCount > 0, "Single-ray should be called");
    }

    /**
     * Test #6: testMetrics
     * Verify DispatchMetrics calculated correctly.
     */
    @Test
    void testMetrics() {
        // Given: Frame with predictable coherence distribution
        var testConfig = TileConfiguration.from(64, 64, 16); // 4x4 = 16 tiles
        var countingAnalyzer = new CountingCoherenceAnalyzer(10); // First 10 high, rest low
        var testDispatcher = new TileBasedDispatcher(testConfig, COHERENCE_THRESHOLD,
                                                      countingAnalyzer, testBeamTreeFactory);
        var rays = createRayArray(64, 64);

        // When: Dispatch frame
        var metrics = testDispatcher.dispatchFrame(rays, 64, 64, null, testExecutor);

        // Then: Verify all metrics
        assertEquals(16, metrics.totalTiles(), "Total tiles should be 16");
        assertEquals(10, metrics.batchTiles(), "Should have 10 batch tiles");
        assertEquals(6, metrics.singleRayTiles(), "Should have 6 single-ray tiles");

        double expectedRatio = 10.0 / 16.0; // 0.625
        assertEquals(expectedRatio, metrics.batchRatio(), 0.01, "Batch ratio should be 0.625");

        // Dispatch time should be measured
        assertTrue(metrics.dispatchTimeNs() > 0, "Dispatch time should be positive");
    }

    /**
     * Test #7: testEmptyTile
     * Handle tiles with no rays gracefully.
     */
    @Test
    void testEmptyTile() {
        // Given: Frame smaller than tile size (8×8 rays, 16×16 tile)
        var smallConfig = TileConfiguration.from(8, 8, 16);
        var smallDispatcher = new TileBasedDispatcher(smallConfig, COHERENCE_THRESHOLD,
                                                       testCoherenceAnalyzer, testBeamTreeFactory);
        var rays = createRayArray(8, 8);
        testCoherenceAnalyzer.setCoherenceValue(0.7);

        // When: Dispatch frame
        var metrics = smallDispatcher.dispatchFrame(rays, 8, 8, null, testExecutor);

        // Then: Only 1 tile created with 64 rays
        assertEquals(1, metrics.totalTiles(), "Should have only 1 tile");

        // No exceptions thrown
        assertNotNull(metrics, "Metrics should not be null");
    }

    /**
     * Test #8: testSingleRayTile
     * Handle 1-ray tiles correctly.
     */
    @Test
    void testSingleRayTile() {
        // Given: 1×1 ray frame with 16×16 tile size
        var tinyConfig = TileConfiguration.from(1, 1, 16);
        var tinyDispatcher = new TileBasedDispatcher(tinyConfig, COHERENCE_THRESHOLD,
                                                      testCoherenceAnalyzer, testBeamTreeFactory);
        var rays = createRayArray(1, 1);
        testCoherenceAnalyzer.setCoherenceValue(0.5);

        // When: Dispatch frame
        var metrics = tinyDispatcher.dispatchFrame(rays, 1, 1, null, testExecutor);

        // Then: Tile created with rayCount=1
        assertEquals(1, metrics.totalTiles(), "Should have 1 tile");

        // executeSingleRay should be called (1 ray below batch threshold)
        assertTrue(testExecutor.singleRayCallCount > 0, "Single-ray should be called");
    }

    /**
     * Helper: Create ray array in scanline order.
     */
    private Ray[] createRayArray(int width, int height) {
        var rays = new Ray[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                var origin = new Point3f(x, y, 0);
                var direction = new Vector3f(0, 0, 1);
                rays[index] = new Ray(origin, direction);
            }
        }
        return rays;
    }

    // Test implementations

    private static class TestKernelExecutor implements KernelExecutor {
        int batchCallCount = 0;
        int singleRayCallCount = 0;

        @Override
        public void executeBatch(Ray[] rays, int[] rayIndices, int raysPerItem) {
            batchCallCount++;
        }

        @Override
        public void executeSingleRay(Ray[] rays, int[] rayIndices) {
            singleRayCallCount++;
        }

        @Override
        public RayResult getResult(int rayIndex) {
            return new com.hellblazer.luciferase.render.tile.RayResult(0f, 0f, 1f, 1.0f);
        }
    }

    private static class TestCoherenceAnalyzer implements TileBasedDispatcher.CoherenceAnalyzer {
        private double coherenceValue = 0.5;
        private int callCount = 0;

        void setCoherenceValue(double value) {
            this.coherenceValue = value;
        }

        int getCallCount() {
            return callCount;
        }

        @Override
        public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
            callCount++;
            return coherenceValue;
        }
    }

    private static class AlternatingCoherenceAnalyzer implements TileBasedDispatcher.CoherenceAnalyzer {
        private final double highValue;
        private final double lowValue;
        private int callCount = 0;

        AlternatingCoherenceAnalyzer(double highValue, double lowValue) {
            this.highValue = highValue;
            this.lowValue = lowValue;
        }

        @Override
        public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
            callCount++;
            return (callCount % 2 == 1) ? highValue : lowValue;
        }
    }

    private static class CountingCoherenceAnalyzer implements TileBasedDispatcher.CoherenceAnalyzer {
        private final int highCoherenceTileCount;
        private int callCount = 0;

        CountingCoherenceAnalyzer(int highCoherenceTileCount) {
            this.highCoherenceTileCount = highCoherenceTileCount;
        }

        @Override
        public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
            callCount++;
            return callCount <= highCoherenceTileCount ? 0.8 : 0.5;
        }
    }

    private static class TestBeamTreeFactory implements TileBasedDispatcher.BeamTreeFactory {
        @Override
        public BeamTree buildBeamTree(Ray[] rays, int[] rayIndices, DAGOctreeData dag, double coherenceScore) {
            return null; // Not needed for these tests
        }
    }
}
