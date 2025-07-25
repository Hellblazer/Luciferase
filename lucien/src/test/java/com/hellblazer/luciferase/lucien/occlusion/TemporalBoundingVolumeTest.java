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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for TemporalBoundingVolume
 *
 * @author hal.hildebrand
 */
public class TemporalBoundingVolumeTest {

    private LongEntityID entityId;
    private EntityBounds originalBounds;
    private Vector3f velocity;
    private int creationFrame;
    private int validityDuration;
    private float expansionFactor;

    @BeforeEach
    void setUp() {
        entityId = new LongEntityID(42);
        originalBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        velocity = new Vector3f(5, 0, 0); // Moving in X direction
        creationFrame = 100;
        validityDuration = 60; // 1 second at 60 FPS
        expansionFactor = 1.0f;
    }

    @Test
    void testConstructorWithExplicitParameters() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity, 
                                           creationFrame, validityDuration, expansionFactor);

        assertNotNull(tbv);
        assertEquals(entityId, tbv.getEntityId());
        assertEquals(originalBounds, tbv.getOriginalBounds());
        assertEquals(velocity, tbv.getVelocity());
        assertEquals(creationFrame, tbv.getCreationFrame());
        assertEquals(validityDuration, tbv.getValidityDuration());
        assertEquals(expansionFactor, tbv.getExpansionFactor());
    }

    @Test
    void testConstructorWithStrategy() {
        var strategy = FixedDurationTBVStrategy.defaultStrategy();
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity, 
                                           creationFrame, strategy);

        assertNotNull(tbv);
        assertEquals(entityId, tbv.getEntityId());
        assertEquals(originalBounds, tbv.getOriginalBounds());
        assertEquals(velocity, tbv.getVelocity());
        assertEquals(creationFrame, tbv.getCreationFrame());
        assertTrue(tbv.getValidityDuration() > 0);
        assertTrue(tbv.getExpansionFactor() >= 0);
    }

    @Test
    void testExpandedBoundsCalculation() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        var expanded = tbv.getExpandedBounds();
        var min = expanded.getMin();
        var max = expanded.getMax();

        // Velocity is 5 units/frame in X, validity is 60 frames
        // Total displacement = 5 * 60 = 300 units
        // Expansion = 300 + 1.0 = 301 units in X direction

        // Min should be expanded in negative direction
        assertEquals(-301.0f, min.x, 0.01f);
        assertEquals(-1.0f, min.y, 0.01f); // Only expansion factor
        assertEquals(-1.0f, min.z, 0.01f); // Only expansion factor

        // Max should be expanded in positive direction
        assertEquals(311.0f, max.x, 0.01f); // 10 + 301
        assertEquals(11.0f, max.y, 0.01f); // 10 + 1
        assertEquals(11.0f, max.z, 0.01f); // 10 + 1
    }

    @Test
    void testExpandedBoundsWithNegativeVelocity() {
        var negativeVelocity = new Vector3f(-3, -2, -1);
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, negativeVelocity,
                                           creationFrame, validityDuration, expansionFactor);

        var expanded = tbv.getExpandedBounds();
        var min = expanded.getMin();
        var max = expanded.getMax();

        // Absolute displacements: |(-3)*60|=180, |(-2)*60|=120, |(-1)*60|=60
        // Plus expansion factor of 1.0

        assertEquals(-181.0f, min.x, 0.01f);
        assertEquals(-121.0f, min.y, 0.01f);
        assertEquals(-61.0f, min.z, 0.01f);

        assertEquals(191.0f, max.x, 0.01f);
        assertEquals(131.0f, max.y, 0.01f);
        assertEquals(71.0f, max.z, 0.01f);
    }

    @Test
    void testValidityChecking() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        // Before creation frame
        assertFalse(tbv.isValid(creationFrame - 1));

        // At creation frame
        assertTrue(tbv.isValid(creationFrame));

        // During validity period
        assertTrue(tbv.isValid(creationFrame + 30));

        // At last valid frame
        assertTrue(tbv.isValid(creationFrame + validityDuration - 1));

        // After validity period
        assertFalse(tbv.isValid(creationFrame + validityDuration));
        assertFalse(tbv.isValid(creationFrame + validityDuration + 100));
    }

    @Test
    void testRemainingValidity() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        // At creation
        assertEquals(validityDuration, tbv.getRemainingValidity(creationFrame));

        // Halfway through
        assertEquals(30, tbv.getRemainingValidity(creationFrame + 30));

        // Almost expired
        assertEquals(1, tbv.getRemainingValidity(creationFrame + validityDuration - 1));

        // Expired
        assertEquals(0, tbv.getRemainingValidity(creationFrame + validityDuration));

        // Past expiration
        assertEquals(0, tbv.getRemainingValidity(creationFrame + validityDuration + 100));

        // Before creation
        assertEquals(0, tbv.getRemainingValidity(creationFrame - 10));
    }

    @Test
    void testGetBoundsAtFrame() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        // At creation frame (no movement yet)
        var boundsAtStart = tbv.getBoundsAtFrame(creationFrame);
        assertEquals(originalBounds.getMin(), boundsAtStart.getMin());
        assertEquals(originalBounds.getMax(), boundsAtStart.getMax());

        // After 10 frames (moved 50 units in X)
        var boundsAt10 = tbv.getBoundsAtFrame(creationFrame + 10);
        assertEquals(50.0f, boundsAt10.getMin().x, 0.01f);
        assertEquals(60.0f, boundsAt10.getMax().x, 0.01f);

        // After 30 frames (moved 150 units in X)
        var boundsAt30 = tbv.getBoundsAtFrame(creationFrame + 30);
        assertEquals(150.0f, boundsAt30.getMin().x, 0.01f);
        assertEquals(160.0f, boundsAt30.getMax().x, 0.01f);

        // Outside validity period returns expanded bounds
        var boundsOutside = tbv.getBoundsAtFrame(creationFrame + validityDuration + 10);
        assertEquals(tbv.getExpandedBounds().getMin(), boundsOutside.getMin());
        assertEquals(tbv.getExpandedBounds().getMax(), boundsOutside.getMax());
    }

    @Test
    void testToVolumeBounds() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        var volumeBounds = tbv.toVolumeBounds();
        var expanded = tbv.getExpandedBounds();

        assertEquals(expanded.getMin().x, volumeBounds.minX(), 0.01f);
        assertEquals(expanded.getMin().y, volumeBounds.minY(), 0.01f);
        assertEquals(expanded.getMin().z, volumeBounds.minZ(), 0.01f);
        assertEquals(expanded.getMax().x, volumeBounds.maxX(), 0.01f);
        assertEquals(expanded.getMax().y, volumeBounds.maxY(), 0.01f);
        assertEquals(expanded.getMax().z, volumeBounds.maxZ(), 0.01f);
    }

    @Test
    void testQualityScore() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        // At creation - perfect quality
        assertEquals(1.0f, tbv.getQuality(creationFrame), 0.01f);

        // Halfway through validity
        assertEquals(0.5f, tbv.getQuality(creationFrame + 30), 0.01f);

        // Almost expired
        assertEquals(0.017f, tbv.getQuality(creationFrame + 59), 0.02f);

        // Expired
        assertEquals(0.0f, tbv.getQuality(creationFrame + validityDuration), 0.01f);

        // Before creation
        assertEquals(0.0f, tbv.getQuality(creationFrame - 10), 0.01f);
    }

    @Test
    void testZeroVelocity() {
        var zeroVelocity = new Vector3f(0, 0, 0);
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, zeroVelocity,
                                           creationFrame, validityDuration, expansionFactor);

        var expanded = tbv.getExpandedBounds();

        // With zero velocity, expansion should only be the expansion factor
        assertEquals(-1.0f, expanded.getMin().x, 0.01f);
        assertEquals(-1.0f, expanded.getMin().y, 0.01f);
        assertEquals(-1.0f, expanded.getMin().z, 0.01f);
        assertEquals(11.0f, expanded.getMax().x, 0.01f);
        assertEquals(11.0f, expanded.getMax().y, 0.01f);
        assertEquals(11.0f, expanded.getMax().z, 0.01f);

        // Bounds at any frame should be the same (no movement)
        var boundsAtAnyFrame = tbv.getBoundsAtFrame(creationFrame + 30);
        assertEquals(originalBounds.getMin(), boundsAtAnyFrame.getMin());
        assertEquals(originalBounds.getMax(), boundsAtAnyFrame.getMax());
    }

    @Test
    void testZeroExpansionFactor() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, 0.0f);

        var expanded = tbv.getExpandedBounds();

        // Only velocity-based expansion
        var displacement = 5.0f * 60; // velocity * validityDuration

        assertEquals(-displacement, expanded.getMin().x, 0.01f);
        assertEquals(0.0f, expanded.getMin().y, 0.01f);
        assertEquals(0.0f, expanded.getMin().z, 0.01f);
        assertEquals(10.0f + displacement, expanded.getMax().x, 0.01f);
        assertEquals(10.0f, expanded.getMax().y, 0.01f);
        assertEquals(10.0f, expanded.getMax().z, 0.01f);
    }

    @Test
    void testLargeVelocity() {
        var largeVelocity = new Vector3f(100, 200, 300);
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, largeVelocity,
                                           creationFrame, validityDuration, expansionFactor);

        var expanded = tbv.getExpandedBounds();

        // Large displacements
        assertEquals(-6001.0f, expanded.getMin().x, 0.01f); // -(100*60 + 1)
        assertEquals(-12001.0f, expanded.getMin().y, 0.01f); // -(200*60 + 1)
        assertEquals(-18001.0f, expanded.getMin().z, 0.01f); // -(300*60 + 1)
    }

    @Test
    void testToString() {
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        var str = tbv.toString();
        assertTrue(str.contains("TBV"));
        assertTrue(str.contains("entity=" + entityId));
        assertTrue(str.contains("frame=100-160"));
        assertTrue(str.contains("velocity=(5.00,0.00,0.00)"));
        assertTrue(str.contains("expansion=1.00"));
    }

    @Test
    void testImmutability() {
        var mutableVelocity = new Vector3f(5, 0, 0);
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, mutableVelocity,
                                           creationFrame, validityDuration, expansionFactor);

        // Modify the original velocity
        mutableVelocity.set(10, 10, 10);

        // TBV's velocity should remain unchanged
        assertEquals(new Vector3f(5, 0, 0), tbv.getVelocity());

        // Get velocity and try to modify it
        var retrievedVelocity = tbv.getVelocity();
        retrievedVelocity.set(20, 20, 20);

        // TBV's velocity should still be unchanged
        assertEquals(new Vector3f(5, 0, 0), tbv.getVelocity());
    }

    @Test
    void testBoundsInterpolationAccuracy() {
        // Test accurate interpolation for various frames
        var tbv = new TemporalBoundingVolume(entityId, originalBounds, velocity,
                                           creationFrame, validityDuration, expansionFactor);

        for (int i = 0; i < validityDuration; i += 10) {
            var frame = creationFrame + i;
            var bounds = tbv.getBoundsAtFrame(frame);
            
            var expectedDisplacement = velocity.x * i;
            var expectedMinX = originalBounds.getMin().x + expectedDisplacement;
            var expectedMaxX = originalBounds.getMax().x + expectedDisplacement;

            assertEquals(expectedMinX, bounds.getMin().x, 0.01f);
            assertEquals(expectedMaxX, bounds.getMax().x, 0.01f);

            // Y and Z should not change (no velocity in those directions)
            assertEquals(originalBounds.getMin().y, bounds.getMin().y, 0.01f);
            assertEquals(originalBounds.getMin().z, bounds.getMin().z, 0.01f);
            assertEquals(originalBounds.getMax().y, bounds.getMax().y, 0.01f);
            assertEquals(originalBounds.getMax().z, bounds.getMax().z, 0.01f);
        }
    }

    @Test
    void testDifferentEntityTypes() {
        // Test with different entity ID types
        var stringId = "Entity_001";
        var tbv1 = new TemporalBoundingVolume(stringId, originalBounds, velocity,
                                            creationFrame, validityDuration, expansionFactor);
        assertEquals(stringId, tbv1.getEntityId());

        var intId = 12345;
        var tbv2 = new TemporalBoundingVolume(intId, originalBounds, velocity,
                                            creationFrame, validityDuration, expansionFactor);
        assertEquals(intId, tbv2.getEntityId());
    }
}