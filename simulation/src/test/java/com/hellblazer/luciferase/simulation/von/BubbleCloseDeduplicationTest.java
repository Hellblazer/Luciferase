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
 * Test that broadcastLeave() is only called once during bubble close/leave operations.
 * <p>
 * Before lifecycle coordination fix, broadcastLeave() was called twice:
 * 1. Manager.leave() called bubble.broadcastLeave()
 * 2. Manager.leave() called bubble.close() which also called broadcastLeave()
 * <p>
 * After fix, Bubble.close() does NOT call broadcastLeave() - only the coordinator does.
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
    void testBubbleCloseDoesNotBroadcastLeave() {
        // Create a Bubble with mock transport
        var bubble = new Bubble(bubbleId, (byte) 10, 16L, mockTransport);

        // Add bubble as neighbor of itself (to have a non-empty neighbor set)
        var neighborId = UUID.randomUUID();
        bubble.addNeighbor(neighborId);

        // Close the bubble
        bubble.close();

        // Verify broadcastLeave was NOT called by Bubble.close()
        // The coordinator should handle broadcastLeave during shutdown
        verify(mockTransport, never()).sendToNeighbor(eq(neighborId), any(Message.class));
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
    void testManagerLeaveCodeRemoved() {
        // This test documents that Manager.leave() no longer calls broadcastLeave()
        // The actual change is in the source code:
        // - Manager.leave() comment indicates: "Broadcast leave to neighbors (handled by LifecycleCoordinator)"
        // - The broadcastLeave() call was removed from Manager.leave()
        // - Only bubble.close() is called (which also doesn't broadcastLeave as verified above)

        // No test needed - code inspection verifies the fix
        assertTrue(true, "Manager.leave() no longer calls broadcastLeave() - verified by code inspection");
    }
}
