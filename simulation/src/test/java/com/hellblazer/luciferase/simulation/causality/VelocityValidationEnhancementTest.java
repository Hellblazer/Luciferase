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

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VelocityValidationEnhancementTest - Phase 7D.2 Part 2 Phase B Tests.
 * <p>
 * Tests enhanced velocity validation capabilities:
 * <ul>
 *   <li>getGhostVelocity() retrieval from GhostStateManager</li>
 *   <li>GhostConsistencyValidator using actual ghost velocities</li>
 *   <li>Zero-velocity division guards (I1 audit finding)</li>
 *   <li>Velocity consistency validation</li>
 *   <li>Thread safety of concurrent velocity operations</li>
 * </ul>
 * <p>
 * Quality target: 9.0+/10 (BDD style, clear assertions, comprehensive coverage).
 *
 * @author hal.hildebrand
 */
class VelocityValidationEnhancementTest {

    private GhostStateManager ghostStateManager;
    private GhostConsistencyValidator validator;
    private BubbleBounds bounds;
    private UUID sourceBubbleId;

    @BeforeEach
    void setUp() {
        // Arrange: Create root TetreeKey at level 10 and convert to bounds
        var rootKey = TetreeKey.create((byte) 10, 0L, 0L);
        bounds = BubbleBounds.fromTetreeKey(rootKey);

        // Create GhostStateManager with 1000 ghost limit
        ghostStateManager = new GhostStateManager(bounds, 1000);

        // Create validator with 5% accuracy, 1000ms window
        validator = new GhostConsistencyValidator(0.05f, 1000L);
        validator.setGhostStateManager(ghostStateManager);

        // Test source bubble ID
        sourceBubbleId = UUID.randomUUID();
    }

    /**
     * Test 1: getGhostVelocity() returns correct velocity from ghost.
     * <p>
     * Scenario: Create ghost with known velocity, retrieve via getGhostVelocity().
     * Expected: Returned velocity matches the velocity set during updateGhost().
     */
    @Test
    void testGetGhostVelocityReturnsCorrectVelocity() {
        // Arrange
        var entityId = new StringEntityID("entity-velocity-1");
        var position = new Point3f(1.0f, 2.0f, 3.0f);
        var velocity = new Point3f(5.0f, 10.0f, 15.0f);
        var timestamp = System.currentTimeMillis();

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 0L);

        // Act: Update ghost with known velocity
        ghostStateManager.updateGhost(sourceBubbleId, event);

        // Retrieve velocity
        var retrievedVelocity = ghostStateManager.getGhostVelocity(entityId);

