package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GPUMetricsCollector.
 * Validates frame lifecycle, kernel timing, thread-safety, and snapshot generation.
 */
class GPUMetricsCollectorTest {

    private GPUMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new GPUMetricsCollector(5); // Small window for testing
    }

    @Test
    void testDefaultWindowSize() {
        var defaultCollector = new GPUMetricsCollector();
        assertNotNull(defaultCollector.getSnapshot());
    }

    @Test
    void testBeginEndFrame() {
        collector.beginFrame();

        // Simulate some delay
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        collector.endFrame();

        var snapshot = collector.getSnapshot();

        // Should have recorded one frame
        assertTrue(snapshot.currentFps() > 0);
        assertTrue(snapshot.avgFrameTimeMs() > 0);
    }

    @Test
    void testRecordKernelTiming() {
        collector.beginFrame();

        // Record kernel executions
        var start1 = System.nanoTime();
        var end1 = start1 + 1_500_000L; // 1.5ms
        collector.recordKernelTiming("batch_kernel", start1, end1);

        var start2 = System.nanoTime();
        var end2 = start2 + 500_000L; // 0.5ms
        collector.recordKernelTiming("single_ray", start2, end2);

        collector.endFrame();

        var snapshot = collector.getSnapshot();

        // Should have frame data
        assertTrue(snapshot.avgFrameTimeMs() >= 0);
    }

    @Test
    void testRecordBeamTreeStats() {
        // Mock BeamTree stats using CoherenceSnapshot directly
        collector.beginFrame();

        var coherence = new CoherenceSnapshot(0.65, 0.2, 0.9, 100, 5);
        collector.recordBeamTreeStats(coherence);

        collector.endFrame();

        var snapshot = collector.getSnapshot();

        // Verify coherence data was captured
        assertEquals(0.65, snapshot.coherence().averageCoherence(), 0.01);
        assertEquals(100, snapshot.coherence().totalBeams());
    }

    @Test
    void testRecordKernelSelection() {
        // Mock kernel selection stats using DispatchMetrics directly
        collector.beginFrame();

        var dispatch = DispatchMetrics.from(100, 75, 25);
        collector.recordKernelSelection(dispatch);

        collector.endFrame();

        var snapshot = collector.getSnapshot();

        // Verify dispatch data was captured
        assertEquals(100, snapshot.dispatch().totalDispatches());
        assertEquals(75, snapshot.dispatch().batchDispatches());
        assertEquals(25, snapshot.dispatch().singleRayDispatches());
    }

    @Test
    void testMultipleFrames() {
        // Record 3 frames
        for (int i = 0; i < 3; i++) {
            collector.beginFrame();

            var coherence = new CoherenceSnapshot(
                0.5 + i * 0.1,  // Increasing coherence
                0.2, 0.9,
                50 + i * 10,    // Increasing beam count
                4
            );
            collector.recordBeamTreeStats(coherence);

            var dispatch = DispatchMetrics.from(100, 70 + i * 5, 30 - i * 5);
            collector.recordKernelSelection(dispatch);

            collector.endFrame();
        }

        var snapshot = collector.getSnapshot();

        // Verify aggregated data
        assertTrue(snapshot.currentFps() > 0);
        assertTrue(snapshot.coherence().averageCoherence() > 0);
        assertTrue(snapshot.dispatch().totalDispatches() > 0);
    }

    @Test
    void testReset() {
        // Record some data
        collector.beginFrame();
        collector.recordBeamTreeStats(new CoherenceSnapshot(0.5, 0.2, 0.8, 50, 4));
        collector.recordKernelSelection(DispatchMetrics.from(100, 75, 25));
        collector.endFrame();

        var before = collector.getSnapshot();
        assertTrue(before.currentFps() > 0);

        // Reset
        collector.reset();

        var after = collector.getSnapshot();

        // Should be empty
        assertEquals(0.0, after.currentFps(), 0.001);
        assertEquals(CoherenceSnapshot.empty(), after.coherence());
        assertEquals(DispatchMetrics.empty(), after.dispatch());
    }

    @Test
    void testGetSnapshotThreadSafe() throws InterruptedException {
        // Multiple threads reading snapshot while one writes
        var threads = new Thread[4];

        // Writer thread
        threads[0] = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                collector.beginFrame();
                collector.recordBeamTreeStats(new CoherenceSnapshot(0.5, 0.2, 0.8, 10, 3));
                collector.recordKernelSelection(DispatchMetrics.from(10, 5, 5));
                collector.endFrame();

                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Reader threads
        for (int t = 1; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    var snapshot = collector.getSnapshot();
                    assertNotNull(snapshot);
                    assertNotNull(snapshot.coherence());
                    assertNotNull(snapshot.dispatch());
                }
            });
        }

        // Start all threads
        for (var thread : threads) {
            thread.start();
        }

        // Wait for completion
        for (var thread : threads) {
            thread.join();
        }

        // Final snapshot should be valid
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot);
    }

    @Test
    void testBeginFrameWithoutEnd() {
        collector.beginFrame();

        // Getting snapshot before endFrame should not crash
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot);
    }

    @Test
    void testEndFrameWithoutBegin() {
        // End frame without begin should be handled gracefully
        assertDoesNotThrow(() -> collector.endFrame());

        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot);
    }

    @Test
    void testMultipleBeginWithoutEnd() {
        collector.beginFrame();
        collector.beginFrame(); // Second begin should replace first

        collector.endFrame();

        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot);
    }

    @Test
    void testEmptyFrameRecorded() {
        // Frame with no kernel or beam data
        collector.beginFrame();
        collector.endFrame();

        var snapshot = collector.getSnapshot();

        // Should have frame timing but empty metrics
        assertTrue(snapshot.avgFrameTimeMs() >= 0);
        assertEquals(CoherenceSnapshot.empty(), snapshot.coherence());
        assertEquals(DispatchMetrics.empty(), snapshot.dispatch());
    }

    @Test
    void testRecordDataWithoutFrame() {
        // Record data outside of frame lifecycle
        collector.recordBeamTreeStats(new CoherenceSnapshot(0.5, 0.2, 0.8, 50, 4));
        collector.recordKernelSelection(DispatchMetrics.from(100, 75, 25));

        // Should not crash
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot);
    }

    @Test
    void testAccumulatesDispatchesAcrossKernels() {
        collector.beginFrame();

        // Record multiple kernel selections in same frame
        collector.recordKernelSelection(DispatchMetrics.from(50, 40, 10));
        collector.recordKernelSelection(DispatchMetrics.from(30, 20, 10));

        collector.endFrame();

        var snapshot = collector.getSnapshot();

        // Should accumulate: 50+30=80 total, 40+20=60 batch, 10+10=20 single
        assertEquals(80, snapshot.dispatch().totalDispatches());
        assertEquals(60, snapshot.dispatch().batchDispatches());
        assertEquals(20, snapshot.dispatch().singleRayDispatches());
    }
}
