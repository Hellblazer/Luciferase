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

import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventReprocessor - Out-of-Order Event Queue with Bounded Lookahead (Phase 7C.2)
 *
 * Handles events arriving out-of-order by queueing them until they can be processed
 * in causal order. Uses a bounded lookahead window (100-500ms) to limit queue size
 * and detect deadlocks where events can never be processed.
 *
 * KEY CONCEPTS:
 * - Bounded Queue: Maximum of 1000 events to prevent unbounded memory growth
 * - Lookahead Window: Time window (configurable) beyond which events are force-processed
 * - Causality Ordering: PriorityQueue sorts by Lamport clock for sequential processing
 * - Metrics: Tracks queue depth, drops, and reprocessing attempts
 *
 * USAGE:
 * <pre>
 *   var reprocessor = new EventReprocessor(100, 500); // Min 100ms, max 500ms window
 *
 *   // Queue event when it arrives out-of-order
 *   reprocessor.queueEvent(event);
 *
 *   // On each tick, attempt to process ready events
 *   var processed = reprocessor.processReady(currentTime, processor::process);
 *
 *   // Monitor queue health
 *   log.debug("Queue depth: {}, drops: {}", reprocessor.getQueueDepth(), reprocessor.getTotalDropped());
 * </pre>
 *
 * THREAD SAFETY: All operations are thread-safe using ConcurrentHashMap and synchronized queue access.
 *
 * @author hal.hildebrand
 */
public class EventReprocessor {

    private static final Logger log = LoggerFactory.getLogger(EventReprocessor.class);

    /**
     * Callback interface for processing events.
     */
    @FunctionalInterface
    public interface EventProcessor {
        /**
         * Process an event.
         *
         * @param event Event to process
         */
        void process(EntityUpdateEvent event);
    }

    /**
     * Gap state enum for tracking event loss scenarios.
     */
    public enum GapState {
        NONE,       // Normal operation, no gap detected
        DETECTED,   // Gap detected (overflow occurred)
        TIMEOUT     // Gap timeout expired, view change triggered
    }

    /**
     * Configuration for lookahead window.
     */
    public static class Configuration {
        public final long minLookaheadMs;
        public final long maxLookaheadMs;
        public final int  maxQueueSize;
        public final long gapTimeoutMs;

        public Configuration(long minLookaheadMs, long maxLookaheadMs) {
            this(minLookaheadMs, maxLookaheadMs, 1000, 30000);
        }

        public Configuration(long minLookaheadMs, long maxLookaheadMs, int maxQueueSize) {
            this(minLookaheadMs, maxLookaheadMs, maxQueueSize, 30000);
        }

        public Configuration(long minLookaheadMs, long maxLookaheadMs, int maxQueueSize, long gapTimeoutMs) {
            this.minLookaheadMs = minLookaheadMs;
            this.maxLookaheadMs = maxLookaheadMs;
            this.maxQueueSize = maxQueueSize;
            this.gapTimeoutMs = gapTimeoutMs;
        }
    }

    /**
     * Configuration for this reprocessor.
     */
    private final Configuration config;

    /**
     * Queue of pending events sorted by Lamport clock.
     * Comparator orders by ascending Lamport clock for FIFO processing within clock values.
     */
    private final PriorityQueue<EntityUpdateEvent> pendingQueue;

    /**
     * Track events being held (by entity ID) to detect duplicates.
     */
    private final ConcurrentHashMap<String, Long> eventTracker;

    /**
     * Timestamp of first event in queue (for lookahead window calculation).
     */
    private volatile long windowStartTime = 0L;

    /**
     * Metrics: Total events dropped (queue overflow).
     */
    private final AtomicLong totalDropped = new AtomicLong(0L);

    /**
     * Metrics: Total events reprocessed.
     */
    private final AtomicLong totalReprocessed = new AtomicLong(0L);

    /**
     * Metrics: Total gap cycles detected.
     * A gap cycle begins on the first queue overflow (NONE → DETECTED) and ends when resetGap() is called.
     * Multiple queue overflows during a single gap cycle count as one gap cycle.
     * Use getTotalDropped() to monitor individual overflow events.
     */
    private final AtomicLong totalGaps = new AtomicLong(0L);

    /**
     * Current gap state.
     */
    private volatile GapState gapState = GapState.NONE;

    /**
     * Timestamp when current gap was detected (milliseconds).
     */
    private volatile long gapStartTimeMs = 0L;

