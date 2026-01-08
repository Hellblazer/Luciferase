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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test MessageOrderValidator for detecting dropped, reordered, and duplicate messages.
 * <p>
 * Test Coverage:
 * - Valid in-order messages
 * - Dropped message detection (gaps in sequence)
 * - Reordered messages (sequence goes backwards)
 * - Duplicate sequences
 * - Per-sender isolation
 * - Late message detection
 *
 * @author hal.hildebrand
 */
class MessageOrderValidatorTest {

    private MessageOrderValidator validator;
    private UUID senderId1;
    private UUID senderId2;

    @BeforeEach
    void setUp() {
        validator = new MessageOrderValidator();
        senderId1 = UUID.randomUUID();
        senderId2 = UUID.randomUUID();
    }

    @Test
    void testValidInOrderMessages() {
        var msg1 = createMessage(senderId1, 1);
        var msg2 = createMessage(senderId1, 2);
        var msg3 = createMessage(senderId1, 3);

        var result1 = validator.validateMessage(msg1);
        assertTrue(result1.isValid(), "First message should be valid");
        assertFalse(result1.isDropped(), "First message should not be dropped");
        assertFalse(result1.isReordered(), "First message should not be reordered");
        assertEquals(0, result1.messageGap(), "First message should have no gap");

        var result2 = validator.validateMessage(msg2);
        assertTrue(result2.isValid(), "Second message should be valid");
        assertFalse(result2.isDropped(), "Second message should not be dropped");
        assertFalse(result2.isReordered(), "Second message should not be reordered");
        assertEquals(0, result2.messageGap(), "Second message should have no gap");

        var result3 = validator.validateMessage(msg3);
        assertTrue(result3.isValid(), "Third message should be valid");
        assertFalse(result3.isDropped(), "Third message should not be dropped");
        assertFalse(result3.isReordered(), "Third message should not be reordered");
        assertEquals(0, result3.messageGap(), "Third message should have no gap");
    }

    @Test
    void testDroppedMessageDetection() {
        var msg1 = createMessage(senderId1, 1);
        var msg3 = createMessage(senderId1, 3); // Skipped sequence 2

        validator.validateMessage(msg1);
        var result = validator.validateMessage(msg3);

        assertFalse(result.isValid(), "Message should be invalid due to gap");
        assertTrue(result.isDropped(), "Message should be marked as dropped");
        assertFalse(result.isReordered(), "Message should not be reordered");
        assertEquals(1, result.messageGap(), "Gap should be 1 message");
        assertEquals(1, result.lastSeenSequence(), "Last seen should be sequence 1");
    }

    @Test
    void testLargeGapDetection() {
        var msg1 = createMessage(senderId1, 1);
        var msg10 = createMessage(senderId1, 10); // Skipped sequences 2-9

        validator.validateMessage(msg1);
        var result = validator.validateMessage(msg10);

        assertFalse(result.isValid(), "Message should be invalid due to large gap");
        assertTrue(result.isDropped(), "Message should be marked as dropped");
        assertEquals(8, result.messageGap(), "Gap should be 8 messages");
        assertEquals(1, result.lastSeenSequence(), "Last seen should be sequence 1");
    }

    @Test
    void testReorderedMessageDetection() {
        var msg1 = createMessage(senderId1, 1);
        var msg3 = createMessage(senderId1, 3);
        var msg2 = createMessage(senderId1, 2); // Out of order

        validator.validateMessage(msg1);
        validator.validateMessage(msg3);
        var result = validator.validateMessage(msg2);

        assertFalse(result.isValid(), "Message should be invalid due to reordering");
        assertFalse(result.isDropped(), "Message should not be marked as dropped");
        assertTrue(result.isReordered(), "Message should be marked as reordered");
        assertEquals(3, result.lastSeenSequence(), "Last seen should be sequence 3");
    }

    @Test
    void testDuplicateSequenceDetection() {
        var msg1 = createMessage(senderId1, 1);
        var msg1Duplicate = createMessage(senderId1, 1); // Same sequence

        validator.validateMessage(msg1);
        var result = validator.validateMessage(msg1Duplicate);

        assertFalse(result.isValid(), "Duplicate message should be invalid");
        assertFalse(result.isDropped(), "Duplicate should not be marked as dropped");
        assertTrue(result.isReordered(), "Duplicate should be marked as reordered");
        assertEquals(1, result.lastSeenSequence(), "Last seen should still be sequence 1");
    }

