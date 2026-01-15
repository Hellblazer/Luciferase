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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import com.hellblazer.luciferase.simulation.spatial.DeadReckoningEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Objects;

/**
 * GhostConsistencyValidator - Position/Velocity Validation for Ghost→MIGRATING_IN Transitions (Phase 7D.2 Part 1).
 * <p>
 * Validates that ghost entity state is consistent with expected position and velocity during
 * GHOST→MIGRATING_IN transitions. Uses dead reckoning to extrapolate expected position and
 * compares against authoritative state to detect position divergence.
 * <p>
 * <strong>Validation Criteria:</strong>
 * <ul>
 *   <li>Position valid: distance(expected, extrapolated) ≤ maxMovement × accuracyTarget</li>
 *   <li>Velocity valid: no major reversals (dot product ≥ -0.5)</li>
 *   <li>Default accuracy target: 5% of maximum possible movement</li>
 *   <li>Default extrapolation window: 1000ms</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var validator = new GhostConsistencyValidator(0.05f, 1000L);  // 5% accuracy, 1s window
 *
 * // During GHOST→MIGRATING_IN transition
 * var report = validator.validateConsistency(entityId, expectedPosition, expectedVelocity);
 *
 * if (!report.positionValid()) {
 *     log.warn("Position inconsistency detected: {}", report.message());
 *     // Consider rejecting migration or requesting retry
 * }
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> This class is thread-safe. GhostStateManager operations are
 * delegated and DeadReckoningEstimator is thread-safe.
 *
 * @author hal.hildebrand
 */
public class GhostConsistencyValidator {

    private static final Logger log = LoggerFactory.getLogger(GhostConsistencyValidator.class);

    /**
     * Epsilon for floating point comparisons (FP32 tolerance).
     * Used for zero-velocity division guards (I1 audit finding).
     */
    private static final float EPSILON = 1e-6f;

    /**
     * Consistency validation report.
     *
     * @param positionValid  true if position is within acceptable threshold
     * @param velocityValid  true if velocity is consistent (no major reversals)
     * @param positionDelta  Distance between expected and extrapolated position
     * @param velocityDelta  Difference in velocity magnitude
     * @param message        Human-readable validation message
     */
    public record ConsistencyReport(
        boolean positionValid,
        boolean velocityValid,
        float positionDelta,
        float velocityDelta,
        String message
    ) {
    }

    /**
     * Accuracy target as percentage of maximum movement (default 5%).
     * Position error must be ≤ maxMovement × accuracyTarget to pass validation.
     */
    private final float accuracyTargetPercent;

    /**
     * Extrapolation window in milliseconds (default 1000ms).
     * Used to calculate maximum expected movement: velocity × extrapolationWindow.
     */
    private final long extrapolationWindowMs;

    /**
     * Reference to ghost state manager for accessing ghost entities.
     */
    private GhostStateManager ghostStateManager;
    private volatile Clock clock = Clock.system();

    /**
     * Create GhostConsistencyValidator with default parameters.
     * Default: 5% accuracy target, 1000ms extrapolation window.
     */
    public GhostConsistencyValidator() {
        this(0.05f, 1000L);
    }

