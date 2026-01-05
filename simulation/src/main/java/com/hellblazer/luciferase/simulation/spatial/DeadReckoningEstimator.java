package com.hellblazer.luciferase.simulation.spatial;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.spatial.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dead reckoning estimator for smooth ghost entity position prediction.
 * <p>
 * Provides smooth animation between authoritative updates using:
 * - **Linear Prediction**: position + velocity × time
 * - **Smooth Correction**: Spread error over N frames when authoritative state arrives
 * - **Correction Clamping**: Max correction per frame to prevent jarring snaps
 * <p>
 * Algorithm:
 * <pre>
 * // Prediction phase (every frame)
 * predictedPosition = lastKnownPosition + velocity × (currentTime - lastUpdateTime)
 *
 * // Correction phase (when authoritative update arrives)
 * error = authoritativePosition - predictedPosition
 * correctionPerFrame = error / CORRECTION_FRAMES
 * if (correctionPerFrame.length() > velocity.length() × MAX_CORRECTION_PER_FRAME)
 *     correctionPerFrame = clamp(correctionPerFrame, velocity.length() × MAX_CORRECTION_PER_FRAME)
 *
 * // Apply correction over next N frames
 * for (frame in 1..CORRECTION_FRAMES)
 *     position += correctionPerFrame
 * </pre>
 * <p>
 * Performance targets:
 * - Prediction error < 10% of actual movement
 * - Position delta per frame < 5% of velocity
 * - Works with up to 200ms latency
 * - No visible jitter during normal operation
 * <p>
 * Thread-safe: All operations use concurrent data structures.
 *
 * @author hal.hildebrand
 */
public class DeadReckoningEstimator {

    /**
     * Number of frames to spread error correction over.
     * <p>
     * Smaller = faster convergence, higher jitter
     * Larger = slower convergence, smoother animation
     */
    public static final int CORRECTION_FRAMES = 3;

    /**
     * Maximum correction per frame as percentage of velocity.
     * <p>
     * Prevents jarring snaps when large prediction errors occur.
     */
    public static final float MAX_CORRECTION_PER_FRAME = 0.05f;  // 5%

    /**
     * Prediction state for an entity.
     * <p>
     * Tracks last known authoritative state for linear extrapolation.
     *
     * @param position Last known authoritative position
     * @param velocity Last known velocity (units per second)
     * @param timestamp When this state was observed (milliseconds)
     */
    public record PredictionState(
        Point3f position,
        Vector3f velocity,
        long timestamp
    ) {
    }

    /**
     * Correction state for an entity.
     * <p>
     * Tracks ongoing error correction to smooth out prediction mismatches.
     *
     * @param totalError      Remaining error to correct
     * @param framesRemaining Number of frames to spread correction over
     */
    public record CorrectionState(
        Vector3f totalError,
        int framesRemaining
    ) {
    }

    // Prediction state per entity
    private final Map<EntityID, PredictionState> predictions = new ConcurrentHashMap<>();

    // Active correction state per entity
    private final Map<EntityID, CorrectionState> corrections = new ConcurrentHashMap<>();

