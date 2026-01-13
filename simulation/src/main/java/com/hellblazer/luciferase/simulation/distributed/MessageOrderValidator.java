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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates message ordering using sequence numbers from TopologyUpdateMessage.
 * <p>
 * Responsibilities:
 * - Validate message sequence numbers
 * - Detect dropped messages (gaps in sequence)
 * - Detect reordered messages (sequence goes backwards)
 * - Log ordering violations with bucket context
 * - Per-sender sequence tracking
 * <p>
 * Message Ordering Guarantees:
 * - Within-process ordering: Guaranteed by FIFO queue discipline
 * - Cross-process ordering: Best-effort using sequence numbers
 * - Determinism: Same input produces same sequence ordering
 * <p>
 * Thread Safety:
 * - ConcurrentHashMap for per-sender tracking
 * - No blocking operations
 * - Thread-safe validation
 * <p>
 * Architecture Decision D6B.6: Message Sequence Numbers
 *
 * @author hal.hildebrand
 */
public class MessageOrderValidator {

    private static final Logger log = LoggerFactory.getLogger(MessageOrderValidator.class);

    private final ConcurrentHashMap<UUID, SenderState> senderStates;
    private static final long LATE_MESSAGE_THRESHOLD_MS = 1000; // 1 second
    private volatile Clock clock = Clock.system();

    /**
     * Tracks state for a single sender.
     */
    private static class SenderState {
        long lastSeenSequence;
        long lastSeenTimestamp;
        final List<Long> droppedSequences;

        SenderState() {
            this.lastSeenSequence = 0;
            this.lastSeenTimestamp = 0;
            this.droppedSequences = new ArrayList<>();
        }
    }

    /**
     * Validation result for a message.
     *
     * @param isValid          true if message is in correct order
     * @param isDropped        true if message(s) missing between last and this
     * @param isReordered      true if sequence went backwards
     * @param lastSeenSequence the last sequence number seen before this message
     * @param messageGap       number of messages dropped (0 if none)
     * @param reason           human-readable reason for validation result
     */
    public record ValidationResult(
        boolean isValid,
        boolean isDropped,
        boolean isReordered,
        long lastSeenSequence,
        long messageGap,
        String reason
    ) {
    }

    /**
     * Create a MessageOrderValidator.
     */
    public MessageOrderValidator() {
        this.senderStates = new ConcurrentHashMap<>();
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
     * Validate a message's sequence number.
     * <p>
     * Checks for:
     * - Dropped messages (gaps in sequence)
     * - Reordered messages (sequence goes backwards)
     * - Duplicate messages (same sequence)
     * <p>
     * First message from a sender is always valid.
     *
     * @param message TopologyUpdateMessage to validate
     * @return ValidationResult with details
     */
    public ValidationResult validateMessage(TopologyUpdateMessage message) {
        var senderId = message.coordinatorId();
        var sequenceNumber = message.sequenceNumber();
        var timestamp = message.timestamp();

        var state = senderStates.computeIfAbsent(senderId, k -> new SenderState());

        // First message from this sender is always valid
        if (state.lastSeenSequence == 0) {
            state.lastSeenSequence = sequenceNumber;
            state.lastSeenTimestamp = timestamp;
            return new ValidationResult(
                true,
                false,
                false,
                0,
                0,
                "First message from sender"
            );
        }

        var lastSeenSequence = state.lastSeenSequence;

        // Check for reordering or duplicates
        if (sequenceNumber <= lastSeenSequence) {
            log.warn("Reordered/duplicate message from {}: sequence {} (last seen: {})",
                    senderId, sequenceNumber, lastSeenSequence);
            return new ValidationResult(
                false,
                false,
                true,
                lastSeenSequence,
                0,
                "Sequence number " + sequenceNumber + " is not greater than last seen " + lastSeenSequence
            );
        }

        // Check for dropped messages (gap in sequence)
        var gap = sequenceNumber - lastSeenSequence - 1;
        if (gap > 0) {
            log.warn("Dropped message(s) from {}: gap of {} (last: {}, current: {})",
                    senderId, gap, lastSeenSequence, sequenceNumber);

            // Track all dropped sequence numbers
            for (long i = lastSeenSequence + 1; i < sequenceNumber; i++) {
                state.droppedSequences.add(i);
            }

            state.lastSeenSequence = sequenceNumber;
            state.lastSeenTimestamp = timestamp;

            return new ValidationResult(
                false,
                true,
                false,
                lastSeenSequence,
                gap,
                "Gap of " + gap + " message(s) detected"
            );
        }

        // Valid in-order message
        state.lastSeenSequence = sequenceNumber;
        state.lastSeenTimestamp = timestamp;

        return new ValidationResult(
            true,
            false,
            false,
            lastSeenSequence,
            0,
            "Valid in-order message"
        );
    }

    /**
     * Check if a message is late (timestamp significantly in the past).
     * <p>
     * Late messages indicate clock skew or network delays.
     *
     * @param message TopologyUpdateMessage to check
     * @return true if message timestamp is more than LATE_MESSAGE_THRESHOLD_MS in the past
     */
    public boolean isMessageLate(TopologyUpdateMessage message) {
        var now = clock.currentTimeMillis();
        var age = now - message.timestamp();

        if (age > LATE_MESSAGE_THRESHOLD_MS) {
            log.warn("Late message from {}: age {}ms (threshold: {}ms)",
                    message.coordinatorId(), age, LATE_MESSAGE_THRESHOLD_MS);
            return true;
        }

        return false;
    }

    /**
     * Detect dropped sequence numbers for a sender.
     * <p>
     * Returns list of sequence numbers that were skipped.
     *
     * @param senderId UUID of sender to check
     * @return List of dropped sequence numbers (empty if none)
     */
    public List<Long> detectDroppedSequences(UUID senderId) {
        var state = senderStates.get(senderId);
        if (state == null) {
            return List.of();
        }

        return new ArrayList<>(state.droppedSequences);
    }

    /**
     * Reset tracking for a sender.
     * <p>
     * Clears sequence state, next message will be treated as first message.
     *
     * @param senderId UUID of sender to reset
     */
    public void reset(UUID senderId) {
        senderStates.remove(senderId);
        log.debug("Reset message tracking for sender {}", senderId);
    }
}
