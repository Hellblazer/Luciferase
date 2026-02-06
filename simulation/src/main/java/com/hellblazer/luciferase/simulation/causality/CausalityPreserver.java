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
 * CausalityPreserver - Event Ordering Enforcer (Phase 7C.3)
 *
 * Ensures events are processed in causally-consistent order by tracking
 * the highest Lamport clock processed from each source bubble. Prevents
 * state corruption from out-of-order event processing.
 *
 * KEY CONCEPTS:
 * - Partial Order: Events from same source must be processed in order
 * - Idempotency: Events already processed can be safely reprocessed
 * - Source Tracking: Per-bubble map of highest processed clock
 * - Causality Validation: canProcess() checks if event maintains order
 *
 * INVARIANT: For any source S, if we've processed clock C from S,
 * we cannot process a clock C' where C' < C (except idempotent replay).
 *
 * USAGE:
 * <pre>
 *   var preserver = new CausalityPreserver();
 *
 *   // Before processing event
 *   if (preserver.canProcess(event, sourceBubbleId)) {
 *       processor.process(event);
 *       preserver.markProcessed(event, sourceBubbleId);
 *   } else {
 *       reprocessor.queueEvent(event);  // Requeue for later
 *   }
 *
 *   // Get processing history
 *   var history = preserver.getProcessedClocks();
 * </pre>
 *
 * THREAD SAFETY: All operations are thread-safe using concurrent collections
 * and atomic operations.
 *
 * @author hal.hildebrand
 */
public class CausalityPreserver {

    private static final Logger log = LoggerFactory.getLogger(CausalityPreserver.class);

    /**
     * Highest Lamport clock processed from each source bubble.
     * Maps source bubble ID to highest clock seen.
     */
    private final ConcurrentHashMap<UUID, Long> processedClocks;

    /**
     * Total events processed (metric).
     */
    private final AtomicLong totalProcessed;

    /**
     * Total events rejected as out-of-order (metric).
     */
    private final AtomicLong totalRejected;

    /**
     * Total events reprocessed (idempotent replay) (metric).
     */
    private final AtomicLong totalIdempotent;

    /**
     * Create a CausalityPreserver with no prior history.
     */
    public CausalityPreserver() {
        this.processedClocks = new ConcurrentHashMap<>();
        this.totalProcessed = new AtomicLong(0L);
        this.totalRejected = new AtomicLong(0L);
        this.totalIdempotent = new AtomicLong(0L);

        log.debug("CausalityPreserver created");
    }

    /**
     * Check if event can be processed without violating causality.
     *
     * Returns true if:
     * 1. Event is from a new source (never seen before), OR
     * 2. Event's clock >= highest processed clock from this source
     *
     * Returns false if:
     * - Event's clock < highest processed clock from source (would violate order)
     *
     * NOTE: This allows idempotent reprocessing (clock == highest) without
     * causing issues, and allows any clock >= highest (monotonic increase).
     *
     * @param event        Event to check
     * @param sourceBubble Source bubble that sent the event
     * @return true if event can be safely processed (doesn't violate causality)
     */
    public boolean canProcess(EntityUpdateEvent event, UUID sourceBubble) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");

        var eventClock = event.lamportClock();
        var lastProcessedClock = processedClocks.getOrDefault(sourceBubble, -1L);

        // Can process if:
        // 1. First event from this source (lastProcessedClock == -1), OR
        // 2. Event clock >= last processed (monotonic or idempotent)
        boolean canProcess = eventClock >= lastProcessedClock;

        if (!canProcess) {
            totalRejected.incrementAndGet();
            log.warn("Event rejected (causality violation): source={}, eventClock={}, lastProcessed={}",
                    sourceBubble, eventClock, lastProcessedClock);
        }