    /**
     * Predict entity position at current time.
     * <p>
     * Uses linear extrapolation: position + velocity × Δt
     * Applies smooth correction if active.
     *
     * @param ghost       Ghost entity to predict
     * @param currentTimeMs Current time in milliseconds
     * @return Predicted position (may include partial correction)
     */
    public Point3f predict(GhostEntity ghost, long currentTimeMs) {
        var predictionState = predictions.get(ghost.id());

        // No prediction state yet - return current position
        if (predictionState == null) {
            return new Point3f(ghost.position());
        }

        // Calculate time delta (convert ms to seconds)
        long deltaMs = currentTimeMs - predictionState.timestamp();
        float deltaSec = deltaMs / 1000f;

        // Linear prediction: position + velocity × time
        var predicted = new Point3f(predictionState.position());
        var velocity = new Vector3f(predictionState.velocity());
        predicted.scaleAdd(deltaSec, velocity, predicted);

        // Apply smooth correction if active
        var correction = corrections.get(ghost.id());
        if (correction != null && correction.framesRemaining() > 0) {
            // Calculate correction for this frame
            var correctionThisFrame = new Vector3f(correction.totalError());
            correctionThisFrame.scale(1f / correction.framesRemaining());

            // Clamp correction to prevent jarring snaps
            float velocityMagnitude = velocity.length();
            if (velocityMagnitude > 0.001f) {  // Avoid division by zero
                float maxCorrection = velocityMagnitude * MAX_CORRECTION_PER_FRAME;

                if (correctionThisFrame.length() > maxCorrection) {
                    correctionThisFrame.normalize();
                    correctionThisFrame.scale(maxCorrection);
                }
            }

            // Apply correction
            predicted.add(correctionThisFrame);

            // Update correction state for next frame
            var remainingError = new Vector3f(correction.totalError());
            remainingError.sub(correctionThisFrame);

            int newFramesRemaining = correction.framesRemaining() - 1;

            if (newFramesRemaining > 0) {
                corrections.put(ghost.id(),
                    new CorrectionState(remainingError, newFramesRemaining));
            } else {
                // Cleanup correction state when complete
                corrections.remove(ghost.id());
            }
        }

        return predicted;
    }

    /**
     * Handle authoritative position update.
     * <p>
     * Calculates prediction error and initiates smooth correction
     * over CORRECTION_FRAMES frames.
     *
     * @param ghost         Ghost entity that received update
     * @param authoritative Authoritative position from server
     */
    public void onAuthoritativeUpdate(GhostEntity ghost, Point3f authoritative) {
        var lastPrediction = predictions.get(ghost.id());

        // First update - no prediction to correct
        if (lastPrediction == null) {
            predictions.put(ghost.id(),
                new PredictionState(
                    new Point3f(authoritative),
                    new Vector3f(ghost.velocity()),
                    ghost.timestamp()
                ));
            return;
        }

        // Calculate what we would have predicted at this timestamp
        long deltaMs = ghost.timestamp() - lastPrediction.timestamp();
        float deltaSec = deltaMs / 1000f;

        var wouldHavePredicted = new Point3f(lastPrediction.position());
        wouldHavePredicted.scaleAdd(deltaSec, lastPrediction.velocity(), wouldHavePredicted);

        // Calculate prediction error: authoritative - predicted
        var error = new Vector3f();
        error.sub(authoritative, wouldHavePredicted);

        // Only start correction if there's meaningful error
        if (error.length() > 0.001f) {  // 1mm threshold
            corrections.put(ghost.id(),
                new CorrectionState(
                    new Vector3f(error),
                    CORRECTION_FRAMES
                ));
        }

        // Update prediction state with authoritative data
        predictions.put(ghost.id(),
            new PredictionState(
                new Point3f(authoritative),
                new Vector3f(ghost.velocity()),
                ghost.timestamp()
            ));
    }

    /**
     * Clear prediction state for an entity.
     * <p>
     * Use when entity is removed or no longer tracked.
     *
     * @param entityId Entity to clear
     */
    public void clearEntity(EntityID entityId) {
        predictions.remove(entityId);
        corrections.remove(entityId);
    }

    /**
     * Clear all prediction and correction state.
     */
    public void clear() {
        predictions.clear();
        corrections.clear();
    }

    /**
     * Get number of entities being tracked.
     *
     * @return Entity count
     */
    public int getTrackedEntityCount() {
        return predictions.size();
    }

    /**
     * Check if entity has active correction in progress.
     *
     * @param entityId Entity to check
     * @return true if correction active
     */
    public boolean hasActiveCorrection(EntityID entityId) {
        var correction = corrections.get(entityId);
        return correction != null && correction.framesRemaining() > 0;
    }

    @Override
    public String toString() {
        return String.format("DeadReckoningEstimator{tracked=%d, correcting=%d}",
                            predictions.size(), corrections.size());
    }
}
