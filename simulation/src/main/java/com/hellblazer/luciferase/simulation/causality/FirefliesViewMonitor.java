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

import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FirefliesViewMonitor - View Stability Detection (Phase 7C.4)
 *
 * Monitors cluster membership via Fireflies gossip protocol and detects
 * when the view has become stable (no changes for a threshold duration).
 * Signals safe points for entity migration commits by detecting view convergence.
 *
 * KEY CONCEPTS:
 * - View: Current set of active cluster members
 * - View Change: When a member joins or leaves
 * - View Stability: No view changes for N ticks (N=10 default = 100ms at 100Hz)
 * - Migration Signals: When view is stable, entity migrations can safely commit
 *
 * ARCHITECTURE:
 * - Listens to MembershipView for ViewChange notifications
 * - Tracks current view (members) and view change timestamp
 * - Increments tick counter on each onTick() call
 * - isViewStable() returns true when ticks_since_change >= threshold
 *
 * USAGE:
 * <pre>
 *   var monitor = new FirefliesViewMonitor(membershipView, 10); // 10 tick threshold
 *
 *   // Register tick listener with RealTimeController
 *   controller.addTickListener((simTime, clock) -> monitor.onTick(simTime));
 *
 *   // Query view stability (e.g., before committing migration)
 *   if (monitor.isViewStable()) {
 *       entityMigration.commit();
 *   }
 *
 *   // Get view information
 *   var members = monitor.getCurrentMembers();
 *   var stable = monitor.isViewStable();
 * </pre>
 *
 * FIREFLIES GOSSIP CONVERGENCE:
 * View stability is typically achieved within O(log n) rounds where n is cluster size.
 * At 100Hz tick rate with 10 tick threshold (100ms), this handles:
 * - Fireflies convergence: ~150ms for n=4 bubbles (typical Phase 7C test size)
 * - Network jitter: Up to 50-100ms delay
 * - Clock skew: Accommodated via max(local, remote) + 1 Lamport semantics
 *
 * THREAD SAFETY: View changes are notified on Fireflies callback thread,
 * but state is synchronized. Read operations are lock-free via volatile fields.
 *
 * @author hal.hildebrand
 */
public class FirefliesViewMonitor {

    private static final Logger log = LoggerFactory.getLogger(FirefliesViewMonitor.class);

    /**
     * Configuration for view stability detection.
     */
    public static class Configuration {
        public final int stabilityThresholdTicks;

        public Configuration(int stabilityThresholdTicks) {
            this.stabilityThresholdTicks = stabilityThresholdTicks;
        }
    }

    /**
     * Membership view from Fireflies.
     */
    private final MembershipView<?> membershipView;

    /**
     * Configuration for stability threshold.
     */
    private final Configuration config;

    /**
     * Current set of members in the view (thread-safe via Collections.synchronizedSet).
     */
    private final Set<Object> currentMembers;

    /**
     * Last timestamp when view changed (ticks).
     */
    private volatile long lastViewChangeTime = 0L;

    /**
     * Current simulation time (ticks).
     * Updated via onTick() for stability calculation.
     */
    private volatile long currentTime = 0L;

    /**
     * Flag tracking if any view change has occurred.
     * If false, view is initially stable (no changes yet).
     */
    private volatile boolean hasChanged = false;

    /**
     * Metrics: Total view changes detected.
     */
    private final AtomicLong totalViewChanges = new AtomicLong(0L);

    /**
     * Metrics: Total members joined.
     */
    private final AtomicLong totalMembersJoined = new AtomicLong(0L);

    /**
     * Metrics: Total members left.
     */
    private final AtomicLong totalMembersLeft = new AtomicLong(0L);

    /**
     * Metrics: Times view became stable.
     */
    private final AtomicLong timesStable = new AtomicLong(0L);

    /**
     * Previous stable state (for detecting transitions).
     */
    private volatile boolean wasStable = false;

    /**
     * Create a FirefliesViewMonitor with default stability threshold.
     * Default: 10 ticks (100ms at 100Hz) per audit recommendation.
     *
     * @param membershipView MembershipView from Fireflies
     */
    public FirefliesViewMonitor(MembershipView<?> membershipView) {
        this(membershipView, 10);  // 10 ticks = 100ms at 100Hz
    }

    /**
     * Create a FirefliesViewMonitor with custom stability threshold.
     *
     * @param membershipView        MembershipView from Fireflies
     * @param stabilityThresholdTicks Number of ticks before view is considered stable
     */
    public FirefliesViewMonitor(MembershipView<?> membershipView, int stabilityThresholdTicks) {
        this.membershipView = Objects.requireNonNull(membershipView, "membershipView must not be null");
        this.config = new Configuration(stabilityThresholdTicks);
        this.currentMembers = Collections.synchronizedSet(new HashSet<>());

        // Initialize members from view
        membershipView.getMembers().forEach(currentMembers::add);
        // hasChanged starts as false (initially stable, no changes yet)
        this.hasChanged = false;

        // Register for view change notifications
        membershipView.addListener(this::handleViewChange);

        log.debug("FirefliesViewMonitor created: threshold={} ticks", stabilityThresholdTicks);
    }

