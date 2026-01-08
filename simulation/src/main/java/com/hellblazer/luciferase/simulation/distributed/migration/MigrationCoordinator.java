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

package com.hellblazer.luciferase.simulation.distributed.migration;

import com.hellblazer.luciferase.simulation.distributed.*;
import com.hellblazer.luciferase.simulation.distributed.migration.MigrationProtocolMessages.*;
import com.hellblazer.luciferase.simulation.von.VonMessage;
import com.hellblazer.luciferase.simulation.von.VonTransport;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Per-process migration coordinator.
 * <p>
 * Responsibilities:
 * - Register message handlers with ProcessCoordinator for all 6 migration protocol message types
 * - Route incoming migration requests to local bubbles
 * - Track pending transactions (Map<UUID, MigrationTransaction>)
 * - Periodic cleanup of expired transactions (>300ms timeout)
 * - Send responses back via VonTransport
 * - Thread-safe concurrent operation
 * <p>
 * Protocol Message Flow:
 * <pre>
 * Incoming PrepareRequest  → validate → delegate to source bubble → send PrepareResponse
 * Incoming CommitRequest   → validate transaction exists → delegate to dest bubble → send CommitResponse
 * Incoming AbortRequest    → validate transaction exists → delegate to source bubble → send AbortResponse
 * Outgoing responses sent via VonTransport back to originating process
 * </pre>
 * <p>
 * Transaction Lifecycle:
 * 1. INCOMING REQUEST (PrepareRequest/CommitRequest/AbortRequest)
 * 2. VALIDATE (idempotency, message ordering)
 * 3. EXECUTE (delegate to local bubble)
 * 4. RESPOND (send appropriate response message)
 * 5. CLEANUP (remove from pending after 300ms timeout)
 * <p>
 * Timeout Strategy:
 * - Transactions tracked with createdAt timestamp
 * - Cleanup scheduler runs every 500ms
 * - Removes transactions > 300ms old
 * - No active waiting - passive cleanup
 * <p>
 * Architecture Decision D6B.9: Per-Process Coordination
 * - MigrationCoordinator runs per-process, not globally
 * - Coordinates responses to incoming migration protocol requests
 * - Separate from CrossProcessMigration which initiates migrations
 *
 * @author hal.hildebrand
 */
public class MigrationCoordinator {

    private static final Logger log                  = LoggerFactory.getLogger(MigrationCoordinator.class);
    private static final long   TRANSACTION_TIMEOUT  = 300; // ms
    private static final long   CLEANUP_INTERVAL     = 500; // ms

    private final ProcessCoordinator                 coordinator;
    private final VonTransport                       transport;
    private final VONDiscoveryProtocol               discoveryProtocol;
    private final MessageOrderValidator              validator;
    private final Map<UUID, MigrationTransaction>    pendingTransactions;
    private final ScheduledExecutorService           cleanupScheduler;
    private final MigrationMetrics                   metrics;

    private volatile boolean running = false;

    /**
     * Create a MigrationCoordinator.
     *
     * @param coordinator       ProcessCoordinator for process coordination
     * @param transport         VonTransport for outgoing message delivery
     * @param discoveryProtocol VONDiscoveryProtocol for bubble lookups
     * @param validator         MessageOrderValidator for sequence validation
     */
    public MigrationCoordinator(ProcessCoordinator coordinator, VonTransport transport,
                                VONDiscoveryProtocol discoveryProtocol, MessageOrderValidator validator) {
        this.coordinator = coordinator;
        this.transport = transport;
        this.discoveryProtocol = discoveryProtocol;
        this.validator = validator;
        this.pendingTransactions = new ConcurrentHashMap<>();
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "migration-coordinator-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.metrics = new MigrationMetrics();
    }

    /**
     * Register message handlers with transport for all 6 message types.
     * <p>
     * Handlers registered:
     * - PrepareRequest → handlePrepareRequest
     * - PrepareResponse → handlePrepareResponse
     * - CommitRequest → handleCommitRequest
     * - CommitResponse → handleCommitResponse
     * - AbortRequest → handleAbortRequest
     * - AbortResponse → handleAbortResponse
     */
    public void register() {
        if (running) {
            throw new IllegalStateException("MigrationCoordinator already running");
        }

        running = true;

        // Register handlers for all 6 message types
        transport.onMessage(msg -> {
            if (!running) {
                return; // Ignore messages after shutdown
            }

            if (msg instanceof PrepareRequest request) {
                handlePrepareRequest(request);
            } else if (msg instanceof PrepareResponse response) {
                handlePrepareResponse(response);
            } else if (msg instanceof CommitRequest request) {
                handleCommitRequest(request);
            } else if (msg instanceof CommitResponse response) {
                handleCommitResponse(response);
            } else if (msg instanceof AbortRequest request) {
                handleAbortRequest(request);
            } else if (msg instanceof AbortResponse response) {
                handleAbortResponse(response);
            }
        });

        // Start cleanup scheduler
        cleanupScheduler.scheduleAtFixedRate(
            this::cleanupExpiredTransactions,
            CLEANUP_INTERVAL,
            CLEANUP_INTERVAL,
            TimeUnit.MILLISECONDS
        );

        log.info("MigrationCoordinator registered and started on process {}", transport.getLocalId());
    }