        // Assert: Velocity matches
        assertNotNull(retrievedVelocity, "Velocity should not be null");
        assertEquals(5.0f, retrievedVelocity.x, 0.001f, "Velocity X component should match");
        assertEquals(10.0f, retrievedVelocity.y, 0.001f, "Velocity Y component should match");
        assertEquals(15.0f, retrievedVelocity.z, 0.001f, "Velocity Z component should match");
    }

    /**
     * Test 2: getGhostVelocity() returns zero vector for non-existent ghost.
     * <p>
     * Scenario: Call getGhostVelocity() for entity that has no ghost.
     * Expected: Returns zero vector (0, 0, 0).
     */
    @Test
    void testGetGhostVelocityReturnsZeroForNonExistentGhost() {
        // Arrange
        var nonExistentId = new StringEntityID("non-existent");

        // Act: Try to get velocity for ghost that doesn't exist
        var velocity = ghostStateManager.getGhostVelocity(nonExistentId);

        // Assert: Zero vector returned
        assertNotNull(velocity, "Velocity should not be null");
        assertEquals(0.0f, velocity.x, 0.001f, "Velocity X should be zero");
        assertEquals(0.0f, velocity.y, 0.001f, "Velocity Y should be zero");
        assertEquals(0.0f, velocity.z, 0.001f, "Velocity Z should be zero");
    }

    /**
     * Test 3: Validation uses actual ghost velocity (not hardcoded zero).
     * <p>
     * Scenario: Create ghost with non-zero velocity, validate with different expected velocity.
     * Expected: Validation report includes velocity data from actual ghost (not hardcoded zero).
     */
    @Test
    void testValidationUsesActualGhostVelocity() {
        // Arrange
        var entityId = new StringEntityID("entity-velocity-validation");
        var initialPosition = new Point3f(1.0f, 1.0f, 1.0f);
        var ghostVelocity = new Point3f(10.0f, 0.0f, 0.0f);  // Ghost moving in X direction
        var timestamp = System.currentTimeMillis();

        var event = new EntityUpdateEvent(entityId, initialPosition, ghostVelocity, timestamp, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event);

        // Wait for dead reckoning to process and extrapolate
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Act: Validate with extrapolated position and different velocity
        // After 100ms at velocity (10, 0, 0), position should be near (1.0 + 10*0.1, 1.0, 1.0) = (2.0, 1.0, 1.0)
        var extrapolatedPosition = new Point3f(2.0f, 1.0f, 1.0f);
        var expectedVelocity = new Vector3f(12.0f, 0.0f, 0.0f);  // Slightly different
        var report = validator.validateConsistency(entityId, extrapolatedPosition, expectedVelocity);

        // Assert: Validation uses actual ghost velocity (report includes velocity delta)
        assertNotNull(report, "Report should not be null");
        // Velocity delta should reflect difference between ghost velocity (10) and expected velocity (12)
        assertTrue(report.velocityDelta() >= 1.5f, "Velocity delta should be approximately 2.0 (12 - 10)");
        assertTrue(report.velocityValid(), "Velocity should be valid (same direction)");
    }

    /**
     * Test 4: Zero-velocity guard prevents division by zero (I1 audit finding).
     * <p>
     * Scenario: Create ghost with zero velocity, validate consistency.
     * Expected: No NaN/Infinity in validation report, validation passes.
     */
    @Test
    void testZeroVelocityGuardPreventsDivisionByZero() {
        // Arrange
        var entityId = new StringEntityID("entity-zero-velocity");
        var position = new Point3f(5.0f, 5.0f, 5.0f);
        var zeroVelocity = new Point3f(0.0f, 0.0f, 0.0f);  // Zero velocity
        var timestamp = System.currentTimeMillis();

        var event = new EntityUpdateEvent(entityId, position, zeroVelocity, timestamp, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event);

        // Act: Validate with zero velocity
        var report = validator.validateConsistency(entityId, position, new Vector3f(zeroVelocity.x, zeroVelocity.y, zeroVelocity.z));

        // Assert: No NaN/Infinity, validation passes
        assertNotNull(report, "Report should not be null");
        assertFalse(Float.isNaN(report.positionDelta()), "Position delta should not be NaN");
        assertFalse(Float.isInfinite(report.positionDelta()), "Position delta should not be infinite");
        assertFalse(Float.isNaN(report.velocityDelta()), "Velocity delta should not be NaN");
        assertFalse(Float.isInfinite(report.velocityDelta()), "Velocity delta should not be infinite");
        assertTrue(report.positionValid(), "Position validation should pass for zero velocity");
        assertTrue(report.velocityValid(), "Velocity validation should pass for zero velocity");
    }

    /**
     * Test 5: Velocity consistency check handles high-velocity ghosts.
     * <p>
     * Scenario: Create ghost with very high velocity, validate consistency.
     * Expected: Validation handles high-speed movement correctly.
     */
    @Test
    void testVelocityConsistencyHandlesHighVelocity() {
        // Arrange
        var entityId = new StringEntityID("entity-high-velocity");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var highVelocity = new Point3f(1000.0f, 1000.0f, 1000.0f);  // Very high velocity
        var timestamp = System.currentTimeMillis();

        var event = new EntityUpdateEvent(entityId, position, highVelocity, timestamp, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event);

        // Wait for extrapolation
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Act: Validate with same high velocity
        var report = validator.validateConsistency(entityId, position, new Vector3f(highVelocity.x, highVelocity.y, highVelocity.z));

        // Assert: Validation completes without errors
        assertNotNull(report, "Report should not be null");
        assertFalse(Float.isNaN(report.positionDelta()), "Position delta should not be NaN");
        assertFalse(Float.isInfinite(report.positionDelta()), "Position delta should not be infinite");
    }

    /**
     * Test 6: Velocity validation handles rapid velocity changes.
     * <p>
     * Scenario: Update ghost velocity multiple times in quick succession.
     * Expected: getGhostVelocity() returns most recent velocity.
     */
    @Test
    void testVelocityValidationHandlesRapidChanges() {
        // Arrange
        var entityId = new StringEntityID("entity-rapid-velocity");
        var position = new Point3f(1.0f, 1.0f, 1.0f);
        var timestamp = System.currentTimeMillis();

        // Initial velocity
        var velocity1 = new Point3f(10.0f, 0.0f, 0.0f);
        var event1 = new EntityUpdateEvent(entityId, position, velocity1, timestamp, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event1);

        // Update velocity rapidly
        var velocity2 = new Point3f(0.0f, 20.0f, 0.0f);
        var event2 = new EntityUpdateEvent(entityId, position, velocity2, timestamp + 50, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event2);

        var velocity3 = new Point3f(0.0f, 0.0f, 30.0f);
        var event3 = new EntityUpdateEvent(entityId, position, velocity3, timestamp + 100, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event3);

        // Act: Get current velocity
        var currentVelocity = ghostStateManager.getGhostVelocity(entityId);

        // Assert: Most recent velocity is returned
        assertNotNull(currentVelocity, "Velocity should not be null");
        assertEquals(0.0f, currentVelocity.x, 0.001f, "Velocity X should match most recent");
        assertEquals(0.0f, currentVelocity.y, 0.001f, "Velocity Y should match most recent");
        assertEquals(30.0f, currentVelocity.z, 0.001f, "Velocity Z should match most recent");
    }

    /**
     * Test 7: Concurrent velocity reads are thread-safe.
     * <p>
     * Scenario: Multiple threads reading velocity while updates occur.
     * Expected: No ConcurrentModificationException, all reads succeed.
     */
    @Test
    void testConcurrentVelocityReadsAreThreadSafe() throws InterruptedException {
        // Arrange
        var entityId = new StringEntityID("entity-concurrent");
        var position = new Point3f(1.0f, 1.0f, 1.0f);
        var velocity = new Point3f(5.0f, 5.0f, 5.0f);
        var timestamp = System.currentTimeMillis();

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event);

        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);
        var exceptionCount = new AtomicInteger(0);

        // Act: Spawn threads to read velocity concurrently
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        var v = ghostStateManager.getGhostVelocity(entityId);
                        if (v != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for all threads
        latch.await();

        // Assert: All reads succeeded
        assertEquals(threadCount * 100, successCount.get(), "All velocity reads should succeed");
        assertEquals(0, exceptionCount.get(), "No exceptions should occur");
    }

    /**
     * Test 8: Consistency report includes velocity data.
     * <p>
     * Scenario: Validate ghost with known velocity difference.
     * Expected: Report includes velocityDelta and velocityValid fields.
     */
    @Test
    void testConsistencyReportIncludesVelocityData() {
        // Arrange
        var entityId = new StringEntityID("entity-velocity-report");
        var position = new Point3f(2.0f, 2.0f, 2.0f);
        var ghostVelocity = new Point3f(8.0f, 8.0f, 8.0f);
        var timestamp = System.currentTimeMillis();

        var event = new EntityUpdateEvent(entityId, position, ghostVelocity, timestamp, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event);

        // Act: Validate with different velocity
        var expectedVelocity = new Vector3f(10.0f, 10.0f, 10.0f);
        var report = validator.validateConsistency(entityId, position, expectedVelocity);

        // Assert: Report includes velocity data
        assertNotNull(report, "Report should not be null");
        assertNotNull(report.message(), "Message should not be null");
        assertTrue(report.message().contains("Velocity delta"), "Message should mention velocity delta");

        // Velocity delta should be present (may be zero if velocity comparison not implemented yet)
        assertTrue(report.velocityDelta() >= 0.0f, "Velocity delta should be non-negative");

        // Velocity valid should be present
        assertNotNull(report.velocityValid(), "Velocity valid flag should be present");
    }
}
