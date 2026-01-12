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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * In-process P2P transport for testing VON communication.
 * <p>
 * Provides direct message delivery between LocalServerTransport instances
 * without network overhead. Used for integration testing with
 * TestClusterBuilder.
 * <p>
 * Features:
 * <ul>
 *   <li>Zero-copy in-process message delivery</li>
 *   <li>Thread-safe concurrent operations</li>
 *   <li>Automatic routing via TetreeKey hash</li>
 *   <li>Async message handling with CompletableFuture</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * var registry = LocalServerTransport.Registry.create();
 * var transport1 = registry.register(uuid1);
 * var transport2 = registry.register(uuid2);
 *
 * transport1.sendToNeighbor(uuid2, message);
 * </pre>
 *
 * @author hal.hildebrand
 */
public class LocalServerTransport implements VonTransport {

    private static final Logger log = LoggerFactory.getLogger(LocalServerTransport.class);

    private final UUID localId;
    private final Registry registry;
    private final VonMessageFactory factory;
    private final List<Consumer<VonMessage>> handlers = new CopyOnWriteArrayList<>();
    private final Map<UUID, CompletableFuture<VonMessage.Ack>> pendingAcks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private volatile boolean connected = true;

    // Failure injection for testing (Phase 6A)
    private volatile long injectDelayMs = 0;
    private volatile boolean injectPartition = false;

    /**
     * Create a LocalServerTransport (use Registry.register() instead).
     */
    LocalServerTransport(UUID localId, Registry registry, ExecutorService executor) {
        this.localId = localId;
        this.registry = registry;
        this.factory = VonMessageFactory.system();
        this.executor = executor;
    }

    @Override
    public void sendToNeighbor(UUID neighborId, VonMessage message) throws TransportException {
        if (!connected) {
            throw new TransportException("Transport is closed");
        }

        // Failure injection: Simulate network partition (drop message)
        if (injectPartition) {
            log.debug("{} -> {}: DROPPED (partition injected)", localId, neighborId);
            return; // Message silently dropped
        }

        // Failure injection: Simulate network delay
        if (injectDelayMs > 0) {
            try {
                Thread.sleep(injectDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportException("Interrupted during injected delay", e);
            }
        }

        var neighbor = registry.get(neighborId);
        if (neighbor == null) {
            throw new TransportException("Unknown neighbor: " + neighborId);
        }

        log.debug("{} -> {}: {}", localId, neighborId, message.getClass().getSimpleName());
        neighbor.deliver(message);
    }

    @Override
    public CompletableFuture<VonMessage.Ack> sendToNeighborAsync(UUID neighborId, VonMessage message) {
        if (!connected) {
            return CompletableFuture.failedFuture(new TransportException("Transport is closed"));
        }

        var future = new CompletableFuture<VonMessage.Ack>();
        var messageId = UUID.randomUUID();
        pendingAcks.put(messageId, future);

        executor.submit(() -> {
            try {
                sendToNeighbor(neighborId, message);
                // For LocalServer, immediately complete (real impl would wait for ACK)
                future.complete(factory.createAck(messageId, neighborId));
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                pendingAcks.remove(messageId);
            }
        });

        return future;
    }

    @Override
    public void onMessage(Consumer<VonMessage> handler) {
        handlers.add(handler);
    }

    @Override
    public void removeMessageHandler(Consumer<VonMessage> handler) {
        handlers.remove(handler);
    }

    @Override
    public Optional<MemberInfo> lookupMember(UUID memberId) {
        var transport = registry.get(memberId);
        if (transport == null) {
            return Optional.empty();
        }
        return Optional.of(new MemberInfo(memberId, "local:" + memberId));
    }

    @Override
    public MemberInfo routeToKey(TetreeKey<?> key) throws TransportException {
        var members = registry.getAllIds();
        if (members.isEmpty()) {
            throw new TransportException("No members available for routing");
        }

        // Deterministic routing: hash key to member index
        var hash = key.getLowBits() ^ key.getHighBits();
        var absHash = hash == Long.MIN_VALUE ? 0 : Math.abs(hash);
        var index = (int) (absHash % members.size());

        var targetId = members.get(index);
        return new MemberInfo(targetId, "local:" + targetId);
    }

    @Override
    public UUID getLocalId() {
        return localId;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        connected = false;
        registry.unregister(localId);
        pendingAcks.values().forEach(f -> f.cancel(true));
        pendingAcks.clear();
    }

    /**
     * Deliver a message to this transport (called by sender).
     * <p>
     * Public to allow cross-package message delivery for migration coordinator.
     */
    public void deliver(VonMessage message) {
        executor.submit(() -> {
            for (var handler : handlers) {
                try {
                    handler.accept(message);
                } catch (Exception e) {
                    log.error("Handler error for {}: {}", message.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Inject artificial network delay for testing.
     * <p>
     * Phase 6A failure injection API: Allows tests to simulate network latency
     * by adding a delay before each sendToNeighbor operation.
     *
     * @param delayMs Delay in milliseconds (0 to disable)
     */
    public void injectDelay(long delayMs) {
        this.injectDelayMs = delayMs;
        log.debug("Injected delay: {} ms", delayMs);
    }

    /**
     * Inject network partition for testing.
     * <p>
     * Phase 6A failure injection API: When enabled, all sendToNeighbor calls
     * silently drop messages (simulating complete network partition).
     *
     * @param enabled true to drop all messages, false to restore normal operation
     */
    public void injectPartition(boolean enabled) {
        this.injectPartition = enabled;
        log.debug("Injected partition: {}", enabled);
    }

    /**
     * Registry for LocalServerTransport instances.
     * <p>
     * Provides discovery and routing between in-process transports.
     */
    public static class Registry implements AutoCloseable {
        private final Map<UUID, LocalServerTransport> transports = new ConcurrentHashMap<>();
        private final ExecutorService executor;

        private Registry(ExecutorService executor) {
            this.executor = executor;
        }

        /**
         * Create a new registry with a shared executor.
         */
        public static Registry create() {
            return new Registry(Executors.newCachedThreadPool(r -> {
                var t = new Thread(r, "local-transport");
                t.setDaemon(true);
                return t;
            }));
        }

        /**
         * Create a new registry with a custom executor.
         */
        public static Registry create(ExecutorService executor) {
            return new Registry(executor);
        }

        /**
         * Register a new transport and return it.
         *
         * @param nodeId UUID for the new transport
         * @return The registered transport
         */
        public LocalServerTransport register(UUID nodeId) {
            var transport = new LocalServerTransport(nodeId, this, executor);
            transports.put(nodeId, transport);
            log.debug("Registered transport: {}", nodeId);
            return transport;
        }

        /**
         * Get a transport by UUID.
         */
        LocalServerTransport get(UUID nodeId) {
            return transports.get(nodeId);
        }

        /**
         * Unregister a transport.
         */
        void unregister(UUID nodeId) {
            transports.remove(nodeId);
            log.debug("Unregistered transport: {}", nodeId);
        }

        /**
         * Get all registered transport IDs.
         */
        List<UUID> getAllIds() {
            return new ArrayList<>(transports.keySet());
        }

        /**
         * Get the number of registered transports.
         */
        public int size() {
            return transports.size();
        }

        @Override
        public void close() {
            transports.values().forEach(LocalServerTransport::close);
            transports.clear();
            executor.shutdown();
        }
    }
}