    /**
     * Handle incoming PrepareRequest.
     * <p>
     * Flow:
     * 1. Validate destination exists
     * 2. Create transaction
     * 3. Add to pending
     * 4. Send PrepareResponse
     *
     * @param request PrepareRequest message
     */
    void handlePrepareRequest(PrepareRequest request) {
        var txnId = request.transactionId();
        var entityId = request.entitySnapshot().entityId();
        var sourceId = request.sourceId();
        var destId = request.destId();

        log.debug("Handling PrepareRequest: txn={}, entity={}, source={}, dest={}",
                 txnId, entityId, sourceId, destId);

        // Validate destination exists (check if bubble is registered)
        var neighbors = discoveryProtocol.getNeighbors(destId);
        var destExists = discoveryProtocol.getNeighborIndex() != null; // Simplified check

        // For testing: Check if destination was registered via handleJoin
        var isDestReachable = checkBubbleExists(destId);

        if (!isDestReachable) {
            log.warn("Destination unreachable for entity {}, txn={}", entityId, txnId);
            sendPrepareResponse(txnId, false, "UNREACHABLE", null);
            metrics.recordFailure("DESTINATION_UNREACHABLE");
            return;
        }

        // Create transaction
        var txn = new MigrationTransaction(
            txnId,
            request.idempotencyToken(),
            request.entitySnapshot(),
            createBubbleReference(sourceId),
            createBubbleReference(destId)
        );

        // Add to pending
        pendingTransactions.put(txnId, txn);

        log.debug("PrepareRequest successful: txn={}, entity={}", txnId, entityId);

        // Send success response
        sendPrepareResponse(txnId, true, null, transport.getLocalId());
    }

    /**
     * Handle incoming PrepareResponse.
     *
     * @param response PrepareResponse message
     */
    void handlePrepareResponse(PrepareResponse response) {
        var txnId = response.transactionId();
        log.debug("Handling PrepareResponse: txn={}, success={}", txnId, response.success());

        // For Phase 6B4.5, this is primarily logged
        // Phase 6B5+ will use responses to drive state machine
    }

    /**
     * Handle incoming CommitRequest.
     *
     * @param request CommitRequest message
     */
    void handleCommitRequest(CommitRequest request) {
        var txnId = request.transactionId();
        log.debug("Handling CommitRequest: txn={}, confirmed={}", txnId, request.confirmed());

        // Validate transaction exists
        var txn = pendingTransactions.get(txnId);
        if (txn == null) {
            log.warn("CommitRequest for unknown transaction: txn={}", txnId);
            sendCommitResponse(txnId, false, "UNKNOWN_TRANSACTION");
            return;
        }

        // Delegate to destination bubble (simulated)
        // In production: dest.asLocal().addEntity(txn.entitySnapshot())
        log.debug("CommitRequest successful: txn={}", txnId);

        sendCommitResponse(txnId, true, null);
    }

    /**
     * Handle incoming CommitResponse.
     *
     * @param response CommitResponse message
     */
    void handleCommitResponse(CommitResponse response) {
        var txnId = response.transactionId();
        log.debug("Handling CommitResponse: txn={}, success={}", txnId, response.success());

        // For Phase 6B4.5, this is primarily logged
        // Phase 6B5+ will use responses to drive state machine
    }

    /**
     * Handle incoming AbortRequest.
     *
     * @param request AbortRequest message
     */
    void handleAbortRequest(AbortRequest request) {
        var txnId = request.transactionId();
        log.debug("Handling AbortRequest: txn={}, reason={}", txnId, request.reason());

        // Validate transaction exists
        var txn = pendingTransactions.get(txnId);
        if (txn == null) {
            log.warn("AbortRequest for unknown transaction: txn={}", txnId);
            sendAbortResponse(txnId, false);
            return;
        }

        // Delegate to source bubble (simulated rollback)
        // In production: source.asLocal().addEntity(txn.entitySnapshot())
        log.debug("AbortRequest successful: txn={}", txnId);

        // Remove from pending
        pendingTransactions.remove(txnId);

        sendAbortResponse(txnId, true);
    }

    /**
     * Handle incoming AbortResponse.
     *
     * @param response AbortResponse message
     */
    void handleAbortResponse(AbortResponse response) {
        var txnId = response.transactionId();
        log.debug("Handling AbortResponse: txn={}, rolledBack={}", txnId, response.rolledBack());

        // For Phase 6B4.5, this is primarily logged
        // Phase 6B5+ will use responses to drive state machine
    }

