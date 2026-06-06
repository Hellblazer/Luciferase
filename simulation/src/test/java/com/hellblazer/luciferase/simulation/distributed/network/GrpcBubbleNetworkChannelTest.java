/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.events.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GrpcBubbleNetworkChannelTest - TDD tests for gRPC network channel implementation.
 * Tests the production gRPC implementation against the BubbleNetworkChannel interface contract.
 */
public class GrpcBubbleNetworkChannelTest {

    private GrpcBubbleNetworkChannel sourceChannel;
    private GrpcBubbleNetworkChannel targetChannel;
    private UUID sourceNodeId;
    private UUID targetNodeId;

    @BeforeEach
    void setUp() {
        sourceNodeId = UUID.randomUUID();
        targetNodeId = UUID.randomUUID();

        // Explicit plaintext opt-in for tests (Luciferase-7wzml.200).
        // Production use requires TLS — see Luciferase-l9dny for mTLS roadmap.
        sourceChannel = new GrpcBubbleNetworkChannel(true);
        targetChannel = new GrpcBubbleNetworkChannel(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sourceChannel != null) {
            sourceChannel.close();
        }
        if (targetChannel != null) {
            targetChannel.close();
        }
    }

    // ==================== Transport-security opt-in gate (Luciferase-7wzml.200 / I1) ====================

