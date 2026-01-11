/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper for ConsensusBubbleNode that injects Byzantine behavior.
 * <p>
 * Responsibilities:
 * - Intercept voting operations
 * - Modify votes based on Byzantine behavior mode
 * - Drop or delay messages
 * - Simulate slow processing
 * - Track behavior injection metrics
 * <p>
 * BYZANTINE MODES:
 * - NORMAL: No Byzantine behavior (passthrough)
 * - VOTE_ALWAYS_NO: Always vote "NO" regardless of proposal
 * - VOTE_ALWAYS_YES: Always vote "YES" regardless of proposal
 * - VOTE_RANDOM: Vote randomly
 * - DROP_MESSAGES: Drop 50% of incoming messages
 * - DELAY_RESPONSES: Random delay (1-2s) on responses
 * - CORRUPT_STATE: Report incorrect entity count
 * <p>
 * DELEGATION:
 * Wraps ConsensusBubbleNode and delegates all normal operations.
 * Only intercepts operations when Byzantine mode is active.
 * <p>
 * THREAD SAFETY:
 * Uses atomic counters for metrics tracking.
 * <p>
 * Phase 8D Day 1: Byzantine Failure Injection
 *
 * @author hal.hildebrand
 */
public class ByzantineNode {

    private static final Logger log = LoggerFactory.getLogger(ByzantineNode.class);

    /**
     * Byzantine behavior modes.
     */
    public enum ByzantineMode {
        NORMAL,              // No Byzantine behavior
        VOTE_ALWAYS_NO,      // Always vote "NO"
        VOTE_ALWAYS_YES,     // Always vote "YES"
        VOTE_RANDOM,         // Vote randomly
        DROP_MESSAGES,       // Drop 50% of messages
        DELAY_RESPONSES,     // Random 1-2s delays
        CORRUPT_STATE        // Report incorrect state
    }

    private final ConsensusBubbleNode delegate;
    private volatile ByzantineMode currentMode;
    private volatile double messageDropRate = 0.5; // Default 50% drop rate
    private volatile long delayMs = 1500; // Default 1.5s delay

    /**
     * Metrics
     */
    private final AtomicLong totalBehaviorInjections = new AtomicLong(0);
    private final AtomicInteger votesModified = new AtomicInteger(0);
    private final AtomicInteger messagesDropped = new AtomicInteger(0);

    private final Random random = new Random();

    /**
     * Create ByzantineNode wrapper.
     *
     * @param delegate Delegate ConsensusBubbleNode
     * @param mode     Initial Byzantine mode
     */
    public ByzantineNode(ConsensusBubbleNode delegate, ByzantineMode mode) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.currentMode = Objects.requireNonNull(mode, "mode must not be null");

