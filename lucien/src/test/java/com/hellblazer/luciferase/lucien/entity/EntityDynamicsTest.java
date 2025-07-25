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
package com.hellblazer.luciferase.lucien.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for EntityDynamics
 *
 * @author hal.hildebrand
 */
public class EntityDynamicsTest {

    private EntityDynamics dynamics;
    private long baseTime;

    @BeforeEach
    void setUp() {
        dynamics = new EntityDynamics();
        baseTime = System.currentTimeMillis();
    }

    @Test
    void testConstructorValidation() {
        // Test default constructor
        var defaultDynamics = new EntityDynamics();
        assertNotNull(defaultDynamics);
        assertEquals(0, defaultDynamics.getHistoryCount());

        // Test custom history size
        var customDynamics = new EntityDynamics(20);
        assertNotNull(customDynamics);
        assertEquals(0, customDynamics.getHistoryCount());

        // Test invalid history size
        assertThrows(IllegalArgumentException.class, () -> new EntityDynamics(1));
        assertThrows(IllegalArgumentException.class, () -> new EntityDynamics(0));
        assertThrows(IllegalArgumentException.class, () -> new EntityDynamics(-5));
    }

    @Test
    void testInitialState() {
        // Initial state should have zero velocity and acceleration
        assertEquals(new Vector3f(0, 0, 0), dynamics.getVelocity());
        assertEquals(new Vector3f(0, 0, 0), dynamics.getAcceleration());
        assertEquals(0.0f, dynamics.getSpeed());
        assertEquals(0, dynamics.getHistoryCount());
        assertNull(dynamics.getCurrentPosition());
        assertEquals(-1, dynamics.getCurrentTimestamp());
    }

    @Test
    void testSinglePositionUpdate() {
        var position = new Point3f(10, 20, 30);
        dynamics.updatePosition(position, baseTime);

        assertEquals(1, dynamics.getHistoryCount());
        assertEquals(position, dynamics.getCurrentPosition());
        assertEquals(baseTime, dynamics.getCurrentTimestamp());
        
        // With only one position, velocity should still be zero
        assertEquals(new Vector3f(0, 0, 0), dynamics.getVelocity());
        assertEquals(new Vector3f(0, 0, 0), dynamics.getAcceleration());
    }

    @Test
    void testVelocityCalculation() {
        // First position
        var pos1 = new Point3f(0, 0, 0);
        dynamics.updatePosition(pos1, baseTime);

        // Second position after 1 second, moved 10 units in X
        var pos2 = new Point3f(10, 0, 0);
        dynamics.updatePosition(pos2, baseTime + 1000);

        // Velocity should be 10 units/second in X direction
        var expectedVelocity = new Vector3f(10, 0, 0);
        assertEquals(expectedVelocity, dynamics.getVelocity());
        assertEquals(10.0f, dynamics.getSpeed());
    }

    @Test
    void testAccelerationCalculation() {
        // Position 1: origin
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);

