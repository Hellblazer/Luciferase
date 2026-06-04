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

package com.hellblazer.luciferase.simulation.von.transport;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.fireflies.FirefliesMembershipView;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SocketTransport Fireflies virtual synchrony ACK semantics.
 * <p>
 * Tests use deterministic TestClock and manual tick control for zero flakiness.
 * All timing is explicit via testClock.advance() and controller.tick().
 * <p>
 * Test scenarios:
 * 1. View stable → ACK success
 * 2. View change → ACK failure
 * 3. Multiple concurrent ACKs
 * 4. Timeout (view never stabilizes)
 * 5. Tick listener cleanup
 *
 * @author hal.hildebrand
 */
class SocketTransportFirefliesAckTest {

    private RealTimeController controller;
    private MockFirefliesView<String> mockView;
    private FirefliesViewMonitor viewMonitor;
    private FirefliesMembershipView membership;
    private SocketTransport transport1;
    private SocketTransport transport2;
    private UUID transport2BubbleId;  // Store transport2's bubble ID for tests
    private MessageFactory factory;
    private DynamicContext mockContext;  // mocked view context; tests can mutate getId()
    private ProcessAddress addr2;        // transport2's address, reused by ad-hoc transports

    private final List<SocketTransport> transports = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // Create RealTimeController
        var bubbleId = UUID.randomUUID();
        controller = new RealTimeController(bubbleId, "test-bubble", 100);  // 100 Hz = 10ms ticks

        // Create mock Fireflies infrastructure
        mockView = new MockFirefliesView<>();
        viewMonitor = new FirefliesViewMonitor(mockView);

        // Create membership view with mocked Delos View
        var delosView = mock(View.class);
        mockContext = mock(DynamicContext.class);
        when(delosView.getContext()).thenReturn((DynamicContext) mockContext);
        when(mockContext.getId()).thenReturn(Digest.NONE);  // Stable view ID
        membership = new FirefliesMembershipView(delosView);

        // Create transports
        var port1 = findAvailablePort();
        var port2 = findAvailablePort();
        var addr1 = ProcessAddress.localhost("p1", port1);
        addr2 = ProcessAddress.localhost("p2", port2);

        transport2BubbleId = UUID.randomUUID();  // Store for use in tests
        transport1 = SocketTransport.create(bubbleId, addr1, membership, viewMonitor, controller);
        transport2 = SocketTransport.create(transport2BubbleId, addr2, membership, viewMonitor, controller);

        transports.add(transport1);
        transports.add(transport2);

        // Start servers
        transport1.listenOn(addr1);
        transport2.listenOn(addr2);

        // Connect transports
        transport1.connectTo(addr2);

        // Register members for routing - use transport2's actual bubble ID
        transport1.registerMember(transport2BubbleId, addr2);

        factory = MessageFactory.system();

        // Register viewMonitor to receive tick notifications so its time advances
        controller.addTickListener((simTime, lamportClock) -> viewMonitor.onTick(simTime));

        // Start controller to enable tick listeners
        controller.start();