    @Test
    void testPerSenderIsolation() {
        // Sender 1 sends messages 1, 2, 3
        var msg1_sender1 = createMessage(senderId1, 1);
        var msg2_sender1 = createMessage(senderId1, 2);

        // Sender 2 sends messages 1, 2, 3
        var msg1_sender2 = createMessage(senderId2, 1);
        var msg2_sender2 = createMessage(senderId2, 2);

        // Interleave messages from both senders
        var result1 = validator.validateMessage(msg1_sender1);
        assertTrue(result1.isValid(), "Sender 1 message 1 should be valid");

        var result2 = validator.validateMessage(msg1_sender2);
        assertTrue(result2.isValid(), "Sender 2 message 1 should be valid");

        var result3 = validator.validateMessage(msg2_sender1);
        assertTrue(result3.isValid(), "Sender 1 message 2 should be valid");

        var result4 = validator.validateMessage(msg2_sender2);
        assertTrue(result4.isValid(), "Sender 2 message 2 should be valid");
    }

    @Test
    void testResetSender() {
        var msg1 = createMessage(senderId1, 1);
        var msg2 = createMessage(senderId1, 2);

        validator.validateMessage(msg1);
        validator.validateMessage(msg2);

        // Reset sender tracking
        validator.reset(senderId1);

        // Next message should be treated as first message
        var msg3 = createMessage(senderId1, 3);
        var result = validator.validateMessage(msg3);

        assertTrue(result.isValid(), "Message should be valid after reset");
        assertEquals(0, result.lastSeenSequence(), "Last seen should be 0 after reset");
    }

    @Test
    void testLateMessageDetection() {
        var timestamp1 = System.currentTimeMillis();
        var timestamp2 = timestamp1 + 1000; // 1 second later
        var oldTimestamp = timestamp1 - 5000; // 5 seconds in the past

        var msg1 = createMessageWithTimestamp(senderId1, 1, timestamp1);
        var msg2 = createMessageWithTimestamp(senderId1, 2, timestamp2);
        var lateMsg = createMessageWithTimestamp(senderId1, 3, oldTimestamp);

        validator.validateMessage(msg1);
        validator.validateMessage(msg2);

        assertTrue(validator.isMessageLate(lateMsg), "Message with old timestamp should be late");
    }

    @Test
    void testDetectDroppedSequences() {
        var msg1 = createMessage(senderId1, 1);
        var msg5 = createMessage(senderId1, 5);

        validator.validateMessage(msg1);
        validator.validateMessage(msg5);

        var droppedSequences = validator.detectDroppedSequences(senderId1);

        assertEquals(3, droppedSequences.size(), "Should detect 3 dropped sequences");
        assertTrue(droppedSequences.contains(2L), "Sequence 2 should be dropped");
        assertTrue(droppedSequences.contains(3L), "Sequence 3 should be dropped");
        assertTrue(droppedSequences.contains(4L), "Sequence 4 should be dropped");
    }

    @Test
    void testNoDroppedSequences() {
        var msg1 = createMessage(senderId1, 1);
        var msg2 = createMessage(senderId1, 2);
        var msg3 = createMessage(senderId1, 3);

        validator.validateMessage(msg1);
        validator.validateMessage(msg2);
        validator.validateMessage(msg3);

        var droppedSequences = validator.detectDroppedSequences(senderId1);

        assertTrue(droppedSequences.isEmpty(), "Should not detect any dropped sequences");
    }

    @Test
    void testValidationResultReason() {
        var msg1 = createMessage(senderId1, 1);
        var msg3 = createMessage(senderId1, 3);

        validator.validateMessage(msg1);
        var result = validator.validateMessage(msg3);

        assertNotNull(result.reason(), "Result should have a reason");
        assertTrue(result.reason().toLowerCase().contains("gap") || result.reason().toLowerCase().contains("message"),
                "Reason should mention gap or message, got: " + result.reason());
    }

    @Test
    void testFirstMessageForSender() {
        var msg1 = createMessage(senderId1, 1);
        var result = validator.validateMessage(msg1);

        assertTrue(result.isValid(), "First message should always be valid");
        assertEquals(0, result.lastSeenSequence(), "Last seen should be 0 for first message");
        assertEquals(0, result.messageGap(), "First message should have no gap");
    }

    // Helper methods

    private TopologyUpdateMessage createMessage(UUID senderId, long sequenceNumber) {
        return new TopologyUpdateMessage(
            senderId,
            Map.of(),
            sequenceNumber,
            System.currentTimeMillis()
        );
    }

    private TopologyUpdateMessage createMessageWithTimestamp(UUID senderId, long sequenceNumber, long timestamp) {
        return new TopologyUpdateMessage(
            senderId,
            Map.of(),
            sequenceNumber,
            timestamp
        );
    }
}
