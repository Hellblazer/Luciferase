package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsAggregator.
 * Validates rolling window behavior, aggregation accuracy, and thread-safety.
 */
class MetricsAggregatorTest {

    private MetricsAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new MetricsAggregator(5); // 5-frame window for testing
    }

    @Test
    void testDefaultWindowSize() {
        var defaultAggregator = new MetricsAggregator();
        assertEquals(0, defaultAggregator.getFrameCount());

        // Window size is internal, but we can verify it works
        for (int i = 0; i < MetricsAggregator.DEFAULT_WINDOW_SIZE + 10; i++) {
            defaultAggregator.addFrame(
                16_666_667L,
                CoherenceSnapshot.empty(),
                DispatchMetrics.empty()
            );
        }

        // Should not exceed window size
        assertTrue(defaultAggregator.getFrameCount() <= MetricsAggregator.DEFAULT_WINDOW_SIZE);
    }

    @Test
    void testRollingWindowCapacity() {
        assertEquals(0, aggregator.getFrameCount());

        // Add frames up to capacity
        for (int i = 0; i < 5; i++) {
            aggregator.addFrame(
                16_666_667L,
                new CoherenceSnapshot(0.5, 0.2, 0.8, 10, 3),
                DispatchMetrics.from(10, 5, 5)
            );
        }

        assertEquals(5, aggregator.getFrameCount());

        // Add more frames - should maintain window size
        for (int i = 0; i < 10; i++) {
            aggregator.addFrame(
                16_666_667L,
                new CoherenceSnapshot(0.5, 0.2, 0.8, 10, 3),
                DispatchMetrics.from(10, 5, 5)
            );
        }

        assertEquals(5, aggregator.getFrameCount()); // Still 5, oldest evicted
    }

    @Test
    void testAggregateEmptyWindow() {
        var snapshot = aggregator.aggregate();

        assertEquals(0.0, snapshot.currentFps(), 0.001);
        assertEquals(0.0, snapshot.avgFrameTimeMs(), 0.001);
        assertEquals(CoherenceSnapshot.empty(), snapshot.coherence());
        assertEquals(DispatchMetrics.empty(), snapshot.dispatch());
    }

    @Test
    void testAggregateSingleFrame() {
        var coherence = new CoherenceSnapshot(0.65, 0.2, 0.9, 100, 5);
        var dispatch = DispatchMetrics.from(100, 75, 25);

        aggregator.addFrame(16_666_667L, coherence, dispatch); // ~60 FPS frame

        var snapshot = aggregator.aggregate();

        // FPS calculation: 1 second in nanos / frame time nanos
        assertEquals(60.0, snapshot.currentFps(), 1.0);
        assertEquals(16.67, snapshot.avgFrameTimeMs(), 0.1);
        assertEquals(16.67, snapshot.minFrameTimeMs(), 0.1);
        assertEquals(16.67, snapshot.maxFrameTimeMs(), 0.1);

        // Coherence and dispatch should match
        assertEquals(coherence, snapshot.coherence());
        assertEquals(dispatch, snapshot.dispatch());
    }

    @Test
    void testAggregateMultipleFrames() {
        // Add 5 frames with varying frame times
        aggregator.addFrame(16_666_667L,  // 60 FPS
            new CoherenceSnapshot(0.5, 0.2, 0.8, 50, 4),
            DispatchMetrics.from(100, 60, 40)
        );
        aggregator.addFrame(20_000_000L,  // 50 FPS
            new CoherenceSnapshot(0.6, 0.3, 0.9, 60, 4),
            DispatchMetrics.from(100, 70, 30)
        );
        aggregator.addFrame(33_333_333L,  // 30 FPS
            new CoherenceSnapshot(0.7, 0.4, 0.9, 70, 5),
            DispatchMetrics.from(100, 80, 20)
        );
        aggregator.addFrame(16_666_667L,  // 60 FPS
            new CoherenceSnapshot(0.8, 0.5, 1.0, 80, 5),
            DispatchMetrics.from(100, 90, 10)
        );
        aggregator.addFrame(20_000_000L,  // 50 FPS
            new CoherenceSnapshot(0.9, 0.6, 1.0, 90, 6),
            DispatchMetrics.from(100, 100, 0)
        );

        var snapshot = aggregator.aggregate();

        // Average frame time: (16.67 + 20.0 + 33.33 + 16.67 + 20.0) / 5 = 21.33ms
        assertEquals(21.33, snapshot.avgFrameTimeMs(), 0.5);

        // Min/max frame times
        assertEquals(16.67, snapshot.minFrameTimeMs(), 0.5);
        assertEquals(33.33, snapshot.maxFrameTimeMs(), 0.5);

        // FPS from most recent frame
        assertEquals(50.0, snapshot.currentFps(), 2.0);

        // Coherence: average of all frames
        // (0.5 + 0.6 + 0.7 + 0.8 + 0.9) / 5 = 0.7
        assertEquals(0.7, snapshot.coherence().averageCoherence(), 0.01);

        // Dispatch: sum of all frames
        // Total: 500, batch: 400, single: 100
        assertEquals(500, snapshot.dispatch().totalDispatches());
        assertEquals(400, snapshot.dispatch().batchDispatches());
        assertEquals(100, snapshot.dispatch().singleRayDispatches());
    }

    @Test
    void testClear() {
        // Add frames
        for (int i = 0; i < 3; i++) {
            aggregator.addFrame(
                16_666_667L,
                new CoherenceSnapshot(0.5, 0.2, 0.8, 10, 3),
                DispatchMetrics.from(10, 5, 5)
            );
        }

        assertEquals(3, aggregator.getFrameCount());

        // Clear
        aggregator.clear();

        assertEquals(0, aggregator.getFrameCount());

        var snapshot = aggregator.aggregate();
        assertEquals(0.0, snapshot.currentFps(), 0.001);
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        var sharedAggregator = new MetricsAggregator(100);

        // Spawn multiple threads adding frames concurrently
        var threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 250; i++) {
                    sharedAggregator.addFrame(
                        16_666_667L,
                        new CoherenceSnapshot(0.5, 0.2, 0.8, 10, 3),
                        DispatchMetrics.from(10, 5, 5)
                    );

                    // Occasionally aggregate during writes
                    if (i % 50 == 0) {
                        sharedAggregator.aggregate();
                    }
                }
            });
            threads[t].start();
        }

        // Wait for all threads
        for (var thread : threads) {
            thread.join();
        }

        // Should have exactly window size frames (not more)
        assertEquals(100, sharedAggregator.getFrameCount());

        // Should be able to aggregate without exception
        var snapshot = sharedAggregator.aggregate();
        assertNotNull(snapshot);
        assertTrue(snapshot.currentFps() > 0);
    }

    @Test
    void testZeroFrameTimeHandled() {
        // Edge case: zero frame time shouldn't crash
        aggregator.addFrame(
            0L,
            CoherenceSnapshot.empty(),
            DispatchMetrics.empty()
        );

        var snapshot = aggregator.aggregate();

        // FPS calculation should handle zero frame time gracefully
        assertTrue(Double.isFinite(snapshot.currentFps()));
    }

    @Test
    void testCoherenceMinMaxTracking() {
        aggregator.addFrame(16_666_667L,
            new CoherenceSnapshot(0.5, 0.1, 0.9, 10, 3),
            DispatchMetrics.empty()
        );
        aggregator.addFrame(16_666_667L,
            new CoherenceSnapshot(0.7, 0.2, 1.0, 20, 4),
            DispatchMetrics.empty()
        );
        aggregator.addFrame(16_666_667L,
            new CoherenceSnapshot(0.3, 0.0, 0.6, 15, 3),
            DispatchMetrics.empty()
        );

        var snapshot = aggregator.aggregate();

        // Min across all frames: min(0.1, 0.2, 0.0) = 0.0
        assertEquals(0.0, snapshot.coherence().minCoherence(), 0.001);

        // Max across all frames: max(0.9, 1.0, 0.6) = 1.0
        assertEquals(1.0, snapshot.coherence().maxCoherence(), 0.001);

        // Average: (0.5 + 0.7 + 0.3) / 3 = 0.5
        assertEquals(0.5, snapshot.coherence().averageCoherence(), 0.01);
    }
}