    /**
     * Create GhostConsistencyValidator with custom parameters.
     *
     * @param accuracyTargetPercent  Accuracy target as percentage (e.g., 0.05 = 5%)
     * @param extrapolationWindowMs  Extrapolation window in milliseconds
     */
    public GhostConsistencyValidator(float accuracyTargetPercent, long extrapolationWindowMs) {
        if (accuracyTargetPercent <= 0 || accuracyTargetPercent > 1.0f) {
            throw new IllegalArgumentException("accuracyTargetPercent must be in (0, 1.0]");
        }
        if (extrapolationWindowMs <= 0) {
            throw new IllegalArgumentException("extrapolationWindowMs must be positive");
        }

        this.accuracyTargetPercent = accuracyTargetPercent;
        this.extrapolationWindowMs = extrapolationWindowMs;

        log.debug("GhostConsistencyValidator created: accuracy={}%, window={}ms",
                 accuracyTargetPercent * 100, extrapolationWindowMs);
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Set ghost state manager reference.
     * Must be called before validateConsistency().
     *
     * @param ghostStateManager Ghost state manager instance
     */
    public void setGhostStateManager(GhostStateManager ghostStateManager) {
        this.ghostStateManager = Objects.requireNonNull(ghostStateManager, "ghostStateManager must not be null");
    }

    /**
     * Validate consistency of ghost entity state against expected position and velocity.
     * <p>
     * Validation algorithm:
     * <ol>
     *   <li>Retrieve ghost from GhostStateManager</li>
     *   <li>Get extrapolated position via DeadReckoningEstimator</li>
     *   <li>Calculate position delta: distance(expected, extrapolated)</li>
     *   <li>Calculate maximum allowed movement: expectedVelocity.length() × extrapolationWindow / 1000</li>
     *   <li>Position valid if: positionDelta ≤ maxMovement × accuracyTarget</li>
     *   <li>Velocity valid if: dot product ≥ -0.5 (no major reversals)</li>
     * </ol>
     *
     * @param entityId         Entity ID to validate
     * @param expectedPosition Expected authoritative position
     * @param expectedVelocity Expected authoritative velocity
     * @return ConsistencyReport with validation results
     */
    public ConsistencyReport validateConsistency(Object entityId, Point3f expectedPosition, Vector3f expectedVelocity) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(expectedPosition, "expectedPosition must not be null");
        Objects.requireNonNull(expectedVelocity, "expectedVelocity must not be null");

        if (ghostStateManager == null) {
            throw new IllegalStateException("ghostStateManager not set - call setGhostStateManager() first");
        }

        // Get ghost from GhostStateManager
        var ghost = ghostStateManager.getGhost((com.hellblazer.luciferase.simulation.entity.StringEntityID) entityId);
        if (ghost == null) {
            // No ghost tracked - consider this valid (ghost may have been removed)
            return new ConsistencyReport(
                true,
                true,
                0f,
                0f,
                "No ghost tracked for entity, validation passed"
            );
        }

        // Get extrapolated position via dead reckoning
        var currentTime = clock.currentTimeMillis();
        var extrapolatedPosition = ghostStateManager.getGhostPosition(
            (com.hellblazer.luciferase.simulation.entity.StringEntityID) entityId,
            currentTime
        );

        if (extrapolatedPosition == null) {
            // No prediction available - consider this valid (first update)
            return new ConsistencyReport(
                true,
                true,
                0f,
                0f,
                "No prediction available, validation passed"
            );
        }

        // Calculate position delta
        var positionDelta = expectedPosition.distance(extrapolatedPosition);

        // Calculate maximum allowed movement based on velocity and extrapolation window
        var expectedSpeed = expectedVelocity.length();

        // Zero-velocity guard (I1 audit finding): prevent division by zero
        var maxMovement = expectedSpeed * (extrapolationWindowMs / 1000f);
        var maxAllowedDelta = maxMovement * accuracyTargetPercent;

        // If velocity is essentially zero, allow minimal position delta
        if (expectedSpeed < EPSILON) {
            maxAllowedDelta = 0.1f;  // Allow 0.1 unit position delta for stationary objects
        }

        var positionValid = positionDelta <= maxAllowedDelta;

        // Velocity validation: check for major reversals using actual ghost velocity
        var ghostVelocity = ghostStateManager.getGhostVelocity(
            (com.hellblazer.luciferase.simulation.entity.StringEntityID) entityId
        );

        // Calculate velocity delta (difference in magnitude)
        var ghostSpeed = ghostVelocity.length();
        var velocityDelta = Math.abs(ghostSpeed - expectedSpeed);

        // Check for major reversals using dot product
        var dotProduct = 1.0f;  // Default to consistent

        // Zero-velocity guard: only check dot product if both velocities are non-zero
        if (ghostSpeed >= EPSILON && expectedSpeed >= EPSILON) {
            ghostVelocity.normalize();
            var normalizedExpected = new Vector3f(expectedVelocity);
            normalizedExpected.normalize();
            dotProduct = ghostVelocity.dot(normalizedExpected);
        }

        var velocityValid = dotProduct >= -0.5f;  // No major reversals

        // Build validation message
        var message = String.format(
            "Position delta: %.3f (max: %.3f, valid: %s), Velocity delta: %.3f (valid: %s)",
            positionDelta, maxAllowedDelta, positionValid, velocityDelta, velocityValid
        );

        if (!positionValid || !velocityValid) {
            log.warn("Consistency validation failed for {}: {}", entityId, message);
        } else {
            log.debug("Consistency validation passed for {}: {}", entityId, message);
        }

        return new ConsistencyReport(positionValid, velocityValid, positionDelta, velocityDelta, message);
    }

    /**
     * Get accuracy target percentage.
     *
     * @return Accuracy target (e.g., 0.05 = 5%)
     */
    public float getAccuracyTargetPercent() {
        return accuracyTargetPercent;
    }

    /**
     * Get extrapolation window in milliseconds.
     *
     * @return Extrapolation window
     */
    public long getExtrapolationWindowMs() {
        return extrapolationWindowMs;
    }

    @Override
    public String toString() {
        return String.format("GhostConsistencyValidator{accuracy=%.1f%%, window=%dms}",
                            accuracyTargetPercent * 100, extrapolationWindowMs);
    }
}