    /**
     * Callback to invoke when gap timeout expires.
     */
    private volatile Runnable viewChangeCallback = null;

    /**
     * Create an EventReprocessor with specified lookahead window.
     *
     * @param minLookaheadMs Minimum lookahead window (milliseconds)
     * @param maxLookaheadMs Maximum lookahead window (milliseconds)
     */
    public EventReprocessor(long minLookaheadMs, long maxLookaheadMs) {
        this(new Configuration(minLookaheadMs, maxLookaheadMs));
    }

    /**
     * Create an EventReprocessor with configuration.
     *
     * @param config Configuration with window and queue sizes
     */
    public EventReprocessor(Configuration config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.pendingQueue = new PriorityQueue<>(
            (e1, e2) -> Long.compare(e1.lamportClock(), e2.lamportClock())
        );
        this.eventTracker = new ConcurrentHashMap<>();

        log.debug("EventReprocessor created: min={}ms, max={}ms, maxQueue={}",
                 config.minLookaheadMs, config.maxLookaheadMs, config.maxQueueSize);
    }

    /**
     * Queue an event for potential reprocessing if it arrives out-of-order.
     * Events are held until they can be processed sequentially.
     *
     * @param event Event to queue
     * @return true if queued successfully, false if dropped (queue overflow)
     */
    public synchronized boolean queueEvent(EntityUpdateEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        // Check queue overflow
        if (pendingQueue.size() >= config.maxQueueSize) {
            totalDropped.incrementAndGet();

            // Detect gap if not already in gap state
            if (gapState == GapState.NONE) {
                gapState = GapState.DETECTED;
                gapStartTimeMs = System.currentTimeMillis();
                totalGaps.incrementAndGet();
                log.warn("Gap detected: queue overflow at size={}, gap#{}", config.maxQueueSize, totalGaps.get());
            }

            log.warn("Event queue overflow: dropping event {} at clock={}",
                    event.entityId(), event.lamportClock());
            return false;
        }

        // Track event to detect duplicates
        var entityKey = event.entityId().toString();
        eventTracker.put(entityKey, event.lamportClock());

        // Add to queue (will be sorted by Lamport clock)
        pendingQueue.offer(event);

        // Update window start time on first event
        if (windowStartTime == 0L) {
            windowStartTime = event.timestamp();
        }

        if (pendingQueue.size() % 10 == 0) {
            log.debug("Event queued: queue depth={}, clock={}", pendingQueue.size(), event.lamportClock());
        }

        return true;
    }

    /**
     * Process events that are ready (within lookahead window or force-ready).
     * Calls processor for each ready event in Lamport clock order.
     *
     * @param currentTime Current simulation time (milliseconds)
     * @param processor   Callback to process each ready event
     * @return Number of events processed
     */
    public synchronized int processReady(long currentTime, EventProcessor processor) {
        Objects.requireNonNull(processor, "processor must not be null");

        int processed = 0;

        while (!pendingQueue.isEmpty()) {
            var event = pendingQueue.peek();

            // Calculate time since event arrived
            long timeSinceArrival = currentTime - event.timestamp();

            // Check if event is ready:
            // 1. Within min lookahead window, OR
            // 2. Within max lookahead window (force-process to avoid deadlock)
            boolean withinMinWindow = timeSinceArrival < config.minLookaheadMs;
            boolean withinMaxWindow = timeSinceArrival < config.maxLookaheadMs;

            if (withinMinWindow) {
                // Event too young, not ready yet
                break;
            }

            // Process event if within window or force-ready (max window exceeded)
            pendingQueue.poll();
            var entityKey = event.entityId().toString();
            eventTracker.remove(entityKey);

            try {
                processor.process(event);
                totalReprocessed.incrementAndGet();
                processed++;

                if (processed % 10 == 0) {
                    log.debug("Reprocessed: {} events (queue depth={})", processed, pendingQueue.size());
                }
            } catch (Exception e) {
                log.error("Error processing queued event: entity={}, clock={}", event.entityId(), event.lamportClock(), e);
            }

            // Reset window start if queue is now empty
            if (pendingQueue.isEmpty()) {
                windowStartTime = 0L;
            }
        }

        return processed;
    }

    /**
     * Get current queue depth (number of events waiting).
     *
     * @return Number of events in queue
     */
    public synchronized int getQueueDepth() {
        return pendingQueue.size();
    }

    /**
     * Get total events dropped due to queue overflow.
     *
     * @return Total dropped count
     */
    public long getTotalDropped() {
        return totalDropped.get();
    }

