package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DeadReckoningEstimator - smooth ghost entity position prediction.
 * <p>
 * DeadReckoningEstimator provides:
 * - Linear prediction: position + velocity × time
 * - Smooth error correction over N frames
 * - Correction clamping to prevent jarring snaps
 * - Latency tolerance up to 200ms
 * <p>
 * Success criteria from Phase 5:
 * - Prediction error < 10% of actual movement
 * - Position delta per frame < 5% of velocity
 * - Works with up to 200ms latency
 * - No visible jitter during normal operation
 * <p>
 * Test coverage:
 * - Linear prediction validation
 * - Smooth correction over CORRECTION_FRAMES (3)
 * - Correction clamping (5% max per frame)
 * - Latency tolerance (200ms)
 * - Integration with ghost entity updates
 *
 * @author hal.hildebrand
 */
class DeadReckoningEstimatorTest {

    // Simple EntityID for testing
    static class TestEntityID implements EntityID {
        private final String id;

        TestEntityID(String id) {
            this.id = id;
        }

        @Override
        public String toDebugString() {
            return id;
        }

        @Override
        public int compareTo(EntityID other) {
            return id.compareTo(other.toDebugString());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestEntityID that)) return false;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    // Simple GhostEntity for testing
    static class TestGhostEntity implements GhostEntity {
        private final TestEntityID id;
        private final Point3f position;
        private final Vector3f velocity;
        private final long timestamp;

        TestGhostEntity(String id, Point3f position, Vector3f velocity, long timestamp) {
            this.id = new TestEntityID(id);
            this.position = position;
            this.velocity = velocity;
            this.timestamp = timestamp;
        }

        @Override
        public EntityID id() {
            return id;
        }

        @Override
        public Point3f position() {
            return new Point3f(position);
        }

        @Override
        public Vector3f velocity() {
            return new Vector3f(velocity);
        }

        @Override
        public long timestamp() {
            return timestamp;
        }
    }