    /**
     * Server-side plaintext gate (I1): initialize() without opt-in must throw immediately.
     * The default constructor leaves {@code allowPlaintext=false}; the SERVER must not start
     * in plaintext mode without an explicit opt-in, mirroring the client-side gate.
     */
    @Test
    void testServerStartWithoutPlaintextOptInThrows() {
        var noTlsChannel = new GrpcBubbleNetworkChannel(); // no opt-in
        try {
            assertThrows(IllegalStateException.class,
                         () -> noTlsChannel.initialize(UUID.randomUUID(), "localhost:0"),
                         "initialize() without plaintext opt-in must throw ISE on the server path");
        } finally {
            try { noTlsChannel.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Server-side plaintext gate (I1): initialize() with opt-in must succeed and
     * assign a local address.
     */
    @Test
    void testServerStartWithPlaintextOptInSucceeds() throws Exception {
        // Via constructor
        var channelViaConstructor = new GrpcBubbleNetworkChannel(true);
        try {
            assertDoesNotThrow(
                () -> channelViaConstructor.initialize(UUID.randomUUID(), "localhost:0"),
                "initialize() with boolean constructor opt-in must not throw");
            assertNotNull(channelViaConstructor.getLocalAddress(),
                          "channel constructed with allowPlaintext=true must report a local address");
        } finally {
            channelViaConstructor.close();
        }

        // Via setter
        var channelViaSetter = new GrpcBubbleNetworkChannel();
        channelViaSetter.setAllowPlaintext(true);
        try {
            assertDoesNotThrow(
                () -> channelViaSetter.initialize(UUID.randomUUID(), "localhost:0"),
                "initialize() with setAllowPlaintext(true) must not throw");
            assertNotNull(channelViaSetter.getLocalAddress(),
                          "channel with setAllowPlaintext(true) must report a local address");
        } finally {
            channelViaSetter.close();
        }
    }

    /**
     * Client-side plaintext gate: attempting to SEND without opt-in returns false (caught
     * internally in sendEntityDeparture). The server-gate test above is the primary I1
     * validation; this test documents the client-gate behavior is unchanged.
     */
    @Test
    void testChannelWithoutPlaintextOptInThrowsOnSend() {
        // With the I1 fix, initialize() itself now throws, so we opt-in to start the server
        // and leave allowPlaintext=false only on the SENDING channel to isolate the client gate.
        var serverChannel = new GrpcBubbleNetworkChannel(true);
        var noTlsChannel = new GrpcBubbleNetworkChannel(); // no opt-in — client side only
        try {
            var nodeId = UUID.randomUUID();
            var remoteId = UUID.randomUUID();
            serverChannel.initialize(nodeId, "localhost:0");

            // noTlsChannel never calls initialize() — we only test the send-path gate
            noTlsChannel.registerNode(remoteId, "localhost:1"); // some remote addr

            var event = new EntityDepartureEvent(
                UUID.randomUUID(), nodeId, remoteId,
                EntityMigrationState.MIGRATING_OUT, System.nanoTime());

            // The outer try/catch in sendEntityDeparture catches the ISE from getOrCreateChannel
            // and returns false — fail-loud is preserved, but not via exception to caller.
            boolean result = noTlsChannel.sendEntityDeparture(remoteId, event);
            assertFalse(result, "send without plaintext opt-in must fail (return false)");
        } finally {
            try { serverChannel.close(); } catch (Exception ignored) {}
            try { noTlsChannel.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Verify that setting allowPlaintext=true before initialize allows channel creation.
     * Both the boolean constructor and setAllowPlaintext(true) must work.
     */
    @Test
    void testChannelWithPlaintextOptInSucceeds() throws Exception {
        // Via constructor
        var channelViaConstructor = new GrpcBubbleNetworkChannel(true);
        try {
            channelViaConstructor.initialize(UUID.randomUUID(), "localhost:0");
            assertNotNull(channelViaConstructor.getLocalAddress(),
                          "channel constructed with allowPlaintext=true must initialize");
        } finally {
            channelViaConstructor.close();
        }

        // Via setter
        var channelViaSetter = new GrpcBubbleNetworkChannel();
        channelViaSetter.setAllowPlaintext(true);
        try {
            channelViaSetter.initialize(UUID.randomUUID(), "localhost:0");
            assertNotNull(channelViaSetter.getLocalAddress(),
                          "channel with setAllowPlaintext(true) must initialize");
        } finally {
            channelViaSetter.close();
        }
    }

    // ==================== Functional tests ====================

    /**
     * Test 1: Basic Initialization
     * Verify channel can initialize on dynamic port and accept connections.
     */
    @Test
    void testBasicInitialization() {
        // Initialize source channel on dynamic port (0 = OS assigns)
        sourceChannel.initialize(sourceNodeId, "localhost:0");

        // Get the actual port assigned
        var sourceAddress = sourceChannel.getLocalAddress();
        assertNotNull(sourceAddress, "Local address should be set after initialization");
        assertTrue(sourceAddress.contains(":"), "Address should contain port");

        // Initialize target channel
        targetChannel.initialize(targetNodeId, "localhost:0");
        var targetAddress = targetChannel.getLocalAddress();
        assertNotNull(targetAddress, "Target address should be set");

        // Register nodes with each other
        sourceChannel.registerNode(targetNodeId, targetAddress);
        targetChannel.registerNode(sourceNodeId, sourceAddress);

        // Verify reachability
        assertTrue(sourceChannel.isNodeReachable(targetNodeId), "Target should be reachable from source");
        assertTrue(targetChannel.isNodeReachable(sourceNodeId), "Source should be reachable from target");
    }

    /**
     * Test 2: EntityDepartureEvent Delivery
     * Verify EntityDepartureEvent can be sent and received.
     */
    @Test
    void testEntityDepartureEventDelivery() throws Exception {
        // Initialize channels
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set up listener on target
        var receivedLatch = new CountDownLatch(1);
        var receivedEvent = new AtomicReference<EntityDepartureEvent>();
        var receivedSourceId = new AtomicReference<UUID>();

        targetChannel.setEntityDepartureListener((sourceId, event) -> {
            receivedSourceId.set(sourceId);
            receivedEvent.set(event);
            receivedLatch.countDown();
        });

        // Create and send event
        var entityId = UUID.randomUUID();
        var event = new EntityDepartureEvent(
            entityId,
            sourceNodeId,
            targetNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        assertTrue(sourceChannel.sendEntityDeparture(targetNodeId, event),
                   "Send should return true");

        // Wait for delivery
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive event within 5 seconds");

        // Verify received event
        assertEquals(sourceNodeId, receivedSourceId.get(), "Source ID should match");
        assertNotNull(receivedEvent.get(), "Event should be received");
        assertEquals(entityId, receivedEvent.get().getEntityId(), "Entity ID should match");
        assertEquals(EntityMigrationState.MIGRATING_OUT, receivedEvent.get().getStateSnapshot());
    }

    /**
     * Test 3: ViewSynchronyAck Delivery
     * Verify ViewSynchronyAck can be sent and received.
     */
    @Test
    void testViewSynchronyAckDelivery() throws Exception {
        // Initialize channels
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set up listener on source
        var receivedLatch = new CountDownLatch(1);
        var receivedAck = new AtomicReference<ViewSynchronyAck>();
        var receivedSourceId = new AtomicReference<UUID>();

        sourceChannel.setViewSynchronyAckListener((sourceId, ack) -> {
            receivedSourceId.set(sourceId);
            receivedAck.set(ack);
            receivedLatch.countDown();
        });

        // Create and send ack
        var entityId = UUID.randomUUID();
        var ack = new ViewSynchronyAck(
            entityId,
            sourceNodeId,
            targetNodeId,
            3, // stability ticks
            System.nanoTime()
        );

        assertTrue(targetChannel.sendViewSynchronyAck(sourceNodeId, ack),
                   "Send should return true");

        // Wait for delivery
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive ack within 5 seconds");

        // Verify received ack. Luciferase-wikxz: the receiver must recover the ack's
        // SourceBubbleId (the originating identity the sender serialised), NOT TargetBubbleId.
        // The ack was built with sourceBubbleId=sourceNodeId, so the recovered source id
        // must equal sourceNodeId. (Pre-fix this asserted targetNodeId — encoding the bug.)
        assertEquals(sourceNodeId, receivedSourceId.get(),
                     "Recovered source id must equal the ack's SourceBubbleId");
        assertNotNull(receivedAck.get(), "Ack should be received");
        assertEquals(entityId, receivedAck.get().getEntityId(), "Entity ID should match");
        assertEquals(3, receivedAck.get().getStabilityTicksVerified());
    }

    /**
     * Test 4: EntityRollbackEvent Delivery
     * Verify EntityRollbackEvent can be sent and received.
     */
    @Test
    void testEntityRollbackEventDelivery() throws Exception {
        // Initialize channels
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set up listener on target
        var receivedLatch = new CountDownLatch(1);
        var receivedRollback = new AtomicReference<EntityRollbackEvent>();
        var receivedSourceId = new AtomicReference<UUID>();

        targetChannel.setEntityRollbackListener((sourceId, rollback) -> {
            receivedSourceId.set(sourceId);
            receivedRollback.set(rollback);
            receivedLatch.countDown();
        });

        // Create and send rollback
        var entityId = UUID.randomUUID();
        var rollback = new EntityRollbackEvent(
            entityId,
            sourceNodeId,
            targetNodeId,
            "timeout",
            System.nanoTime()
        );

        assertTrue(sourceChannel.sendEntityRollback(targetNodeId, rollback),
                   "Send should return true");

        // Wait for delivery
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive rollback within 5 seconds");

        // Verify received rollback
        assertEquals(sourceNodeId, receivedSourceId.get(), "Source ID should match");
        assertNotNull(receivedRollback.get(), "Rollback should be received");
        assertEquals(entityId, receivedRollback.get().getEntityId(), "Entity ID should match");
        assertEquals("timeout", receivedRollback.get().getReason());
    }

    /**
     * Test 5: Connection Refused (Unreachable Node)
     * Verify proper handling when target node is unreachable.
     */
    @Test
    void testUnreachableNode() {
        sourceChannel.initialize(sourceNodeId, "localhost:0");

        // Register unreachable node
        var unreachableNodeId = UUID.randomUUID();
        sourceChannel.registerNode(unreachableNodeId, "localhost:9999");

        // Try to send to unreachable node
        var event = new EntityDepartureEvent(
            UUID.randomUUID(),
            sourceNodeId,
            unreachableNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        // In gRPC, isNodeReachable just checks if address is registered
        // Actual connectivity is determined when sending (async failures)
        assertTrue(sourceChannel.isNodeReachable(unreachableNodeId),
                   "Registered node should show as reachable (actual connectivity checked on send)");

        // Send will return true (queued) but fail async
        assertTrue(sourceChannel.sendEntityDeparture(unreachableNodeId, event),
                   "Send returns true even if node is down (fails async)");
    }

    /**
     * Test 6: Connection Pooling (Reuse Channels)
     * Verify channels are reused for multiple messages to same node.
     */
    @Test
    void testConnectionPooling() throws Exception {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        var receivedCount = new AtomicInteger(0);
        var receivedLatch = new CountDownLatch(10);

        targetChannel.setEntityDepartureListener((sourceId, event) -> {
            receivedCount.incrementAndGet();
            receivedLatch.countDown();
        });

        // Send 10 rapid messages
        for (int i = 0; i < 10; i++) {
            var event = new EntityDepartureEvent(
                UUID.randomUUID(),
                sourceNodeId,
                targetNodeId,
                EntityMigrationState.MIGRATING_OUT,
                System.nanoTime()
            );
            assertTrue(sourceChannel.sendEntityDeparture(targetNodeId, event));
        }

        // All should be received
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive all 10 events");
        assertEquals(10, receivedCount.get(), "Should receive exactly 10 events");
    }

    /**
     * Test 7: Graceful Shutdown
     * Verify channels can be shut down without hanging.
     */
    @Test
    void testGracefulShutdown() throws Exception {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());

        // Shutdown should complete within reasonable time
        var shutdownLatch = new CountDownLatch(2);

        new Thread(() -> {
            try {
                sourceChannel.close();
                shutdownLatch.countDown();
            } catch (Exception e) {
                fail("Source shutdown failed: " + e.getMessage());
            }
        }).start();

        new Thread(() -> {
            try {
                targetChannel.close();
                shutdownLatch.countDown();
            } catch (Exception e) {
                fail("Target shutdown failed: " + e.getMessage());
            }
        }).start();

        assertTrue(shutdownLatch.await(5, TimeUnit.SECONDS),
                   "Shutdown should complete within 5 seconds");
    }

    /**
     * Test 8: Network Latency Simulation
     * Verify optional latency simulation works (backward compatibility with FakeNetworkChannel).
     */
    @Test
    void testNetworkLatencySimulation() throws Exception {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set latency simulation
        sourceChannel.setNetworkLatency(100); // 100ms latency

        var receivedLatch = new CountDownLatch(1);
        var receivedTime = new AtomicReference<Long>();

        targetChannel.setEntityDepartureListener((sourceId, event) -> {
            receivedTime.set(System.currentTimeMillis());
            receivedLatch.countDown();
        });

        var sendTime = System.currentTimeMillis();
        var event = new EntityDepartureEvent(
            UUID.randomUUID(),
            sourceNodeId,
            targetNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        sourceChannel.sendEntityDeparture(targetNodeId, event);

        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive event");

        // Note: Latency simulation may not be exact in gRPC, this is optional
        // Just verify it was received eventually
        assertNotNull(receivedTime.get());
    }

    /**
     * Test 9: Packet Loss Simulation
     * Verify optional packet loss simulation works (backward compatibility).
     */
    @Test
    void testPacketLossSimulation() {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());

        // Set 100% packet loss
        sourceChannel.setPacketLoss(1.0);

        var event = new EntityDepartureEvent(
            UUID.randomUUID(),
            sourceNodeId,
            targetNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        // Note: In gRPC, packet loss simulation may work differently
        // This test just ensures the API exists and doesn't crash
        sourceChannel.sendEntityDeparture(targetNodeId, event);
    }

    /**
     * Test 10: Multiple Simultaneous Channels
     * Verify multiple channels can coexist on same host.
     */
    @Test
    void testMultipleSimultaneousChannels() throws Exception {
        var node1Id = UUID.randomUUID();
        var node2Id = UUID.randomUUID();
        var node3Id = UUID.randomUUID();

        // Explicit plaintext opt-in for tests (Luciferase-7wzml.200).
        var channel1 = new GrpcBubbleNetworkChannel(true);
        var channel2 = new GrpcBubbleNetworkChannel(true);
        var channel3 = new GrpcBubbleNetworkChannel(true);

        try {
            channel1.initialize(node1Id, "localhost:0");
            channel2.initialize(node2Id, "localhost:0");
            channel3.initialize(node3Id, "localhost:0");

            // Register all nodes with each other
            channel1.registerNode(node2Id, channel2.getLocalAddress());
            channel1.registerNode(node3Id, channel3.getLocalAddress());
            channel2.registerNode(node1Id, channel1.getLocalAddress());
            channel2.registerNode(node3Id, channel3.getLocalAddress());
            channel3.registerNode(node1Id, channel1.getLocalAddress());
            channel3.registerNode(node2Id, channel2.getLocalAddress());

            // All should be reachable
            assertTrue(channel1.isNodeReachable(node2Id));
            assertTrue(channel1.isNodeReachable(node3Id));
            assertTrue(channel2.isNodeReachable(node1Id));
            assertTrue(channel2.isNodeReachable(node3Id));
            assertTrue(channel3.isNodeReachable(node1Id));
            assertTrue(channel3.isNodeReachable(node2Id));

        } finally {
            channel1.close();
            channel2.close();
            channel3.close();
        }
    }

    // ==================== .201: sendWithRetry onNext exception must not be silently swallowed ====================

    /**
     * Luciferase-7wzml.201: if the {@code onNext} handler inside {@code sendWithRetry} throws,
     * the exception must NOT be silently swallowed (previous code had {@code catch (Exception ignored)}).
     * <p>
     * This test drives a successful round-trip RPC and verifies that the channel remains operational
     * after the call. The production fix ensures the exception is logged at WARN rather than dropped.
     * We cannot inject a throwing lambda from outside the private method, so we verify the channel
     * continues working after a real successful exchange and that the onNext path is exercised.
     *
     * @see GrpcBubbleNetworkChannel#sendWithRetry
     */
    @Test
    void sendWithRetry_channelRemainsOperationalAfterSuccessfulExchange() throws Exception {
        var nodeId1 = UUID.randomUUID();
        var nodeId2 = UUID.randomUUID();
        var ch1 = new GrpcBubbleNetworkChannel(true);
        var ch2 = new GrpcBubbleNetworkChannel(true);
        try {
            ch1.initialize(nodeId1, "localhost:0");
            ch2.initialize(nodeId2, "localhost:0");
            ch1.registerNode(nodeId2, ch2.getLocalAddress());
            ch2.registerNode(nodeId1, ch1.getLocalAddress());

            var latch = new CountDownLatch(1);
            ch2.setEntityDepartureListener((srcId, evt) -> latch.countDown());

            var event = new EntityDepartureEvent(
                UUID.randomUUID(), nodeId1, nodeId2,
                EntityMigrationState.MIGRATING_OUT, System.nanoTime());
            assertTrue(ch1.sendEntityDeparture(nodeId2, event),
                       "sendEntityDeparture must return true (dispatched)");

            // Wait for delivery — confirms the onNext path completed without crashing the channel.
            assertTrue(latch.await(3, TimeUnit.SECONDS),
                       "EntityDepartureEvent must be received — onNext path must execute");

            // Channel must still be reachable after the exchange.
            assertTrue(ch1.isNodeReachable(nodeId2),
                       "Channel must remain operational after onNext execution");
        } finally {
            ch1.close();
            ch2.close();
        }
    }

    /**
     * Luciferase-3l1b5: initialize() registered a fresh JVM shutdown hook on every call and never removed it,
     * leaking a Thread per call. The hook is now registered at most once per instance and deregistered in
     * close(). Probe: {@code removeShutdownHook} returns {@code false} when the hook is not currently
     * registered — so after close() it must return false (close already removed it); pre-fix it would return
     * true (leak).
     */
    @Test
    void shutdownHook_deregisteredOnClose_andLifecycleIdempotent() throws Exception {
        var channel = new GrpcBubbleNetworkChannel(true);
        channel.initialize(UUID.randomUUID(), "localhost:0");

        var hookField = GrpcBubbleNetworkChannel.class.getDeclaredField("shutdownHook");
        hookField.setAccessible(true);
        var hook = (Thread) hookField.get(channel);
        assertNotNull(hook, "initialize() must register a shutdown hook");

        channel.close();
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook),
                    "close() must deregister the shutdown hook (no leak)");

        // Re-initialize + close again must not throw and must register/deregister a fresh single hook.
        channel.initialize(UUID.randomUUID(), "localhost:0");
        var hook2 = (Thread) hookField.get(channel);
        assertNotNull(hook2, "re-initialize() after close() must register a new hook");
        channel.close();
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook2),
                    "second close() must also deregister its hook");
    }
}
