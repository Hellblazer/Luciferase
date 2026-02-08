/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test that broadcastLeave() is called correctly during bubble close/leave operations.
 * <p>
 * Phase 5 design:
 * - Bubble.close() DOES call broadcastLeave() for backward compatibility
 * - EnhancedBubbleAdapter.stop() calls the full close() method (includes broadcastLeave)
 * - close() is idempotent - calling twice is safe (second call is no-op)
 * - This ensures both direct close() calls and coordinator-based shutdown work correctly
 *
 * @author hal.hildebrand
 */
class BubbleCloseDeduplicationTest {

    private Transport mockTransport;
    private UUID bubbleId;

    @BeforeEach
    void setUp() {
        mockTransport = mock(Transport.class);
        bubbleId = UUID.randomUUID();
    }

    @Test
    void testBubbleCloseCallsBroadcastLeave() {
        // Create a Bubble with mock transport
        var bubble = new Bubble(bubbleId, (byte) 10, 16L, mockTransport);

        // Add bubble as neighbor of itself (to have a non-empty neighbor set)
        var neighborId = UUID.randomUUID();
        bubble.addNeighbor(neighborId);

        // Close the bubble
        bubble.close();

        // Verify broadcastLeave WAS called by Bubble.close() (for backward compatibility)
        var messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mockTransport, times(1)).sendToNeighbor(eq(neighborId), messageCaptor.capture());

        var sentMessage = messageCaptor.getValue();
        assertTrue(sentMessage instanceof Message.Leave, "Should send LEAVE message, got: " + sentMessage.getClass());
        assertEquals(bubbleId, ((Message.Leave) sentMessage).nodeId(), "LEAVE should be from correct bubble");
    }

    @Test
    void testDirectBroadcastLeaveStillWorks() {
        // Verify that explicitly calling broadcastLeave() still works
        // (for cases where LifecycleCoordinator calls it directly)
        var bubble = new Bubble(bubbleId, (byte) 10, 16L, mockTransport);

        // Add a neighbor
        var neighborId = UUID.randomUUID();
        bubble.addNeighbor(neighborId);

        // Explicitly call broadcastLeave
        bubble.broadcastLeave();

        // Verify LEAVE was sent
        var messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mockTransport, times(1)).sendToNeighbor(eq(neighborId), messageCaptor.capture());

        var sentMessage = messageCaptor.getValue();
        assertTrue(sentMessage instanceof Message.Leave, "Should send LEAVE message, got: " + sentMessage.getClass());
        assertEquals(bubbleId, ((Message.Leave) sentMessage).nodeId(), "LEAVE should be from correct bubble");
    }

    @Test
    void testCloseIsIdempotent() {
        // Verify that calling close() twice is safe (second call is no-op)
        var bubble = new Bubble(bubbleId, (byte) 10, 16L, mockTransport);

        // Add a neighbor
        var neighborId = UUID.randomUUID();
        bubble.addNeighbor(neighborId);

        // Close twice
        bubble.close();
        bubble.close();

        // Verify broadcastLeave was called only ONCE (not twice)
        var messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mockTransport, times(1)).sendToNeighbor(eq(neighborId), messageCaptor.capture());

        var sentMessage = messageCaptor.getValue();
        assertTrue(sentMessage instanceof Message.Leave, "Should send LEAVE message once");
        assertEquals(bubbleId, ((Message.Leave) sentMessage).nodeId(), "LEAVE should be from correct bubble");
    }
}
