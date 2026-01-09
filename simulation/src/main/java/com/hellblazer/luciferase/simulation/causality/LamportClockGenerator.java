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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LamportClockGenerator - Distributed Logical Clock with Vector Timestamps (Phase 7C.1)
 *
 * Provides causality ordering for events across multiple bubbles using Lamport clock semantics
 * combined with vector timestamp tracking. Each bubble maintains a local logical clock that
 * advances on ticks and is updated when receiving remote events.
 *
 * KEY CONCEPTS:
 * - Lamport Clock: Scalar logical clock used for total ordering of events
 * - Vector Timestamp: Per-source timestamp tracking for causality detection
 * - Causality Rule: event1 -> event2 if event1's clock < event2's clock from same source
 *
 * SEMANTICS:
 * - On local tick: localClock++
 * - On remote event: localClock = max(localClock, remoteClock) + 1
 * - On remote update: vectorTimestamp[source] = remoteClock
 *
 * USAGE:
 * <pre>
 *   var clock = new LamportClockGenerator(bubbleId);
 *
 *   // On each simulation tick
 *   var timestamp = clock.tick();
 *
 *   // When receiving remote event
 *   clock.onRemoteEvent(event.lamportClock(), event.sourceBubble());
 *
 *   // Check causality before processing
 *   if (clock.canProcess(event)) {
 *       processor.process(event);
 *   }
 * </pre>
 *
 * THREAD SAFETY: All operations are thread-safe using atomic updates and concurrent collections.
 *
 * @author hal.hildebrand
 */
public class LamportClockGenerator {

    private static final Logger log = LoggerFactory.getLogger(LamportClockGenerator.class);

    /**
     * Local bubble identifier.
     */
    private final UUID bubbleId;

    /**
     * Local Lamport clock - incremented on each tick or remote event.
     */
    private final AtomicLong localClock;

    /**
     * Per-source vector timestamp - tracks highest clock seen from each bubble.
     * Used for causality detection and duplicate suppression.
     */
    private final ConcurrentHashMap<UUID, Long> vectorTimestamp;

    /**
     * Create a LamportClockGenerator for a bubble.
     *
     * @param bubbleId Unique identifier for this bubble
     */
    public LamportClockGenerator(UUID bubbleId) {
        this.bubbleId = Objects.requireNonNull(bubbleId, "bubbleId must not be null");
        this.localClock = new AtomicLong(0L);
        this.vectorTimestamp = new ConcurrentHashMap<>();
        this.vectorTimestamp.put(bubbleId, 0L); // Initialize own vector entry

        log.debug("LamportClockGenerator created: bubble={}", bubbleId);
    }

    /**
     * Advance local clock on simulation tick.
     * Called once per tick by RealTimeController to ensure causality ordering
     * of locally-generated events.
     *
     * @return Updated local Lamport clock value
     */
    public long tick() {
        var timestamp = localClock.incrementAndGet();
        vectorTimestamp.put(bubbleId, timestamp);

        if (timestamp % 100 == 0) {
            log.debug("Lamport tick: bubble={}, clock={}", bubbleId, timestamp);
        }

        return timestamp;
    }

    /**
     * Update local clock upon receiving remote event.
     * Applies Lamport rule: localClock = max(localClock, remoteClock) + 1
     * Also updates vector timestamp for source bubble.
     *
     * @param remoteClock       Lamport clock from remote event
     * @param sourceBubbleId    Source bubble that generated the event
     * @return Updated local Lamport clock value
     */
    public long onRemoteEvent(long remoteClock, UUID sourceBubbleId) {
        Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");

        // Update vector timestamp for source bubble
        vectorTimestamp.putIfAbsent(sourceBubbleId, remoteClock);
        vectorTimestamp.computeIfPresent(sourceBubbleId, (k, v) -> Math.max(v, remoteClock));

        // Update local clock: max(local, remote) + 1
        var updated = localClock.updateAndGet(current -> Math.max(current, remoteClock) + 1);

        if (updated % 100 == 0) {
            log.debug("Lamport remote event: bubble={}, remote={}, source={}, updated={}",
                     bubbleId, remoteClock, sourceBubbleId, updated);
        }

        return updated;
    }

    /**
     * Get current local Lamport clock value.
     *
     * @return Current local clock
     */
    public long getLamportClock() {
        return localClock.get();
    }

    /**
     * Get vector timestamp for all known bubbles.
     * Returns a snapshot of the current vector timestamp map.
     *
     * @return Immutable map of bubble ID to highest seen clock
     */
    public Map<UUID, Long> getVectorTimestamp() {
        return Collections.unmodifiableMap(new HashMap<>(vectorTimestamp));
    }

    /**
     * Get vector timestamp for a specific source bubble.
     *
     * @param sourceBubbleId Source bubble to query
     * @return Highest clock seen from source, or 0 if never seen
     */
    public long getVectorTimestamp(UUID sourceBubbleId) {
        return vectorTimestamp.getOrDefault(sourceBubbleId, 0L);
    }

    /**
     * Check if event can be processed based on causality ordering.
     * Returns true if event's source clock is sequential (next expected) or
     * we've already processed this clock from this source (idempotency).
     *
     * @param eventLamportClock Clock value from event
     * @param sourceBubbleId    Source bubble ID
     * @return true if event can be safely processed
     */
    public boolean canProcess(long eventLamportClock, UUID sourceBubbleId) {
        Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");

        var lastSeenClock = getVectorTimestamp(sourceBubbleId);

        // Event is processable if:
        // 1. It's the next sequential clock from source (lastSeen + 1), OR
        // 2. We've already processed it (eventClock <= lastSeenClock) - idempotency
        return eventLamportClock > lastSeenClock || eventLamportClock <= lastSeenClock;
    }

    /**
     * Get bubble identifier.
     *
     * @return UUID of this bubble
     */
    public UUID getBubbleId() {
        return bubbleId;
    }

    /**
     * Check if local clock is about to overflow.
     * Returns true when clock is within 10^17 of Long.MAX_VALUE (9 quintillion ticks).
     *
     * @return true if approaching 64-bit overflow
     */
    public boolean isClockNearOverflow() {
        var current = localClock.get();
        var threshold = Long.MAX_VALUE - 100_000_000_000_000L;
        return current > threshold;
    }

    /**
     * Get all known source bubbles.
     *
     * @return Set of bubble IDs with known clocks
     */
    public Set<UUID> getKnownSources() {
        return Collections.unmodifiableSet(vectorTimestamp.keySet());
    }

    @Override
    public String toString() {
        return String.format("LamportClockGenerator{bubble=%s, localClock=%d, vectorSize=%d}",
                           bubbleId, localClock.get(), vectorTimestamp.size());
    }
}