    /**
     * Handle view change notifications from MembershipView.
     * Updates current members and records change time.
     *
     * @param change ViewChange with joined and left members
     */
    private synchronized void handleViewChange(MembershipView.ViewChange<?> change) {
        Objects.requireNonNull(change, "change must not be null");

        var joined = change.joined();
        var left = change.left();

        // Add joined members
        for (var member : joined) {
            currentMembers.add(member);
            totalMembersJoined.incrementAndGet();
        }

        // Remove left members
        for (var member : left) {
            currentMembers.remove(member);
            totalMembersLeft.incrementAndGet();
        }

        // Update view change time
        lastViewChangeTime = currentTime;
        totalViewChanges.incrementAndGet();
        hasChanged = true;  // Mark that a change has occurred

        // Reset stable state on view change
        wasStable = false;

        log.debug("View change detected: joined={}, left={}, members={}, time={}",
                 joined.size(), left.size(), currentMembers.size(), currentTime);
    }

    /**
     * Called on each simulation tick to update current time.
     * Also checks for stability transitions to track metrics.
     *
     * @param simulationTime Current simulation time (ticks)
     */
    public void onTick(long simulationTime) {
        currentTime = simulationTime;
        // Check for stability transitions (updates wasStable and timesStable)
        isViewStable();
    }

    /**
     * Check if view is currently stable.
     * Returns true if no changes have occurred, or ticks since last view change >= threshold.
     *
     * @return true if view is stable
     */
    public boolean isViewStable() {
        // If no changes have occurred yet, view is stable (no perturbations)
        if (!hasChanged) {
            // Track that we transitioned to stable if not already
            if (!wasStable) {
                wasStable = true;
                timesStable.incrementAndGet();
            }
            return true;
        }

        var ticksSinceChange = currentTime - lastViewChangeTime;
        boolean stable = ticksSinceChange >= config.stabilityThresholdTicks;

        // Track transitions to stable state
        if (stable && !wasStable) {
            wasStable = true;
            timesStable.incrementAndGet();
            log.debug("View became stable: ticks={}, threshold={}", ticksSinceChange, config.stabilityThresholdTicks);
        } else if (!stable && wasStable) {
            wasStable = false;
            log.debug("View changed (became unstable): ticks={}, threshold={}", ticksSinceChange, config.stabilityThresholdTicks);
        }

        return stable;
    }

    /**
     * Get ticks since last view change.
     *
     * @return Number of ticks since last view change (or since start if no changes)
     */
    public long getTicksSinceLastChange() {
        return currentTime - lastViewChangeTime;
    }

    /**
     * Get current view members.
     * Returns a snapshot of current members.
     *
     * @return Unmodifiable set of current members
     */
    public Set<Object> getCurrentMembers() {
        return Collections.unmodifiableSet(new HashSet<>(currentMembers));
    }

    /**
     * Get current member count.
     *
     * @return Number of active members
     */
    public int getMemberCount() {
        return currentMembers.size();
    }

    /**
     * Get total view changes since creation.
     *
     * @return Total view changes
     */
    public long getTotalViewChanges() {
        return totalViewChanges.get();
    }

    /**
     * Get total members joined (cumulative).
     *
     * @return Total members joined
     */
    public long getTotalMembersJoined() {
        return totalMembersJoined.get();
    }

    /**
     * Get total members left (cumulative).
     *
     * @return Total members left
     */
    public long getTotalMembersLeft() {
        return totalMembersLeft.get();
    }

    /**
     * Get times view became stable (transitions from unstable to stable).
     *
     * @return Count of stability transitions
     */
    public long getTimesStable() {
        return timesStable.get();
    }

    /**
     * Get configuration.
     *
     * @return Configuration instance
     */
    public Configuration getConfiguration() {
        return config;
    }

    /**
     * Reset all metrics and state (for testing or recovery).
     * WARNING: Clears all history and current member list.
     */
    public void reset() {
        currentMembers.clear();
        lastViewChangeTime = 0L;
        currentTime = 0L;
        hasChanged = false;  // Reset to initial stable state
        totalViewChanges.set(0L);
        totalMembersJoined.set(0L);
        totalMembersLeft.set(0L);
        timesStable.set(0L);
        wasStable = false;
        log.debug("FirefliesViewMonitor reset");
    }

    /**
     * Get view health status.
     * Returns true if view is stable and has reasonable member count.
     * A healthy view is one that hasn't recently changed.
     *
     * @return true if view is healthy
     */
    public boolean isHealthy() {
        return isViewStable() && !currentMembers.isEmpty();
    }

    /**
     * Get estimated Fireflies convergence time for cluster size.
     * Used for understanding stability threshold adequacy.
     *
     * @return Estimated ticks needed for Fireflies convergence
     */
    public long estimatedConvergenceTicks() {
        // Fireflies converges in O(log n) rounds
        // For 4 bubbles: ~2 rounds × 10 ticks/round = 20 ticks (200ms)
        // For 8 bubbles: ~3 rounds × 10 ticks/round = 30 ticks (300ms)
        // For 16 bubbles: ~4 rounds × 10 ticks/round = 40 ticks (400ms)
        var n = Math.max(1, currentMembers.size());
        var logN = (long) Math.ceil(Math.log(n) / Math.log(2));
        return logN * 10;  // 10 ticks per round estimate
    }

    @Override
    public String toString() {
        return String.format("FirefliesViewMonitor{members=%d, stable=%s, changes=%d, joined=%d, left=%d, timesStable=%d}",
                           currentMembers.size(), isViewStable(), totalViewChanges.get(),
                           totalMembersJoined.get(), totalMembersLeft.get(), timesStable.get());
    }
}