        return canProcess;
    }

    /**
     * Mark event as processed, updating the highest clock from its source.
     * Should only be called AFTER successful event processing.
     *
     * @param event        Event that was processed
     * @param sourceBubble Source bubble that sent the event
     */
    public void markProcessed(EntityUpdateEvent event, UUID sourceBubble) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");

        var eventClock = event.lamportClock();
        var lastProcessedClock = processedClocks.getOrDefault(sourceBubble, -1L);

        // Update to this clock if it's higher
        processedClocks.compute(sourceBubble, (k, v) -> {
            long current = (v == null) ? -1L : v;
            long updated = Math.max(current, eventClock);

            // Track idempotent (replay) vs new processing
            if (updated == current && current >= 0) {
                // Same clock - idempotent replay
                totalIdempotent.incrementAndGet();
            } else {
                // New clock - first-time processing
                totalProcessed.incrementAndGet();
            }

            if (updated % 100 == 0) {
                log.debug("Processed event: source={}, clock={}, total={}",
                         sourceBubble, eventClock, totalProcessed.get());
            }

            return updated;
        });
    }

    /**
     * Get the highest Lamport clock processed from a source bubble.
     *
     * @param sourceBubble Source bubble to query
     * @return Highest clock processed, or -1 if no events from source yet
     */
    public long getProcessedClock(UUID sourceBubble) {
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");
        return processedClocks.getOrDefault(sourceBubble, -1L);
    }

    /**
     * Get all processed clocks from all sources.
     * Returns a snapshot of the current state.
     *
     * @return Immutable map of source bubble ID to highest processed clock
     */
    public Map<UUID, Long> getProcessedClocks() {
        return Collections.unmodifiableMap(new HashMap<>(processedClocks));
    }

    /**
     * Get all known source bubbles that have sent events.
     *
     * @return Set of source bubble IDs with known processing history
     */
    public Set<UUID> getKnownSources() {
        return Collections.unmodifiableSet(processedClocks.keySet());
    }

    /**
     * Get total events processed (first-time processing).
     *
     * @return Total processed count
     */
    public long getTotalProcessed() {
        return totalProcessed.get();
    }

    /**
     * Get total events rejected due to causality violations.
     *
     * @return Total rejected count
     */
    public long getTotalRejected() {
        return totalRejected.get();
    }

    /**
     * Get total events reprocessed (idempotent replay).
     *
     * @return Total idempotent count
     */
    public long getTotalIdempotent() {
        return totalIdempotent.get();
    }

    /**
     * Check if causal history is consistent (no violations detected).
     * A violation would manifest as a rejection followed by inability
     * to recover (stalled processing).
     *
     * @return true if no rejections have occurred (causal chain intact)
     */
    public boolean isConsistent() {
        return totalRejected.get() == 0;
    }

    /**
     * Reset all tracking state (for testing or recovery).
     * WARNING: Only use if you're certain previous events can be discarded.
     */
    public void reset() {
        processedClocks.clear();
        totalProcessed.set(0L);
        totalRejected.set(0L);
        totalIdempotent.set(0L);
        log.debug("CausalityPreserver reset");
    }

    /**
     * Clear history for a specific source (advanced).
     *
     * @param sourceBubble Source bubble to clear
     */
    public void clearSource(UUID sourceBubble) {
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");
        processedClocks.remove(sourceBubble);
        log.debug("Cleared history for source: {}", sourceBubble);
    }

    /**
     * Initialize with prior processing history (for recovery).
     * Used when restoring from checkpoint or merging states.
     *
     * @param history Map of source bubble ID to highest processed clock
     */
    public void initializeWithHistory(Map<UUID, Long> history) {
        Objects.requireNonNull(history, "history must not be null");
        processedClocks.putAll(history);
        log.debug("Initialized with {} sources", history.size());
    }

    /**
     * Check if we're expecting more events from a source.
     * True if source has sent at least one event.
     *
     * @param sourceBubble Source bubble to check
     * @return true if source is known
     */
    public boolean hasSeenSource(UUID sourceBubble) {
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");
        return processedClocks.containsKey(sourceBubble);
    }

    /**
     * Get number of sources in tracking history.
     *
     * @return Count of distinct source bubbles
     */
    public int getSourceCount() {
        return processedClocks.size();
    }

    @Override
    public String toString() {
        return String.format("CausalityPreserver{sources=%d, processed=%d, rejected=%d, idempotent=%d, consistent=%s}",
                           processedClocks.size(), totalProcessed.get(), totalRejected.get(),
                           totalIdempotent.get(), isConsistent());
    }
}