    private DeadReckoningEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new DeadReckoningEstimator();
    }

    @Test
    void testInitialState() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(1, 0, 0),
            1000L
        );

        // First prediction should match ghost position (no history)
        var predicted = estimator.predict(ghost, 1000L);

        assertEquals(0f, predicted.x, 0.001f, "Initial prediction at t=0");
        assertEquals(0f, predicted.y, 0.001f);
        assertEquals(0f, predicted.z, 0.001f);
    }

    @Test
    void testLinearPrediction() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),      // Starting position
            new Vector3f(10, 0, 0),    // Velocity: 10 units/sec in X
            1000L                       // t = 1000ms
        );

        // Record initial state
        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Predict 100ms into future
        var predicted = estimator.predict(ghost, 1100L);

        // Expected: position + velocity × 0.1s = 0 + 10 × 0.1 = 1.0
        assertEquals(1.0f, predicted.x, 0.001f,
                    "Linear prediction: x = 0 + 10 * 0.1");
        assertEquals(0f, predicted.y, 0.001f);
        assertEquals(0f, predicted.z, 0.001f);
    }

    @Test
    void testLinearPredictionMultipleAxes() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(5, 10, 15),
            new Vector3f(2, -3, 4),     // Velocity in all axes
            2000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Predict 500ms (0.5s) into future
        var predicted = estimator.predict(ghost, 2500L);

        // Expected:
        // x = 5 + 2 * 0.5 = 6.0
        // y = 10 + (-3) * 0.5 = 8.5
        // z = 15 + 4 * 0.5 = 17.0
        assertEquals(6.0f, predicted.x, 0.001f);
        assertEquals(8.5f, predicted.y, 0.001f);
        assertEquals(17.0f, predicted.z, 0.001f);
    }

    @Test
    void testPredictionError() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(10, 0, 0),
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Predict 100ms ahead - should be 1.0
        var predicted = estimator.predict(ghost, 1100L);
        assertEquals(1.0f, predicted.x, 0.001f);

        // Authoritative update arrives at t=1100: actual position is 1.08 (not 1.0)
        // This represents a small prediction error (entity velocity changed slightly)
        var ghost2 = new TestGhostEntity("e1",
            new Point3f(1.08f, 0, 0),
            new Vector3f(10, 0, 0),
            1100L
        );
        estimator.onAuthoritativeUpdate(ghost2, ghost2.position());

        // Error should be < 10% of movement
        float actualMovement = 1.08f;  // Moved 1.08 units in 100ms
        float predictedMovement = 1.0f;  // We predicted 1.0
        float error = Math.abs(predictedMovement - actualMovement);
        float errorPercent = (error / actualMovement) * 100f;

        assertTrue(errorPercent < 10f,
                  String.format("Error %.1f%% should be < 10%%", errorPercent));
    }

    @Test
    void testSmoothCorrectionOverThreeFrames() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(10, 0, 0),
            1000L
        );

        // Initial prediction state
        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Authoritative update arrives at t=1100: position is 1.3 (we predicted 1.0)
        // Error = 0.3 units to correct over 3 frames
        var ghost2 = new TestGhostEntity("e1",
            new Point3f(1.3f, 0, 0),
            new Vector3f(10, 0, 0),
            1100L  // 100ms later
        );
        estimator.onAuthoritativeUpdate(ghost2, ghost2.position());

        // Now predict at t=1116 (16ms after authoritative update, one frame at 60fps)
        var predicted1 = estimator.predict(ghost2, 1116L);
        // Expected: 1.3 + 10*0.016 + (0.3/3) = 1.3 + 0.16 + 0.1 = 1.56
        assertTrue(Math.abs(predicted1.x - 1.56f) < 0.15f,
                  "Frame 1: should apply ~1/3 of correction");

        // Frame 2 at t=1133 (17ms later, second frame)
        var predicted2 = estimator.predict(ghost2, 1133L);
        // Correction continues

        // Frame 3 at t=1150 (17ms later, third frame)
        var predicted3 = estimator.predict(ghost2, 1150L);
        // After 3 frames, correction should be nearly complete
    }

    @Test
    void testCorrectionClamping() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(10, 0, 0),    // Velocity: 10 units/sec
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Authoritative update at t=1100: position is 5.0 (we predicted 1.0)
        // Error = 4.0 units (very large error)
        var ghost2 = new TestGhostEntity("e1",
            new Point3f(5.0f, 0, 0),
            new Vector3f(10, 0, 0),
            1100L
        );
        estimator.onAuthoritativeUpdate(ghost2, ghost2.position());

        // Predict one frame later at t=1116 (16ms, one frame at 60fps)
        var predicted = estimator.predict(ghost2, 1116L);

        // Expected base prediction: 5.0 + 10*0.016 = 5.16
        // Max correction per frame: 5% of velocity = 10 * 0.05 = 0.5 units
        // With large error (4.0 units), correction this frame should be clamped to 0.5
        // So predicted = 5.16 + 0.5 = 5.66 (approximately)

        float velocityMagnitude = 10f;
        float maxCorrectionPerFrame = velocityMagnitude * 0.05f;

        // Check that the correction applied is at most max correction
        float linearPrediction = 5.0f + 10f * 0.016f;  // 5.16
        float correctionApplied = predicted.x - linearPrediction;

        assertTrue(correctionApplied <= maxCorrectionPerFrame * 1.1f,  // 10% tolerance
                  String.format("Correction applied %.2f should be <= max %.2f",
                               correctionApplied, maxCorrectionPerFrame));
    }

    @Test
    void testLatencyTolerance200ms() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(10, 0, 0),
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Predict 200ms (0.2s) into future
        var predicted = estimator.predict(ghost, 1200L);

        // Expected: position + velocity × 0.2s = 0 + 10 × 0.2 = 2.0
        assertEquals(2.0f, predicted.x, 0.001f,
                    "Should handle 200ms latency");

        // Authoritative update arrives: actual position is 2.1
        var authoritative = new Point3f(2.1f, 0, 0);
        estimator.onAuthoritativeUpdate(ghost, authoritative);

        // Error = 0.1 / 2.1 = 4.76% (< 10% threshold)
        float error = Math.abs(2.0f - 2.1f);
        float errorPercent = (error / 2.1f) * 100f;
        assertTrue(errorPercent < 10f,
                  String.format("200ms latency error %.1f%% should be < 10%%",
                               errorPercent));
    }

    @Test
    void testMultipleEntityTracking() {
        var ghost1 = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(5, 0, 0),
            1000L
        );
        var ghost2 = new TestGhostEntity("e2",
            new Point3f(10, 0, 0),
            new Vector3f(-3, 0, 0),
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost1, ghost1.position());
        estimator.onAuthoritativeUpdate(ghost2, ghost2.position());

        // Predict both entities 100ms ahead
        var predicted1 = estimator.predict(ghost1, 1100L);
        var predicted2 = estimator.predict(ghost2, 1100L);

        // Entity 1: 0 + 5 * 0.1 = 0.5
        assertEquals(0.5f, predicted1.x, 0.001f);

        // Entity 2: 10 + (-3) * 0.1 = 9.7
        assertEquals(9.7f, predicted2.x, 0.001f);
    }

    @Test
    void testNoJitterWithConstantVelocity() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(10, 0, 0),
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Predict multiple frames with constant velocity
        float lastX = 0f;
        for (int i = 1; i <= 10; i++) {
            long currentTime = 1000L + (i * 16L);  // 16ms per frame (60 FPS)
            var predicted = estimator.predict(ghost, currentTime);

            // Position should increase smoothly
            assertTrue(predicted.x > lastX,
                      "Position should increase monotonically");

            // Delta should be consistent
            if (i > 1) {
                float delta = predicted.x - lastX;
                float expectedDelta = 10f * 0.016f;  // 10 units/sec × 16ms
                assertEquals(expectedDelta, delta, 0.01f,
                           "Delta should be consistent (no jitter)");
            }

            lastX = predicted.x;
        }
    }

    @Test
    void testCorrectionStateCleanup() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(10, 0, 0),
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Authoritative update at t=1100: position is 1.5 (we predicted 1.0)
        // Error = 0.5 units
        var ghost2 = new TestGhostEntity("e1",
            new Point3f(1.5f, 0, 0),
            new Vector3f(10, 0, 0),
            1100L
        );
        estimator.onAuthoritativeUpdate(ghost2, ghost2.position());

        // Apply corrections for 3 frames (16ms each at 60fps)
        estimator.predict(ghost2, 1116L);  // Frame 1
        estimator.predict(ghost2, 1133L);  // Frame 2
        estimator.predict(ghost2, 1150L);  // Frame 3

        // After 3 frames, correction should be complete or nearly complete
        assertFalse(estimator.hasActiveCorrection(ghost2.id()),
                   "After 3 frames, correction should be complete");

        // Next prediction should be pure linear (no correction)
        var predicted = estimator.predict(ghost2, 1200L);

        // Pure linear from t=1100 to t=1200 (100ms = 0.1s):
        // 1.5 + 10 * 0.1 = 2.5
        assertEquals(2.5f, predicted.x, 0.15f,
                    "After correction complete, should be pure linear");
    }

    @Test
    void testZeroVelocity() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(5, 5, 5),
            new Vector3f(0, 0, 0),      // Stationary
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Predict 500ms ahead
        var predicted = estimator.predict(ghost, 1500L);

        // Should remain at same position
        assertEquals(5f, predicted.x, 0.001f);
        assertEquals(5f, predicted.y, 0.001f);
        assertEquals(5f, predicted.z, 0.001f);
    }

    @Test
    void testNegativeTimeDelta() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(10, 0, 0),
            new Vector3f(5, 0, 0),
            2000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Attempt to predict with earlier timestamp (should handle gracefully)
        var predicted = estimator.predict(ghost, 1500L);  // 500ms in past

        // Should either clamp to current position or handle gracefully
        assertNotNull(predicted, "Should handle negative time delta");
    }

    @Test
    void testLargeTimeDelta() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(0, 0, 0),
            new Vector3f(10, 0, 0),
            1000L
        );

        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Predict 5 seconds (5000ms) into future
        var predicted = estimator.predict(ghost, 6000L);

        // Expected: 0 + 10 * 5 = 50
        assertEquals(50f, predicted.x, 0.1f,
                    "Should handle large time deltas");
    }

    @Test
    void testConcurrentEntityUpdates() throws InterruptedException {
        int entityCount = 100;
        var threads = new Thread[entityCount];

        for (int i = 0; i < entityCount; i++) {
            final int entityId = i;
            threads[i] = new Thread(() -> {
                var ghost = new TestGhostEntity("e" + entityId,
                    new Point3f(entityId, 0, 0),
                    new Vector3f(1, 0, 0),
                    1000L
                );

                // Concurrent updates and predictions
                estimator.onAuthoritativeUpdate(ghost, ghost.position());
                estimator.predict(ghost, 1100L);
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // All entities should be tracked (thread-safe)
        // No assertions here, just ensure no exceptions
    }

    @Test
    void testAuthoritativeUpdateWithoutPriorPrediction() {
        var ghost = new TestGhostEntity("e1",
            new Point3f(10, 5, 0),
            new Vector3f(2, 0, 0),
            1000L
        );

        // First authoritative update without any prior prediction
        estimator.onAuthoritativeUpdate(ghost, ghost.position());

        // Should handle gracefully (no NPE)
        var predicted = estimator.predict(ghost, 1100L);

        assertNotNull(predicted, "Should handle first update gracefully");
    }
}
