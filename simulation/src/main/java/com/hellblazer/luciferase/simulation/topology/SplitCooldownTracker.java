/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks split operation cooldowns to prevent retry spam.
 * <p>
 * When a split fails, a cooldown period is activated to prevent the same bubble
 * from retrying the split operation on every topology check cycle. This reduces
 * log spam and unnecessary work.
 * <p>
 * <b>Features</b>:
 * <ul>
 *   <li>Per-bubble cooldown tracking using ConcurrentHashMap</li>
 *   <li>Configurable cooldown duration (default 30 seconds)</li>
 *   <li>Deterministic testing via Clock interface injection</li>
 *   <li>Thread-safe operations for concurrent topology checks</li>
 *   <li>Automatic cooldown clearing on successful split</li>
 * </ul>
 * <p>
 * <b>Usage</b>:
 * <pre>
 * var tracker = new SplitCooldownTracker();
 * tracker.setClock(Clock.system()); // or TestClock for tests
 *
 * // In topology check loop:
 * if (state == DensityState.NEEDS_SPLIT) {
 *     if (tracker.isOnCooldown(bubbleId)) {
 *         // Skip split attempt
 *         continue;
 *     }
 *
 *     var result = executor.execute(splitProposal);
 *     if (!result.success()) {
 *         tracker.recordFailure(bubbleId);
 *     } else {
 *         tracker.recordSuccess(bubbleId);
 *     }
 * }
 * </pre>
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 * P1.2: Cooldown Timer Implementation for Split Retry Prevention
 *
 * @author hal.hildebrand
 */
public class SplitCooldownTracker {

    private static final Logger log = LoggerFactory.getLogger(SplitCooldownTracker.class);
    private static final long DEFAULT_COOLDOWN_MS = 30_000L; // 30 seconds

    private final ConcurrentHashMap<UUID, Long> splitCooldowns = new ConcurrentHashMap<>();
    private final long cooldownDurationMs;
    private volatile Clock clock = Clock.system();

    /**
     * Creates a cooldown tracker with default 30-second cooldown.
     */
    public SplitCooldownTracker() {
        this(DEFAULT_COOLDOWN_MS);
    }

    /**
     * Creates a cooldown tracker with custom cooldown duration.
     *
     * @param cooldownDurationMs cooldown duration in milliseconds
     * @throws IllegalArgumentException if cooldownDurationMs <= 0
     */
    public SplitCooldownTracker(long cooldownDurationMs) {
        if (cooldownDurationMs <= 0) {
            throw new IllegalArgumentException("Cooldown duration must be positive: " + cooldownDurationMs);
        }
        this.cooldownDurationMs = cooldownDurationMs;
    }

    /**
     * Sets the clock to use for time tracking.
     * <p>
     * For deterministic testing, inject a {@link com.hellblazer.luciferase.simulation.distributed.integration.TestClock}
     * to control time progression.
     *
     * @param clock the clock to use (must not be null)
     * @throws NullPointerException if clock is null
     */
    public void setClock(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Checks if a bubble is currently on cooldown.
     * <p>
     * Returns true if the bubble has a recorded failure and the cooldown period
     * has not yet expired.
     *
     * @param bubbleId the bubble ID to check
     * @return true if on cooldown, false otherwise
     * @throws NullPointerException if bubbleId is null
     */
    public boolean isOnCooldown(UUID bubbleId) {
        java.util.Objects.requireNonNull(bubbleId, "bubbleId must not be null");

        var cooldownUntil = splitCooldowns.get(bubbleId);
        if (cooldownUntil == null) {
            return false;
        }

        var now = clock.currentTimeMillis();
        if (now >= cooldownUntil) {
            // Cooldown expired, clean up
            splitCooldowns.remove(bubbleId);
            return false;
        }

        return true;
    }

    /**
     * Records a split failure for a bubble, activating the cooldown period.
     * <p>
     * Logs a message with [SPLIT-COOLDOWN] prefix for traceability.
     *
     * @param bubbleId the bubble ID that failed to split
     * @throws NullPointerException if bubbleId is null
     */
    public void recordFailure(UUID bubbleId) {
        java.util.Objects.requireNonNull(bubbleId, "bubbleId must not be null");

        var cooldownUntil = clock.currentTimeMillis() + cooldownDurationMs;
        splitCooldowns.put(bubbleId, cooldownUntil);

        log.info("[SPLIT-COOLDOWN] Bubble {} failed split, cooldown {}ms (until t={})",
                 bubbleId, cooldownDurationMs, cooldownUntil);
    }

    /**
     * Records a successful split for a bubble, clearing any active cooldown.
     * <p>
     * This allows the bubble to split again immediately if needed, since the
     * previous failure condition has been resolved.
     *
     * @param bubbleId the bubble ID that successfully split
     * @throws NullPointerException if bubbleId is null
     */
    public void recordSuccess(UUID bubbleId) {
        java.util.Objects.requireNonNull(bubbleId, "bubbleId must not be null");

        var removed = splitCooldowns.remove(bubbleId);
        if (removed != null) {
            log.debug("[SPLIT-COOLDOWN] Bubble {} split succeeded, cooldown cleared", bubbleId);
        }
    }

    /**
     * Gets the current cooldown duration in milliseconds.
     *
     * @return cooldown duration in milliseconds
     */
    public long getCooldownDurationMs() {
        return cooldownDurationMs;
    }

    /**
     * Gets the number of bubbles currently on cooldown.
     * <p>
     * Useful for monitoring and metrics.
     *
     * @return number of bubbles on cooldown
     */
    public int getActiveCooldownCount() {
        // Clean up expired cooldowns before counting
        var now = clock.currentTimeMillis();
        splitCooldowns.entrySet().removeIf(entry -> now >= entry.getValue());
        return splitCooldowns.size();
    }
}