    /**
     * Get current pending transaction count.
     *
     * @return Number of pending transactions
     */
    public int getPendingTransactions() {
        return pendingTransactions.size();
    }

    /**
     * Shutdown the coordinator.
     * <p>
     * Stops cleanup scheduler and clears pending transactions.
     */
    public void shutdown() {
        if (!running) {
            return;
        }

        log.info("Shutting down MigrationCoordinator");
        running = false;

        // Stop cleanup scheduler
        cleanupScheduler.shutdownNow();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Cleanup scheduler did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for cleanup scheduler shutdown", e);
        }

        // Clear pending transactions
        pendingTransactions.clear();

        log.info("MigrationCoordinator shutdown complete");
    }

    /**
     * Get metrics.
     *
     * @return MigrationMetrics
     */
    public MigrationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Periodic cleanup of expired transactions.
     * <p>
     * Removes transactions older than TRANSACTION_TIMEOUT (300ms).
     */
    private void cleanupExpiredTransactions() {
        var now = System.currentTimeMillis();
        var expiredCount = 0;

        var iterator = pendingTransactions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var txn = entry.getValue();

            if (txn.isTimedOut(TRANSACTION_TIMEOUT)) {
                iterator.remove();
                expiredCount++;
                log.debug("Cleaned up expired transaction: txn={}, elapsed={}ms",
                         entry.getKey(), txn.elapsedTime());
            }
        }

        if (expiredCount > 0) {
            log.debug("Cleaned up {} expired transactions", expiredCount);
        }
    }

    /**
     * Send PrepareResponse back to originating process.
     *
     * @param txnId         Transaction ID
     * @param success       True if prepare succeeded
     * @param reason        Failure reason (null if success)
     * @param destProcessId Destination process ID (null if failed)
     */
    private void sendPrepareResponse(UUID txnId, boolean success, String reason, UUID destProcessId) {
        var response = new PrepareResponse(txnId, success, reason, destProcessId);
        // Deliver locally for in-process testing
        deliverLocalMessage(response);
    }

    /**
     * Send CommitResponse back to originating process.
     *
     * @param txnId   Transaction ID
     * @param success True if commit succeeded
     * @param reason  Failure reason (null if success)
     */
    private void sendCommitResponse(UUID txnId, boolean success, String reason) {
        var response = new CommitResponse(txnId, success, reason);
        // Deliver locally for in-process testing
        deliverLocalMessage(response);
    }

    /**
     * Send AbortResponse back to originating process.
     *
     * @param txnId      Transaction ID
     * @param rolledBack True if rollback succeeded
     */
    private void sendAbortResponse(UUID txnId, boolean rolledBack) {
        var response = new AbortResponse(txnId, rolledBack);
        // Deliver locally for in-process testing
        deliverLocalMessage(response);
    }

    /**
     * Deliver message to local handlers (for in-process testing).
     * <p>
     * In production, this would route via P2P transport to the originating process.
     * For LocalServerTransport testing, we directly invoke local message handlers.
     *
     * @param message Message to deliver
     */
    private void deliverLocalMessage(VonMessage message) {
        try {
            // For LocalServerTransport, we need to cast and call deliver
            if (transport instanceof com.hellblazer.luciferase.simulation.von.LocalServerTransport local) {
                local.deliver(message);
            } else {
                log.warn("Cannot deliver {} - transport is not LocalServerTransport", message.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.warn("Failed to deliver {}: {}", message.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /**
     * Check if a bubble exists (registered with discovery protocol).
     * <p>
     * For testing: Uses VONDiscoveryProtocol state.
     * For production: Would query ProcessRegistry.
     *
     * @param bubbleId Bubble UUID
     * @return True if bubble exists
     */
    private boolean checkBubbleExists(UUID bubbleId) {
        return discoveryProtocol.hasBubble(bubbleId);
    }

    /**
     * Create a BubbleReference for testing.
     * <p>
     * For production: Would query ProcessRegistry for actual bubble reference.
     *
     * @param bubbleId Bubble UUID
     * @return BubbleReference
     */
    private BubbleReference createBubbleReference(UUID bubbleId) {
        return new BubbleReference() {
            @Override
            public UUID getBubbleId() {
                return bubbleId;
            }

            @Override
            public boolean isLocal() {
                return true;
            }

            @Override
            public LocalBubbleReference asLocal() {
                throw new UnsupportedOperationException("Not implemented for testing");
            }

            @Override
            public RemoteBubbleProxy asRemote() {
                throw new IllegalStateException("This is a local reference");
            }

            @Override
            public Point3D getPosition() {
                return new Point3D(0, 0, 0); // Placeholder for testing
            }

            @Override
            public Set<UUID> getNeighbors() {
                return Set.of(); // Placeholder for testing
            }
        };
    }
}