        // Position 2: moved 10 units in X after 1 second (velocity = 10)
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);

        // Position 3: moved 30 units total after 2 seconds (velocity = 20)
        dynamics.updatePosition(new Point3f(30, 0, 0), baseTime + 2000);

        // Acceleration should be 10 units/second^2 in X direction
        var acceleration = dynamics.getAcceleration();
        assertEquals(10.0f, acceleration.x, 0.01f);
        assertEquals(0.0f, acceleration.y, 0.01f);
        assertEquals(0.0f, acceleration.z, 0.01f);
    }

    @Test
    void testCircularBufferBehavior() {
        var historySize = 5;
        var customDynamics = new EntityDynamics(historySize);

        // Fill buffer beyond capacity
        for (int i = 0; i < 10; i++) {
            customDynamics.updatePosition(new Point3f(i, 0, 0), baseTime + i * 1000);
        }

        // History count should be capped at historySize
        assertEquals(historySize, customDynamics.getHistoryCount());

        // Most recent position should be the last one added
        assertEquals(new Point3f(9, 0, 0), customDynamics.getCurrentPosition());
        assertEquals(baseTime + 9000, customDynamics.getCurrentTimestamp());
    }

    @Test
    void testPositionPrediction() {
        // Create motion with constant velocity
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);

        // Predict position 2 seconds in the future
        var predicted = dynamics.predictPosition(2.0f);
        assertEquals(new Point3f(30, 0, 0), predicted);

        // Add acceleration
        dynamics.updatePosition(new Point3f(30, 0, 0), baseTime + 2000);

        // Now we have acceleration, prediction should account for it
        predicted = dynamics.predictPosition(1.0f);
        // Position = 30 + 20*1 + 0.5*10*1^2 = 30 + 20 + 5 = 55
        assertEquals(55.0f, predicted.x, 0.01f);
    }

    @Test
    void testVelocityPrediction() {
        // Create motion with constant acceleration
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);
        dynamics.updatePosition(new Point3f(30, 0, 0), baseTime + 2000);

        // Current velocity should be 20 units/second
        assertEquals(20.0f, dynamics.getVelocity().x, 0.01f);

        // Predict velocity 1 second in the future
        var predictedVelocity = dynamics.predictVelocity(1.0f);
        // v = v0 + a*t = 20 + 10*1 = 30
        assertEquals(30.0f, predictedVelocity.x, 0.01f);
    }

    @Test
    void testAverageVelocity() {
        // Create variable motion
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);
        dynamics.updatePosition(new Point3f(25, 0, 0), baseTime + 2000);
        dynamics.updatePosition(new Point3f(45, 0, 0), baseTime + 3000);

        var avgVelocity = dynamics.getAverageVelocity();
        // Average of velocities: (10 + 15 + 20) / 3 = 15
        assertEquals(15.0f, avgVelocity.x, 0.01f);
    }

    @Test
    void testMovementDetection() {
        // Initially not moving
        assertFalse(dynamics.isMoving(0.1f));

        // Add positions to create movement
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);

        // Should be moving at 10 units/second
        assertTrue(dynamics.isMoving(5.0f));
        assertTrue(dynamics.isMoving(9.9f));
        assertFalse(dynamics.isMoving(10.1f));
    }

    @Test
    void testAccelerationDetection() {
        // Initially not accelerating
        assertFalse(dynamics.isAccelerating(0.1f));

        // Create acceleration
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);
        dynamics.updatePosition(new Point3f(30, 0, 0), baseTime + 2000);

        // Should be accelerating at 10 units/second^2
        assertTrue(dynamics.isAccelerating(5.0f));
        assertTrue(dynamics.isAccelerating(9.9f));
        assertFalse(dynamics.isAccelerating(10.1f));
    }

    @Test
    void testReset() {
        // Add some history
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);
        dynamics.updatePosition(new Point3f(30, 0, 0), baseTime + 2000);

        // Verify state before reset
        assertEquals(3, dynamics.getHistoryCount());
        assertNotEquals(new Vector3f(0, 0, 0), dynamics.getVelocity());
        assertNotEquals(new Vector3f(0, 0, 0), dynamics.getAcceleration());

        // Reset
        dynamics.reset();

        // Verify reset state
        assertEquals(0, dynamics.getHistoryCount());
        assertEquals(new Vector3f(0, 0, 0), dynamics.getVelocity());
        assertEquals(new Vector3f(0, 0, 0), dynamics.getAcceleration());
        assertNull(dynamics.getCurrentPosition());
        assertEquals(-1, dynamics.getCurrentTimestamp());
    }

    @Test
    void test3DMotion() {
        // Test motion in all three dimensions
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 20, 30), baseTime + 1000);
        dynamics.updatePosition(new Point3f(30, 60, 90), baseTime + 2000);

        var velocity = dynamics.getVelocity();
        assertEquals(20.0f, velocity.x, 0.01f);
        assertEquals(40.0f, velocity.y, 0.01f);
        assertEquals(60.0f, velocity.z, 0.01f);

        var acceleration = dynamics.getAcceleration();
        assertEquals(10.0f, acceleration.x, 0.01f);
        assertEquals(20.0f, acceleration.y, 0.01f);
        assertEquals(30.0f, acceleration.z, 0.01f);
    }

    @Test
    void testNegativeVelocityAndAcceleration() {
        // Test motion in negative direction
        dynamics.updatePosition(new Point3f(100, 100, 100), baseTime);
        dynamics.updatePosition(new Point3f(90, 80, 70), baseTime + 1000);
        dynamics.updatePosition(new Point3f(70, 40, 10), baseTime + 2000);

        var velocity = dynamics.getVelocity();
        assertEquals(-20.0f, velocity.x, 0.01f);
        assertEquals(-40.0f, velocity.y, 0.01f);
        assertEquals(-60.0f, velocity.z, 0.01f);

        var acceleration = dynamics.getAcceleration();
        assertEquals(-10.0f, acceleration.x, 0.01f);
        assertEquals(-20.0f, acceleration.y, 0.01f);
        assertEquals(-30.0f, acceleration.z, 0.01f);
    }

    @Test
    void testIrregularTimeIntervals() {
        // Test with non-uniform time intervals
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 500); // 0.5 seconds
        dynamics.updatePosition(new Point3f(40, 0, 0), baseTime + 2000); // 1.5 seconds later

        // Velocity at last update: 30 units in 1.5 seconds = 20 units/second
        assertEquals(20.0f, dynamics.getVelocity().x, 0.01f);
    }

    @Test
    void testZeroTimeInterval() {
        // Test handling of zero time interval (same timestamp)
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime);

        // Velocity should remain zero due to zero time interval
        assertEquals(new Vector3f(0, 0, 0), dynamics.getVelocity());
    }

    @Test
    void testPredictionWithNoHistory() {
        // Prediction with no history should return null
        assertNull(dynamics.predictPosition(1.0f));
    }

    @Test
    void testPredictionWithSinglePosition() {
        dynamics.updatePosition(new Point3f(10, 20, 30), baseTime);

        // With only one position and zero velocity, prediction should return current position
        var predicted = dynamics.predictPosition(5.0f);
        assertEquals(new Point3f(10, 20, 30), predicted);
    }

    @Test
    void testLargeHistorySize() {
        // Test with large history to ensure performance
        var largeHistory = new EntityDynamics(1000);

        // Fill with many positions
        for (int i = 0; i < 2000; i++) {
            largeHistory.updatePosition(new Point3f(i, i * 2, i * 3), baseTime + i * 100);
        }

        assertEquals(1000, largeHistory.getHistoryCount());
        assertEquals(new Point3f(1999, 3998, 5997), largeHistory.getCurrentPosition());
    }

    @Test
    void testComplexMotionPattern() {
        // Simulate complex motion: acceleration, deceleration, direction change
        var times = new long[]{0, 1000, 2000, 3000, 4000, 5000};
        var positions = new Point3f[]{
            new Point3f(0, 0, 0),      // Start
            new Point3f(10, 0, 0),     // Accelerating
            new Point3f(30, 0, 0),     // Faster
            new Point3f(45, 0, 0),     // Slowing down
            new Point3f(50, 0, 0),     // Almost stopped
            new Point3f(48, 5, 0)      // Changed direction
        };

        for (int i = 0; i < positions.length; i++) {
            dynamics.updatePosition(positions[i], baseTime + times[i]);
        }

        // Final velocity should reflect the last movement
        var finalVelocity = dynamics.getVelocity();
        assertEquals(-2.0f, finalVelocity.x, 0.01f);
        assertEquals(5.0f, finalVelocity.y, 0.01f);
    }
}