    /**
     * Get total events reprocessed.
     *
     * @return Total reprocessed count
     */
    public long getTotalReprocessed() {
        return totalReprocessed.get();
    }

    /**
     * Check if queue is healthy (not overflowing or deadlocked).
     * Returns false if queue is growing beyond expected bounds.
     * Healthy means: queue at or below 50% capacity AND no drops.
     *
     * @return true if queue appears healthy
     */
    public synchronized boolean isHealthy() {
        return pendingQueue.size() <= config.maxQueueSize / 2
            && totalDropped.get() == 0;
    }

    /**
     * Get queue configuration.
     *
     * @return Configuration instance
     */
    public Configuration getConfiguration() {
        return config;
    }

    /**
     * Clear all pending events (for testing or reset).
     */
    public synchronized void clear() {
        pendingQueue.clear();
        eventTracker.clear();
        windowStartTime = 0L;
        log.debug("EventReprocessor cleared");
    }

    /**
     * Get snapshot of current queue state (for debugging).
     * Returns events in sorted order (by Lamport clock).
     *
     * @return Ordered list of pending events (sorted by Lamport clock)
     */
    public synchronized List<EntityUpdateEvent> getPendingEvents() {
        // Extract all events in sorted order (poll maintains heap invariant)
        var result = new ArrayList<EntityUpdateEvent>();
        var tempQueue = new PriorityQueue<>(pendingQueue);

        while (!tempQueue.isEmpty()) {
            result.add(tempQueue.poll());
        }

        return result;
    }

    /**
     * Check gap timeout and trigger view change if timeout expired.
     * Should be called periodically during tick processing.
     *
     * @param currentTimeMs Current time in milliseconds
     */
    public synchronized void checkGapTimeout(long currentTimeMs) {
        if ((gapState == GapState.DETECTED) &&
            currentTimeMs - gapStartTimeMs >= config.gapTimeoutMs) {
            acceptGap();
        }
    }

    /**
     * Accept the gap and trigger view change callback.
     * Called when gap timeout expires.
     */
    private void acceptGap() {
        gapState = GapState.TIMEOUT;
        log.warn("Gap timeout expired after {}ms, accepting gap and triggering view change",
                config.gapTimeoutMs);

        if (viewChangeCallback != null) {
            try {
                viewChangeCallback.run();
            } catch (Exception e) {
                log.error("Error in viewChangeCallback", e);
            }
        }
    }

    /**
     * Set callback to invoke when gap timeout expires.
     *
     * @param callback Callback to invoke on gap timeout
     */
    public void setViewChangeCallback(Runnable callback) {
        this.viewChangeCallback = callback;
    }

    /**
     * Reset gap state to NONE, clearing gap tracking.
     * Called after queue drains or view change completes.
     */
    public synchronized void resetGap() {
        gapState = GapState.NONE;
        gapStartTimeMs = 0L;
        log.debug("Gap reset - returning to normal operation");
    }

    /**
     * Get current gap state.
     *
     * @return Current GapState
     */
    public GapState getGapState() {
        return gapState;
    }

    /**
     * Get timestamp when current gap was detected.
     *
     * @return Gap start time in milliseconds, or 0 if no gap
     */
    public long getGapStartTimeMs() {
        return gapStartTimeMs;
    }

    /**
     * Get duration of current gap.
     *
     * @param currentTimeMs Current time in milliseconds
     * @return Gap duration in milliseconds, or 0 if no gap
     */
    public synchronized long getGapDurationMs(long currentTimeMs) {
        if (gapState == GapState.NONE) {
            return 0L;
        }
        return currentTimeMs - gapStartTimeMs;
    }

    /**
     * Get total number of gap cycles detected.
     *
     * A gap cycle begins when queue overflows for the first time (transition to DETECTED)
     * and ends when resetGap() is called. Multiple overflows within a single gap cycle
     * count as one gap.
     *
     * @return Total gap cycles count
     * @see #getTotalDropped() to monitor individual overflow events
     */
    public long getTotalGaps() {
        return totalGaps.get();
    }

    @Override
    public String toString() {
        return String.format("EventReprocessor{queue=%d, dropped=%d, reprocessed=%d, gaps=%d, gapState=%s, healthy=%s}",
                           pendingQueue.size(), totalDropped.get(), totalReprocessed.get(),
                           totalGaps.get(), gapState, isHealthy());
    }
}
