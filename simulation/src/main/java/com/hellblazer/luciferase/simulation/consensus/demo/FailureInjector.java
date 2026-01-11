/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injects and manages Byzantine failures in the simulation.
 * <p>
 * Responsibilities:
 * - Inject node crash (stop bubble tick processing)
 * - Inject slow node (500ms delays)
 * - Inject network partition (mark unreachable)
 * - Inject Byzantine voting (vote incorrectly)
 * - Recover failed nodes
 * - Track failure timeline and impact
 * <p>
 * FAILURE TYPES:
 * - NODE_CRASH: Stop all processing on node
 * - SLOW_NODE: Inject 500ms delay on operations
 * - NETWORK_PARTITION: Mark node unreachable
 * - BYZANTINE_VOTE: Vote opposite of committee decision
 * - MESSAGE_DROP: Drop 50% of messages
 * - DELAYED_RESPONSE: Random 1-2s delays on responses
 * <p>
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for thread-safe failure tracking.
 * <p>
 * Phase 8D Day 1: Byzantine Failure Injection
 *
 * @author hal.hildebrand
 */
public class FailureInjector {

    private static final Logger log = LoggerFactory.getLogger(FailureInjector.class);

    /**
     * Failure types supported by injector.
     */
    public enum FailureType {
        NODE_CRASH,           // Stop bubble tick processing
        SLOW_NODE,            // 500ms delay on operations
        NETWORK_PARTITION,    // Mark unreachable, isolate
        BYZANTINE_VOTE,       // Vote opposite of committee decision
        MESSAGE_DROP,         // Drop 50% of messages
        DELAYED_RESPONSE      // Random 1-2s delays
    }

    /**
     * Failure event record.
     *
     * @param bubbleIndex Bubble index affected
     * @param type        Failure type
     * @param timestampMs Timestamp when failure injected
     * @param durationMs  Duration of failure (0 = indefinite)
     */
    public record FailureEvent(int bubbleIndex, FailureType type, long timestampMs, long durationMs) {
    }

    private final ConsensusBubbleGrid grid;
    private final SimulationRunner runner;

    /**
     * Active failures: bubbleIndex -> FailureEvent
     */
    private final ConcurrentHashMap<Integer, FailureEvent> activeFailures = new ConcurrentHashMap<>();

    /**
     * Failure timeline (all events, including recovered)
     */
    private final List<FailureEvent> timeline = new ArrayList<>();

    /**
     * Create FailureInjector.
     *
     * @param grid   ConsensusBubbleGrid for topology
     * @param runner SimulationRunner for tick interception
     */
    public FailureInjector(ConsensusBubbleGrid grid, SimulationRunner runner) {
        this.grid = Objects.requireNonNull(grid, "grid must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");

        log.debug("FailureInjector initialized for 4 bubbles");
    }

    /**
     * Inject failure on specified bubble.
     *
     * @param bubbleIndex Bubble index (0-3)
     * @param type        Failure type to inject
     * @throws IllegalArgumentException if bubbleIndex invalid
     */
    public void injectFailure(int bubbleIndex, FailureType type) {
        injectFailure(bubbleIndex, type, 0); // 0 = indefinite duration
    }

    /**
     * Inject failure on specified bubble with duration.
     *
     * @param bubbleIndex Bubble index (0-3)
     * @param type        Failure type to inject
     * @param durationMs  Duration in milliseconds (0 = indefinite)
     * @throws IllegalArgumentException if bubbleIndex invalid
     */
    public void injectFailure(int bubbleIndex, FailureType type, long durationMs) {
        validateBubbleIndex(bubbleIndex);
        Objects.requireNonNull(type, "type must not be null");

        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration must be non-negative, got " + durationMs);
        }

        var event = new FailureEvent(bubbleIndex, type, System.currentTimeMillis(), durationMs);
        activeFailures.put(bubbleIndex, event);
        timeline.add(event);

        log.info("Injected failure: bubble={}, type={}, duration={}ms", bubbleIndex, type, durationMs);

        // Schedule automatic recovery if duration specified
        if (durationMs > 0) {
            scheduleAutoRecovery(bubbleIndex, durationMs);
        }
    }

    /**
     * Recover node from failure.
     *
     * @param bubbleIndex Bubble index to recover
     * @throws IllegalArgumentException if bubbleIndex invalid
     */
    public void recoverNode(int bubbleIndex) {
        validateBubbleIndex(bubbleIndex);

        var removed = activeFailures.remove(bubbleIndex);
        if (removed != null) {
            log.info("Recovered node: bubble={}, was={}", bubbleIndex, removed.type());
        } else {
            log.debug("Recovered node {} (was not failing)", bubbleIndex);
        }
    }

    /**
     * Recover all failed nodes.
     */
    public void recoverAll() {
        var failedCount = activeFailures.size();
        activeFailures.clear();
        log.info("Recovered all {} failed nodes", failedCount);
    }

    /**
     * Check if node is currently failing.
     *
     * @param bubbleIndex Bubble index to check
     * @return true if node is failing
     */
    public boolean isNodeFailing(int bubbleIndex) {
        validateBubbleIndex(bubbleIndex);
        return activeFailures.containsKey(bubbleIndex);
    }

    /**
     * Get failure type for node.
     *
     * @param bubbleIndex Bubble index to check
     * @return FailureType if failing, null otherwise
     */
    public FailureType getFailureType(int bubbleIndex) {
        validateBubbleIndex(bubbleIndex);
        var event = activeFailures.get(bubbleIndex);
        return event != null ? event.type() : null;
    }

    /**
     * Get failure duration for node.
     *
     * @param bubbleIndex Bubble index to check
     * @return Duration in milliseconds (0 = indefinite), or 0 if not failing
     */
    public long getFailureDuration(int bubbleIndex) {
        validateBubbleIndex(bubbleIndex);
        var event = activeFailures.get(bubbleIndex);
        return event != null ? event.durationMs() : 0;
    }

    /**
     * Get complete failure timeline.
     *
     * @return Unmodifiable list of all failure events (includes recovered)
     */
    public List<FailureEvent> getFailureTimeline() {
        return Collections.unmodifiableList(timeline);
    }

    /**
     * Validate bubble index is within valid range.
     *
     * @param bubbleIndex Bubble index to validate
     * @throws IllegalArgumentException if invalid
     */
    private void validateBubbleIndex(int bubbleIndex) {
        if (bubbleIndex < 0 || bubbleIndex >= 4) {
            throw new IllegalArgumentException(
                    "Bubble index must be 0-3, got " + bubbleIndex);
        }
    }

    /**
     * Schedule automatic recovery after duration.
     *
     * @param bubbleIndex Bubble to recover
     * @param durationMs  Duration in milliseconds
     */
    private void scheduleAutoRecovery(int bubbleIndex, long durationMs) {
        // Simple timer-based recovery (could use ScheduledExecutorService for production)
        new Thread(() -> {
            try {
                Thread.sleep(durationMs);
                recoverNode(bubbleIndex);
            } catch (InterruptedException e) {
                log.warn("Auto-recovery interrupted for bubble {}", bubbleIndex);
                Thread.currentThread().interrupt();
            }
        }, "FailureInjector-AutoRecover-" + bubbleIndex).start();
    }
}
