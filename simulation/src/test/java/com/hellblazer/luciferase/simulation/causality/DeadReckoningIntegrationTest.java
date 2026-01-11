/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostEntity;
import com.hellblazer.luciferase.simulation.spatial.DeadReckoningEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DeadReckoningEstimator with entity positions (Phase 7D.2 Part 1).
 * <p>
 * Validates that dead reckoning prediction works correctly for entity movement,
 * error correction is smooth, and performance targets are met.
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class DeadReckoningIntegrationTest {

    private DeadReckoningEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new DeadReckoningEstimator();
    }

    /**
     * Test ghost entity implementation for dead reckoning.
     */
    private static class TestGhost implements GhostEntity {
        private final EntityID id;
        private final Point3f position;
        private final Vector3f velocity;
        private final long timestamp;

        TestGhost(String id, Point3f position, Vector3f velocity, long timestamp) {
            this.id = new StringEntityID(id);
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
            return position;
        }

        @Override
        public Vector3f velocity() {
            return velocity;
        }

        @Override
        public long timestamp() {
            return timestamp;
        }
    }

    @Test
    @DisplayName("Prediction accuracy with moving entity within 5%")
    void testPredictionAccuracyWithMovingEntity() {
        // Given: Entity moving at constant velocity
        var entityId = "moving-entity";
        var startPosition = new Point3f(0, 0, 0);
        var velocity = new Vector3f(10, 0, 0);  // 10 units/sec
        var startTime = 1000L;

        var ghost = new TestGhost(entityId, startPosition, velocity, startTime);

        // When: Authoritative update received
        estimator.onAuthoritativeUpdate(ghost, startPosition);

        // And: Predict position 100ms later (should move 1 unit)
        var predictTime = startTime + 100L;
        var predicted = estimator.predict(ghost, predictTime);

        // Then: Prediction should be accurate within 5%
        var expectedPosition = new Point3f(1.0f, 0, 0);  // 10 units/sec * 0.1 sec = 1 unit
        var error = predicted.distance(expectedPosition);
        var maxAllowedError = velocity.length() * 0.1f * 0.05f;  // 5% of movement

        assertTrue(error <= maxAllowedError,
                  String.format("Prediction error %.3f exceeds 5%% threshold %.3f", error, maxAllowedError));
    }

    @Test
    @DisplayName("Error correction spread over 3 frames")
    void testCorrectionSpreadOver3Frames() {
        // Given: Entity with initial position
        var entityId = "correcting-entity";
        var initialPosition = new Point3f(0, 0, 0);
        var velocity = new Vector3f(5, 0, 0);
        var startTime = 1000L;

        var ghost = new TestGhost(entityId, initialPosition, velocity, startTime);
        estimator.onAuthoritativeUpdate(ghost, initialPosition);

        // Simulate prediction that will be wrong
        var predictedPosition = estimator.predict(ghost, startTime + 200L);

        // When: Authoritative update arrives with different position (prediction error)
        var authoritativePosition = new Point3f(1.5f, 0, 0);  // Actual position differs from prediction
        var updatedGhost = new TestGhost(entityId, authoritativePosition, velocity, startTime + 200L);
        estimator.onAuthoritativeUpdate(updatedGhost, authoritativePosition);

        // Then: Correction should be active
        assertTrue(estimator.hasActiveCorrection(new StringEntityID(entityId)),
                  "Should have active correction after prediction error");

        // And: Correction applied over 3 frames
        int framesWithCorrection = 0;
        for (int frame = 0; frame < 5; frame++) {
            if (estimator.hasActiveCorrection(new StringEntityID(entityId))) {
                framesWithCorrection++;
            }
            // Simulate frame advance (predict for next frame)
            estimator.predict(updatedGhost, startTime + 200L + (frame * 16L));  // 16ms per frame ~60fps
        }

        assertEquals(DeadReckoningEstimator.CORRECTION_FRAMES, framesWithCorrection,
                    "Correction should span exactly " + DeadReckoningEstimator.CORRECTION_FRAMES + " frames");
    }

    @Test
    @DisplayName("Error clamping prevents position snapping")
    void testErrorClampingPreventsJitter() {
        // Given: Entity with small velocity
        var entityId = "clamped-entity";
        var initialPosition = new Point3f(0, 0, 0);
        var velocity = new Vector3f(1, 0, 0);  // Slow movement
        var startTime = 1000L;

        var ghost = new TestGhost(entityId, initialPosition, velocity, startTime);
        estimator.onAuthoritativeUpdate(ghost, initialPosition);

        // When: Large prediction error occurs
        var authoritativePosition = new Point3f(10, 0, 0);  // 10x expected position
        var updatedGhost = new TestGhost(entityId, authoritativePosition, velocity, startTime + 100L);
        estimator.onAuthoritativeUpdate(updatedGhost, authoritativePosition);

        // Then: Correction per frame is clamped
        var predicted1 = estimator.predict(updatedGhost, startTime + 116L);  // First frame correction
        var predicted2 = estimator.predict(updatedGhost, startTime + 132L);  // Second frame correction

        var frameDelta = predicted2.distance(predicted1);
        var maxCorrectionPerFrame = velocity.length() * DeadReckoningEstimator.MAX_CORRECTION_PER_FRAME;

        assertTrue(frameDelta <= maxCorrectionPerFrame * 1.1f,  // Allow 10% tolerance for float arithmetic
                  String.format("Frame delta %.3f should not exceed max correction %.3f",
                               frameDelta, maxCorrectionPerFrame));
    }

    @Test
    @DisplayName("Configurable correction parameters control behavior")
    void testConfigurableCorrection() {
        // Given: Default configuration uses CORRECTION_FRAMES=3, MAX_CORRECTION_PER_FRAME=0.05
        assertEquals(3, DeadReckoningEstimator.CORRECTION_FRAMES,
                    "Default correction frames should be 3");
        assertEquals(0.05f, DeadReckoningEstimator.MAX_CORRECTION_PER_FRAME, 0.001f,
                    "Default max correction should be 5%");

        // When: Entity undergoes correction
        var entityId = "config-entity";
        var initialPosition = new Point3f(0, 0, 0);
        var velocity = new Vector3f(10, 0, 0);
        var startTime = 1000L;

        var ghost = new TestGhost(entityId, initialPosition, velocity, startTime);
        estimator.onAuthoritativeUpdate(ghost, initialPosition);

        var authoritativePosition = new Point3f(2, 0, 0);
        var updatedGhost = new TestGhost(entityId, authoritativePosition, velocity, startTime + 100L);
        estimator.onAuthoritativeUpdate(updatedGhost, authoritativePosition);

        // Then: Configuration parameters affect correction behavior
        assertTrue(estimator.hasActiveCorrection(new StringEntityID(entityId)),
                  "Correction should be active");
    }

    @Test
    @DisplayName("Works with 200ms network latency")
    void testLatencyHandling200ms() {
        // Given: Entity with 200ms latency between updates
        var entityId = "latency-entity";
        var initialPosition = new Point3f(0, 0, 0);
        var velocity = new Vector3f(5, 5, 0);  // Diagonal movement
        var startTime = 1000L;

        var ghost = new TestGhost(entityId, initialPosition, velocity, startTime);
        estimator.onAuthoritativeUpdate(ghost, initialPosition);

        // When: Update arrives 200ms later
        var latencyMs = 200L;
        var expectedMovement = new Vector3f(velocity);
        expectedMovement.scale(latencyMs / 1000f);

        var authoritativePosition = new Point3f(initialPosition);
        authoritativePosition.add(expectedMovement);

        var updatedGhost = new TestGhost(entityId, authoritativePosition, velocity, startTime + latencyMs);
        estimator.onAuthoritativeUpdate(updatedGhost, authoritativePosition);

        // And: Predict current position
        var predicted = estimator.predict(updatedGhost, startTime + latencyMs);

        // Then: Prediction should be close to authoritative position
        var error = predicted.distance(authoritativePosition);
        var movementMagnitude = expectedMovement.length();
        var maxError = movementMagnitude * 0.1f;  // 10% tolerance for latency

        assertTrue(error <= maxError,
                  String.format("Latency error %.3f should be within 10%% of movement %.3f",
                               error, movementMagnitude));
    }

    @Test
    @DisplayName("Concurrent prediction for multiple entities")
    void testConcurrentPrediction() {
        // Given: Multiple entities being tracked
        var entities = new TestGhost[10];
        var startTime = 1000L;

        for (int i = 0; i < entities.length; i++) {
            var entityId = "concurrent-" + i;
            var position = new Point3f(i * 10f, 0, 0);
            var velocity = new Vector3f(i * 2f, 0, 0);  // Different velocities
            entities[i] = new TestGhost(entityId, position, velocity, startTime);

            estimator.onAuthoritativeUpdate(entities[i], position);
        }

        // When: Predict all entities at same time
        var predictTime = startTime + 100L;
        var predictions = new Point3f[entities.length];

        for (int i = 0; i < entities.length; i++) {
            predictions[i] = estimator.predict(entities[i], predictTime);
        }

        // Then: All predictions should be accurate
        for (int i = 0; i < entities.length; i++) {
            var expected = new Point3f(entities[i].position());
            var movement = new Vector3f(entities[i].velocity());
            movement.scale(0.1f);  // 100ms = 0.1 sec
            expected.add(movement);

            var error = predictions[i].distance(expected);
            var maxError = movement.length() * 0.05f;  // 5% tolerance

            assertTrue(error <= maxError,
                      String.format("Entity %d prediction error %.3f exceeds threshold %.3f",
                                   i, error, maxError));
        }

        // And: Correct number of entities tracked
        assertEquals(entities.length, estimator.getTrackedEntityCount(),
                    "Should track all entities");
    }

    @Test
    @DisplayName("Position accuracy within 5% metric")
    void testPosition5PercentAccuracyMetric() {
        // Given: Entity moving for 1 second
        var entityId = "accuracy-entity";
        var initialPosition = new Point3f(0, 0, 0);
        var velocity = new Vector3f(20, 0, 0);  // 20 units/sec
        var startTime = 1000L;

        var ghost = new TestGhost(entityId, initialPosition, velocity, startTime);
        estimator.onAuthoritativeUpdate(ghost, initialPosition);

        // When: Predict position at various time intervals
        var testDurations = new long[]{50L, 100L, 200L, 500L, 1000L};  // ms

        for (var duration : testDurations) {
            var predictTime = startTime + duration;
            var predicted = estimator.predict(ghost, predictTime);

            // Calculate expected position
            var expectedPosition = new Point3f(initialPosition);
            var movement = new Vector3f(velocity);
            movement.scale(duration / 1000f);
            expectedPosition.add(movement);

            // Measure error as percentage of total movement
            var error = predicted.distance(expectedPosition);
            var totalMovement = movement.length();
            var errorPercent = totalMovement > 0.001f ? (error / totalMovement) : 0f;

            // Then: Error should be within 5%
            assertTrue(errorPercent <= 0.05f,
                      String.format("At %dms: error %% %.2f%% exceeds 5%% threshold",
                                   duration, errorPercent * 100));
        }
    }

    @Test
    @DisplayName("Velocity preserved through update cycle")
    void testVelocityPreservationThroughTransfer() {
        // Given: Entity with specific velocity
        var entityId = "velocity-entity";
        var initialPosition = new Point3f(0, 0, 0);
        var originalVelocity = new Vector3f(3, 4, 0);  // 5 units/sec magnitude
        var startTime = 1000L;

        var ghost = new TestGhost(entityId, initialPosition, originalVelocity, startTime);
        estimator.onAuthoritativeUpdate(ghost, initialPosition);

        // When: Multiple updates received with same velocity
        for (int i = 1; i <= 5; i++) {
            var updateTime = startTime + (i * 100L);
            var expectedPosition = new Point3f(initialPosition);
            var movement = new Vector3f(originalVelocity);
            movement.scale((i * 100L) / 1000f);
            expectedPosition.add(movement);

            var updatedGhost = new TestGhost(entityId, expectedPosition, originalVelocity, updateTime);
            estimator.onAuthoritativeUpdate(updatedGhost, expectedPosition);

            // Then: Predictions should maintain velocity consistency
            var predicted = estimator.predict(updatedGhost, updateTime + 50L);
            var predictedMovement = new Point3f(predicted);
            predictedMovement.sub(expectedPosition);

            var predictedVelocity = new Vector3f(predictedMovement);
            predictedVelocity.scale(1000f / 50f);  // Convert to units/sec

            var velocityError = predictedVelocity.length() - originalVelocity.length();
            var maxVelocityError = originalVelocity.length() * 0.1f;  // 10% tolerance

            assertTrue(Math.abs(velocityError) <= maxVelocityError,
                      String.format("Update %d: velocity error %.3f exceeds threshold %.3f",
                                   i, Math.abs(velocityError), maxVelocityError));
        }
    }
}