        log.debug("Created ByzantineNode wrapper for bubble {} with mode {}",
                 delegate.getBubbleIndex(), mode);
    }

    /**
     * Set Byzantine mode.
     *
     * @param mode Byzantine behavior mode
     */
    public void setByzantineMode(ByzantineMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        this.currentMode = mode;
        log.info("Byzantine mode changed to {} for bubble {}", mode, delegate.getBubbleIndex());
    }

    /**
     * Disable Byzantine behavior (return to normal).
     */
    public void disableByzantine() {
        this.currentMode = ByzantineMode.NORMAL;
        log.info("Byzantine behavior disabled for bubble {}", delegate.getBubbleIndex());
    }

    /**
     * Get current Byzantine mode.
     *
     * @return Current mode
     */
    public ByzantineMode getCurrentMode() {
        return currentMode;
    }

    /**
     * Inject delay on responses.
     *
     * @param delayMs Delay in milliseconds
     */
    public void injectDelay(long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("Delay must be non-negative, got " + delayMs);
        }
        this.delayMs = delayMs;
        log.debug("Set delay to {}ms for bubble {}", delayMs, delegate.getBubbleIndex());
    }

    /**
     * Set message drop rate.
     *
     * @param rate Drop rate (0.0-1.0)
     */
    public void setMessageDropRate(double rate) {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Drop rate must be 0.0-1.0, got " + rate);
        }
        this.messageDropRate = rate;
        log.debug("Set message drop rate to {} for bubble {}", rate, delegate.getBubbleIndex());
    }

    /**
     * Get total behavior injections count.
     *
     * @return Total injections
     */
    public long getTotalBehaviorInjections() {
        return totalBehaviorInjections.get();
    }

    /**
     * Get votes modified count.
     *
     * @return Votes modified
     */
    public int getVotesModified() {
        return votesModified.get();
    }

    /**
     * Get messages dropped count.
     *
     * @return Messages dropped
     */
    public int getMessagesDropped() {
        return messagesDropped.get();
    }

    // ========== Delegate Methods ==========

    /**
     * Get bubble index from delegate.
     *
     * @return Bubble index
     */
    public int getBubbleIndex() {
        return delegate.getBubbleIndex();
    }

    /**
     * Get tetrahedra from delegate.
     *
     * @return Tetrahedra array
     */
    public TetreeKey<?>[] getTetrahedra() {
        return delegate.getTetrahedra();
    }

    /**
     * Get local entities from delegate.
     * <p>
     * May corrupt count if CORRUPT_STATE mode active.
     *
     * @return Local entities set
     */
    public Set<UUID> getLocalEntities() {
        if (currentMode == ByzantineMode.CORRUPT_STATE) {
            // Return corrupted entity set (add fake entity)
            var corrupted = new HashSet<>(delegate.getLocalEntities());
            corrupted.add(UUID.randomUUID()); // Add fake entity
            totalBehaviorInjections.incrementAndGet();
            log.debug("Corrupted entity count for bubble {}", delegate.getBubbleIndex());
            return corrupted;
        }
        return delegate.getLocalEntities();
    }

    /**
     * Check if entity is in bubble (delegate).
     *
     * @param entityId Entity ID
     * @return true if entity present
     */
    public boolean containsEntity(UUID entityId) {
        return delegate.containsEntity(entityId);
    }

    /**
     * Add entity (delegate).
     *
     * @param entityId   Entity ID
     * @param sourceNode Source node
     */
    public void addEntity(UUID entityId, Digest sourceNode) {
        applyDelayIfNeeded();
        delegate.addEntity(entityId, sourceNode);
    }

    /**
     * Remove entity (delegate).
     *
     * @param entityId Entity ID
     */
    public void removeEntity(UUID entityId) {
        applyDelayIfNeeded();
        delegate.removeEntity(entityId);
    }

    /**
     * Move entity locally (delegate).
     *
     * @param entityId    Entity ID
     * @param newLocation New location
     */
    public void moveEntityLocal(UUID entityId, TetreeKey<?> newLocation) {
        applyDelayIfNeeded();
        delegate.moveEntityLocal(entityId, newLocation);
    }

    /**
     * Request cross-bubble migration (delegate).
     * <p>
     * May modify vote if Byzantine voting mode active.
     *
     * @param entityId          Entity ID
     * @param targetBubbleIndex Target bubble
     * @return CompletableFuture<Boolean>
     */
    public CompletableFuture<Boolean> requestCrossBubbleMigration(UUID entityId, int targetBubbleIndex) {
        applyDelayIfNeeded();

        // If Byzantine voting mode, modify vote
        if (currentMode == ByzantineMode.VOTE_ALWAYS_NO) {
            votesModified.incrementAndGet();
            totalBehaviorInjections.incrementAndGet();
            log.debug("Byzantine vote: ALWAYS_NO for entity {} migration", entityId);
            return CompletableFuture.completedFuture(false); // Always reject
        } else if (currentMode == ByzantineMode.VOTE_ALWAYS_YES) {
            votesModified.incrementAndGet();
            totalBehaviorInjections.incrementAndGet();
            log.debug("Byzantine vote: ALWAYS_YES for entity {} migration", entityId);
            return CompletableFuture.completedFuture(true); // Always approve
        } else if (currentMode == ByzantineMode.VOTE_RANDOM) {
            votesModified.incrementAndGet();
            totalBehaviorInjections.incrementAndGet();
            var randomVote = random.nextBoolean();
            log.debug("Byzantine vote: RANDOM ({}) for entity {} migration", randomVote, entityId);
            return CompletableFuture.completedFuture(randomVote);
        }

        return delegate.requestCrossBubbleMigration(entityId, targetBubbleIndex);
    }

    /**
     * Get committee view ID (delegate).
     *
     * @return View ID
     */
    public Digest getCommitteeViewId() {
        return delegate.getCommitteeViewId();
    }

    /**
     * Get committee members (delegate).
     *
     * @return Committee members
     */
    public Set<Digest> getCommitteeMembers() {
        return delegate.getCommitteeMembers();
    }

    // ========== Private Methods ==========

    /**
     * Apply delay if DELAY_RESPONSES mode active.
     */
    private void applyDelayIfNeeded() {
        if (currentMode == ByzantineMode.DELAY_RESPONSES) {
            try {
                // Random delay between delayMs and delayMs + 500ms
                var actualDelay = delayMs + random.nextInt(500);
                Thread.sleep(actualDelay);
                totalBehaviorInjections.incrementAndGet();
                log.debug("Injected {}ms delay for bubble {}", actualDelay, delegate.getBubbleIndex());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Delay injection interrupted for bubble {}", delegate.getBubbleIndex());
            }
        }
    }

    /**
     * Check if message should be dropped.
     *
     * @return true if message should be dropped
     */
    private boolean shouldDropMessage() {
        if (currentMode == ByzantineMode.DROP_MESSAGES) {
            if (random.nextDouble() < messageDropRate) {
                messagesDropped.incrementAndGet();
                totalBehaviorInjections.incrementAndGet();
                log.debug("Dropped message for bubble {}", delegate.getBubbleIndex());
                return true;
            }
        }
        return false;
    }
}