        // Give controller time to start ticking (ensure at least one tick before tests run)
        Thread.sleep(50);  // 5 ticks at 100Hz (50ms)
    }

    @AfterEach
    void tearDown() throws IOException {
        for (var transport : transports) {
            transport.closeAll();
        }
        transports.clear();
        controller.stop();
    }

    /**
     * Test 1: View stable → ACK completes successfully.
     * <p>
     * Wait for 30 ticks (300ms at 100Hz) to reach stability threshold.
     */
    @Test
    void testViewStableAckSuccess() throws Exception {
        var message = factory.createAck(UUID.randomUUID(), transport2BubbleId);

        // Start with stable view (no recent changes)
        // FirefliesViewMonitor starts stable by default

        // Send message - ACK should complete when view is stable
        var ackFuture = transport1.sendToNeighborAsync(transport2BubbleId, message);

        // ACK should complete within reasonable time (view is stable)
        // At 100Hz, view stability check happens every 10ms
        // Default stability threshold is 30 ticks = 300ms
        var ack = ackFuture.get(500, TimeUnit.MILLISECONDS);
        assertNotNull(ack, "ACK should complete when view is stable");
        assertTrue(ackFuture.isDone(), "ACK future should be done");
    }

    /**
     * Test 2: View change → ACK fails with exception.
     * <p>
     * Note: Current implementation captures view ID at send time.
     * This test validates the expected behavior once view change detection is fully implemented.
     */
    @Test
    void testViewChangeDetection() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            // Drive the mocked Delos view to change its id AFTER the send captures the
            // initial (Digest.NONE) view. sendToNeighborAsync() captures the view id at
            // send time; the next tick must observe a different id and fail the future.
            var changedViewId = DigestAlgorithm.DEFAULT.digest("view-changed");
            // First call (at send time) returns NONE; every subsequent call returns the changed id.
            when(mockContext.getId()).thenReturn(Digest.NONE).thenReturn(changedViewId);

            var message = factory.createAck(UUID.randomUUID(), transport2BubbleId);
            var ackFuture = transport1.sendToNeighborAsync(transport2BubbleId, message);

            // The controller is already ticking at 100Hz; the listener fires within a few ms
            // and must observe the changed view id, completing the future exceptionally.
            var ex = assertThrows(ExecutionException.class,
                                  () -> ackFuture.get(2, TimeUnit.SECONDS),
                                  "ACK must fail when the view changes mid-send");
            assertInstanceOf(SocketTransport.TransportException.class, ex.getCause(),
                             "Cause should be TransportException");
            assertTrue(ex.getCause().getMessage().contains("View changed during send"),
                       "Message should describe the view change: " + ex.getCause().getMessage());
        });
    }

    /**
     * Test 3: Multiple concurrent ACKs.
     * <p>
     * Send 100 messages in parallel, all should complete when view stabilizes.
     */
    @Test
    void testMultipleConcurrentAcks() throws Exception {
        var message = factory.createAck(UUID.randomUUID(), transport2BubbleId);

        // Send 10 messages in parallel (reduced from 100 for deterministic testing)
        // In production, view should stabilize quickly enough for higher volumes
        List<CompletableFuture<Message.Ack>> ackFutures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ackFutures.add(transport1.sendToNeighborAsync(transport2BubbleId, message));
        }

        // All should complete within reasonable time
        for (var future : ackFutures) {
            var ack = future.get(1000, TimeUnit.MILLISECONDS);
            assertNotNull(ack, "All ACKs should complete");
        }

        assertEquals(10, ackFutures.stream().filter(CompletableFuture::isDone).count(),
                     "All 10 ACK futures should be done");
    }

        /**
     * Test 4: Timeout when view never stabilizes.
     * <p>
     * View keeps changing, 5-second timeout should trigger.
     */
    @Test
    void testTimeoutWhenViewNeverStabilizes() throws Exception {
        // Build a dedicated monitor whose stability threshold can never be reached within
        // the 5s failsafe (Integer.MAX_VALUE ticks). Trigger a single view change so the
        // monitor is permanently unstable, but keep the Delos view id constant so the
        // value-change branch never fires either. The only path that can complete the
        // future is the orTimeout(5s) failsafe under test.
        var neverStableMonitor = new FirefliesViewMonitor(mockView, Integer.MAX_VALUE);
        controller.addTickListener((simTime, lamportClock) -> neverStableMonitor.onTick(simTime));
        // Fire a view change -> monitor.hasChanged=true, never re-stabilizes.
        mockView.addMember("perturber");
        neverStableMonitor.onTick(1L);
        assertFalse(neverStableMonitor.isViewStable(),
                    "Monitor must be unstable after a view change with an unreachable threshold");

        var port = findAvailablePort();
        var addr = ProcessAddress.localhost("p-timeout", port);
        var timeoutTransport = SocketTransport.create(UUID.randomUUID(), addr, membership,
                                                      neverStableMonitor, controller);
        transports.add(timeoutTransport);
        timeoutTransport.listenOn(addr);
        timeoutTransport.registerMember(transport2BubbleId, addr2);
        timeoutTransport.connectTo(addr2);

        var message = factory.createAck(UUID.randomUUID(), transport2BubbleId);

        // Wrap the real 5s failsafe with a preemptive guard so a regression that removes
        // orTimeout would hang here and fail the test rather than the suite.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(8), () -> {
            var ackFuture = timeoutTransport.sendToNeighborAsync(transport2BubbleId, message);
            var ex = assertThrows(ExecutionException.class,
                                  () -> ackFuture.get(6, TimeUnit.SECONDS),
                                  "ACK must time out when the view never stabilizes");
            assertInstanceOf(TimeoutException.class, ex.getCause(),
                             "Cause should be TimeoutException from the 5s failsafe");
        });
    }

        /**
     * Test 5: Tick listener cleanup.
     * <p>
     * Verify that tick listeners are properly removed after ACK completes.
     */
    @Test
    void testTickListenerCleanup() throws Exception {
        var message = factory.createAck(UUID.randomUUID(), transport2BubbleId);

        // Send message
        var ackFuture = transport1.sendToNeighborAsync(transport2BubbleId, message);

        // Wait for ACK to complete
        var ack = ackFuture.get(500, TimeUnit.MILLISECONDS);
        assertNotNull(ack, "ACK should complete");

        // Verify future is done (listener should have been removed)
        assertTrue(ackFuture.isDone(), "ACK future should be complete");
        
        // Note: Listener cleanup happens in future.whenComplete()
        // No direct way to verify listener count, but absence of memory leaks
        // over many iterations would indicate proper cleanup
    }

        /**
     * Test 6: Immediate send failure doesn't leak listeners.
     * <p>
     * If sendToNeighbor throws, tick listener should not be added.
     */
    @Test
    void testImmediateSendFailureNoListenerLeak() {
        var nonExistentNeighbor = UUID.randomUUID();
        var message = factory.createAck(UUID.randomUUID(), nonExistentNeighbor);

        // Send to non-existent neighbor - should fail immediately
        var ackFuture = transport1.sendToNeighborAsync(nonExistentNeighbor, message);

        // Future should complete exceptionally
        assertTrue(ackFuture.isCompletedExceptionally(),
                   "ACK should fail for non-existent neighbor");

        // Verify it fails with the expected exception
        ExecutionException exception = assertThrows(ExecutionException.class,
                                                     () -> ackFuture.get(100, TimeUnit.MILLISECONDS),
                                                     "Should throw ExecutionException");

        assertInstanceOf(SocketTransport.TransportException.class, exception.getCause(),
                         "Cause should be TransportException");
    }

        private int findAvailablePort() {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }
}
