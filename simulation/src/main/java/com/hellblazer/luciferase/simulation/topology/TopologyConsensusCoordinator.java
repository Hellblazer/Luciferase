/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.consensus.committee.MigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.ViewCommitteeConsensus;
import com.hellblazer.luciferase.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates topology change proposals through committee consensus voting.
 * <p>
 * Wraps {@link ViewCommitteeConsensus} to provide Byzantine fault-tolerant
 * approval of topology changes (split/merge/move operations). Adds cooldown
 * timer to prevent rapid topology oscillation.
 * <p>
 * <b>Key Features</b>:
 * <ul>
 *   <li><b>Pre-validation</b>: Rejects Byzantine proposals before voting</li>
 *   <li><b>Cooldown Timer</b>: 30-second minimum between topology changes</li>
 *   <li><b>View ID Verification</b>: Prevents cross-view double-commit races</li>
 *   <li><b>PrimeMover Integration</b>: Uses Clock interface for deterministic simulation time</li>
 * </ul>
 * <p>
 * <b>Workflow</b>:
 * <ol>
 *   <li>Check cooldown timer (reject if too recent)</li>
 *   <li>Pre-validate proposal against bubble grid (reject Byzantine inputs)</li>
 *   <li>Submit to ViewCommitteeConsensus for voting</li>
 *   <li>If approved, update last change timestamp</li>
 * </ol>
 * <p>
 * <b>Cooldown Rationale</b>:
 * Prevents rapid topology oscillation when entity counts fluctuate near
 * thresholds. DensityMonitor's 10% hysteresis handles most cases, but
 * cooldown provides additional safety against flapping.
 * <p>
 * <b>Thread Safety</b>:
 * Uses ConcurrentHashMap for cooldown tracking. Clock is volatile for
 * visibility across threads.
 * <p>
 * Phase 9B: Consensus Topology Coordination
 *
 * @author hal.hildebrand
 */
public class TopologyConsensusCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TopologyConsensusCoordinator.class);

    /**
     * Default cooldown period: 30 seconds between topology changes.
     * <p>
     * Prevents rapid oscillation while allowing reasonable adaptation rate
     * (max 2 changes per minute).
     */
    public static final long DEFAULT_COOLDOWN_MS = 30_000L;

    private final TetreeBubbleGrid bubbleGrid;
    private final long cooldownMillis;
    private volatile Clock clock;

    /**
     * Monotonic sequence counter used to mint unique per-reservation ABA tokens.
     * <p>
     * Luciferase-7wzml.181: the previous implementation stored the raw clock millis as the ABA
     * token. Two concurrent proposals on the same bubble at the same clock tick (likely under a
     * frozen TestClock, or under coarse wall-clock granularity) would produce identical tokens,
     * allowing a rejected loser's restore to clobber the concurrent winner's live reservation.
     * Using a monotonic sequence guarantees every reservation has a unique token regardless of
     * clock granularity.
     */
    private final AtomicLong reservationSeq = new AtomicLong(0);

    /**
     * Track last topology change per bubble.
     * <p>
     * Key: bubbleId, Value: the {@link CooldownEntry} written by the most-recent reservation,
     * carrying both the unique token (ABA guard, Luciferase-7wzml.181) and the clock timestamp
     * used for cooldown-elapsed arithmetic.
     */
    private final ConcurrentHashMap<UUID, CooldownEntry> lastChangeTimestamps = new ConcurrentHashMap<>();

    /**
     * Underlying consensus protocol for Byzantine fault tolerance.
     * <p>
     * Set via setConsensusProtocol() for dependency injection.
     */
    private ViewCommitteeConsensus consensusProtocol;

    /**
     * Creates a topology consensus coordinator with default 30-second cooldown.
     *
     * @param bubbleGrid the bubble grid for validation
     * @throws NullPointerException if bubbleGrid is null
     */
    public TopologyConsensusCoordinator(TetreeBubbleGrid bubbleGrid) {
        this(bubbleGrid, DEFAULT_COOLDOWN_MS);
    }

    /**
     * Creates a topology consensus coordinator with specified cooldown.
     *
     * @param bubbleGrid     the bubble grid for validation
     * @param cooldownMillis cooldown period in milliseconds
     * @throws NullPointerException     if bubbleGrid is null
     * @throws IllegalArgumentException if cooldownMillis is negative
     */
    public TopologyConsensusCoordinator(TetreeBubbleGrid bubbleGrid, long cooldownMillis) {
        this.bubbleGrid = Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("cooldownMillis must be non-negative: " + cooldownMillis);
        }
        this.cooldownMillis = cooldownMillis;
        this.clock = Clock.system();
    }

    /**
     * Sets the clock for deterministic simulation time.
     * <p>
     * IMPORTANT: Use this for PrimeMover integration. The clock should be
     * injected to ensure simulated time is used instead of wall-clock time.
     *
     * @param clock the clock implementation
     * @throws NullPointerException if clock is null
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Sets the underlying consensus protocol.
     * <p>
     * Dependency injection for ViewCommitteeConsensus.
     *
     * @param protocol the consensus protocol
     * @throws NullPointerException if protocol is null
     */
    public void setConsensusProtocol(ViewCommitteeConsensus protocol) {
        this.consensusProtocol = Objects.requireNonNull(protocol, "consensusProtocol must not be null");
    }

    /**
     * Requests consensus for a topology change proposal.
     * <p>
     * Performs three-stage validation:
     * <ol>
     *   <li>Cooldown check: Reject if too recent (prevents oscillation)</li>
     *   <li>Pre-validation: Reject Byzantine proposals before voting</li>
     *   <li>Consensus voting: Submit to ViewCommitteeConsensus for BFT approval</li>
     * </ol>
     * <p>
     * If approved, updates cooldown timestamp for affected bubbles.
     *
     * @param proposal the topology change proposal
     * @return CompletableFuture<Boolean> - true if approved, false if rejected
     * @throws NullPointerException  if proposal is null
     * @throws IllegalStateException if consensusProtocol not set
     */
    public CompletableFuture<Boolean> requestConsensus(TopologyProposal proposal) {
        Objects.requireNonNull(proposal, "proposal must not be null");

        if (consensusProtocol == null) {
            throw new IllegalStateException("consensusProtocol not set - call setConsensusProtocol()");
        }

        // Stage 1: Atomically test-and-reserve the cooldown for all affected bubbles.
        //
        // Luciferase-0frcy.43/.44: the previous design read the cooldown (canProposeTopologyChange)
        // and wrote it (updateCooldownTimestamps) as two separate, non-atomic ConcurrentHashMap
        // operations. Two threads proposing changes for the same bubble could both pass the read
        // before either performed the write, bypassing the cooldown entirely and allowing
        // simultaneous split/merge on the same bubble. We now reserve the cooldown atomically via
        // compute() test-and-set at the start of the request. A losing concurrent proposal is
        // rejected here. If a later stage (validation or consensus) rejects this proposal, we
        // restore the prior timestamps so a rejected proposal does not impose a spurious cooldown.
        var priorTimestamps = tryReserveCooldown(proposal);
        if (priorTimestamps == null) {
            log.debug("Proposal {} rejected: cooldown period not elapsed", proposal.proposalId());
            return CompletableFuture.completedFuture(false);
        }

        // Stage 2: Pre-validate proposal (Byzantine rejection)
        var validationResult = proposal.validate(bubbleGrid);
        if (!validationResult.isValid()) {
            log.debug("Proposal {} rejected: pre-validation failed: {}",
                     proposal.proposalId(), validationResult.reason());
            restoreCooldown(proposal, priorTimestamps);
            return CompletableFuture.completedFuture(false);
        }

        // Stage 3: Distributed Byzantine fault-tolerant consensus voting.
        // Pre-validation (Stage 2) only rejects locally-detectable Byzantine inputs;
        // it is NOT a substitute for quorum agreement. A topology change must be
        // approved by the committee via ViewCommitteeConsensus before it takes effect,
        // otherwise any single node could unilaterally split/merge/move a bubble.
        log.debug("Submitting valid proposal {} to committee consensus: type={}, view={}, timestamp={}",
                 proposal.proposalId(),
                 proposal.getClass().getSimpleName(),
                 proposal.viewId(),
                 proposal.timestamp());

        return consensusProtocol.requestConsensus(toMigrationProposal(proposal))
                                .handle((approved, ex) -> {
                                    if (ex == null && Boolean.TRUE.equals(approved)) {
                                        // Cooldown was already reserved atomically in Stage 1;
                                        // approval makes the reservation final.
                                        log.info("Topology proposal {} approved by committee consensus",
                                                 proposal.proposalId());
                                        return true;
                                    }
                                    // Rejected (or consensus errored): release the reservation so a
                                    // rejected proposal does not impose a spurious cooldown.
                                    restoreCooldown(proposal, priorTimestamps);
                                    if (ex != null) {
                                        log.warn("Topology proposal {} consensus errored: {}",
                                                 proposal.proposalId(), ex.getMessage());
                                    } else {
                                        log.info("Topology proposal {} rejected by committee consensus",
                                                 proposal.proposalId());
                                    }
                                    return false;
                                });
    }

    /**
     * Adapts a {@link TopologyProposal} to the consensus layer's
     * {@link MigrationProposal} envelope for committee voting.
     * <p>
     * The consensus layer votes on opaque proposal identity (proposalId + viewId +
     * timestamp); the topology-specific payload is validated locally in Stage 2.
     * The affected bubble is mapped to source/target node identities so the
     * proposal is well-formed for the voting protocol.
     * <p>
     * <b>Known limitation (Luciferase-vhbw3):</b> the source/target node identities
     * here are {@link #digestOf(UUID) digests derived from bubble UUIDs}, which are
     * NOT Fireflies member IDs. A live {@code ViewCommitteeConsensus.validateProposal}
     * gates on {@code isNodeInView(...)} against the actual Fireflies membership view,
     * so a bubble-UUID-derived digest will be rejected as "node not in view". The
     * mapping is therefore only sound against a test/mock consensus that does not
     * enforce membership. Wiring real Fireflies member IDs through this envelope is
     * tracked by bead Luciferase-vhbw3.
     *
     * @param proposal the topology proposal to wrap
     * @return a migration proposal carrying the same identity for committee voting
     */
    private MigrationProposal toMigrationProposal(TopologyProposal proposal) {
        var affected = getAffectedBubbles(proposal);
        var primary = affected.get(0);
        var entityId = primary;
        var sourceNode = digestOf(primary);
        var targetNode = affected.size() > 1 ? digestOf(affected.get(1)) : digestOf(proposal.proposalId());
        return new MigrationProposal(proposal.proposalId(), entityId, sourceNode, targetNode,
                                     proposal.viewId(), proposal.timestamp());
    }

    /**
     * Derives a stable {@link Digest} identity from a UUID for the consensus envelope.
     *
     * @param id the source identifier
     * @return a deterministic digest of the identifier
     */
    private static Digest digestOf(UUID id) {
        return DigestAlgorithm.DEFAULT.digest(id.toString());
    }

    /**
     * Checks if a topology change can be proposed (cooldown elapsed).
     * <p>
     * Verifies that sufficient time has passed since the last topology change
     * for bubbles affected by this proposal.
     *
     * @param proposal the topology change proposal
     * @return true if cooldown period elapsed for all affected bubbles
     */
    public boolean canProposeTopologyChange(TopologyProposal proposal) {
        long now = clock.currentTimeMillis();
        var affectedBubbles = getAffectedBubbles(proposal);

        for (var bubbleId : affectedBubbles) {
            var lastEntry = lastChangeTimestamps.get(bubbleId);

            // If no prior change, allow the proposal (no cooldown yet)
            if (lastEntry == null) {
                continue;
            }

            var elapsed = now - lastEntry.timestamp();

            if (elapsed < cooldownMillis) {
                log.debug("Bubble {} still in cooldown: elapsed={}ms, required={}ms",
                         bubbleId, elapsed, cooldownMillis);
                return false;
            }
        }

        return true;
    }

    /**
     * Gets the bubbles affected by a topology proposal.
     * <p>
     * Used for cooldown tracking.
     *
     * @param proposal the topology change proposal
     * @return list of affected bubble IDs
     */
    private java.util.List<UUID> getAffectedBubbles(TopologyProposal proposal) {
        return switch (proposal) {
            case SplitProposal split -> java.util.List.of(split.sourceBubble());
            case MergeProposal merge -> java.util.List.of(merge.bubble1(), merge.bubble2());
            case MoveProposal move -> java.util.List.of(move.sourceBubble());
        };
    }

    /**
     * Atomically tests and reserves the cooldown for every bubble affected by a proposal.
     * <p>
     * For each affected bubble, performs a single {@link ConcurrentHashMap#compute} test-and-set:
     * if the bubble is still within its cooldown window the prior value is left untouched and
     * the reservation fails; otherwise a <em>unique per-reservation token</em> (from
     * {@link #reservationSeq}) is written, claiming the slot for this caller. This closes the
     * check-then-act race (Luciferase-0frcy.43/.44): two concurrent callers racing on the same
     * bubble cannot both succeed because {@code compute} serializes per-key.
     * <p>
     * The unique token (not the raw clock millis) is the ABA guard
     * (Luciferase-7wzml.181): because every reservation writes a distinct token,
     * {@link #restoreCooldown} can reliably distinguish "we still own this slot" from
     * "a concurrent winner has already claimed it" even when two proposals share the
     * same clock millis (e.g. under a frozen TestClock).
     * <p>
     * If a multi-bubble proposal (e.g. merge) fails to reserve a later bubble, the already-claimed
     * bubbles are restored to their prior values and the method returns {@code null}.
     *
     * @param proposal the topology change proposal
     * @return a {@link CooldownReservation} recording the unique token written and the prior slot
     *         values for every reserved bubble, to support restoration on later rejection;
     *         {@code null} if the reservation could not be acquired (cooldown still active for
     *         some bubble)
     */
    private CooldownReservation tryReserveCooldown(TopologyProposal proposal) {
        long now = clock.currentTimeMillis();
        // Unique token for this reservation — distinct from any concurrent proposal's token
        // regardless of clock granularity (Luciferase-7wzml.181 ABA fix).
        long token = reservationSeq.incrementAndGet();
        var affectedBubbles = getAffectedBubbles(proposal);
        var priorEntries = new java.util.LinkedHashMap<UUID, CooldownEntry>();

        for (var bubbleId : affectedBubbles) {
            // Box to detect "reservation refused" without ambiguity.
            var refused = new boolean[]{false};
            var prior = new CooldownEntry[]{null};
            lastChangeTimestamps.compute(bubbleId, (id, last) -> {
                if (last != null && (now - last.timestamp()) < cooldownMillis) {
                    refused[0] = true;
                    return last; // still in cooldown — leave unchanged
                }
                prior[0] = last;
                return new CooldownEntry(token, now); // claim the cooldown for this caller
            });

            if (refused[0]) {
                log.debug("Bubble {} still in cooldown; reservation refused for proposal {}",
                         bubbleId, proposal.proposalId());
                // Roll back any reservations already made for this proposal. Restore only if
                // the current slot value carries our unique token (ABA-safe).
                restoreCooldown(new CooldownReservation(priorEntries, token));
                return null;
            }
            priorEntries.put(bubbleId, prior[0]);
        }

        return new CooldownReservation(priorEntries, token);
    }

    /**
     * Restores cooldown entries to the values captured before a reservation, used when a
     * reserved proposal is subsequently rejected (validation or consensus). A {@code null} prior
     * value means there was no entry before the reservation, so it is removed.
     *
     * @param proposal    the proposal whose reservation is being released (unused beyond doc)
     * @param reservation the reservation captured by {@link #tryReserveCooldown}
     */
    private void restoreCooldown(TopologyProposal proposal, CooldownReservation reservation) {
        restoreCooldown(reservation);
    }

    /**
     * Conditionally reverts each reserved bubble's entry to its prior value. The revert only
     * happens if the bubble's current entry carries <em>this reservation's unique token</em>;
     * otherwise a concurrent winner has since claimed the slot and we leave it untouched.
     * <p>
     * Luciferase-7wzml.181 ABA fix: comparing by unique token (not by clock millis) ensures that
     * two concurrent proposals issued at the same clock tick produce distinct tokens, so a rejected
     * loser's restore cannot mistake the concurrent winner's live entry as its own and clobber it.
     * The {@code compute} callback serializes per key, so the compare-and-revert is atomic.
     */
    private void restoreCooldown(CooldownReservation reservation) {
        long token = reservation.token();
        for (var entry : reservation.priorEntries().entrySet()) {
            var bubbleId = entry.getKey();
            var prior = entry.getValue();
            lastChangeTimestamps.compute(bubbleId, (id, current) -> {
                if (current == null || current.token() != token) {
                    // A concurrent reservation overwrote our slot (or it was already restored);
                    // leave the live value untouched.
                    return current;
                }
                return prior; // null prior maps to removal (compute removes on null return)
            });
        }
    }

    /**
     * A held cooldown reservation: the prior entries to restore on rejection (keyed by bubbleId,
     * null value = no prior entry), plus {@code token} — the unique sequence value written into
     * every reserved bubble's slot by this reservation.
     * <p>
     * Luciferase-7wzml.181: {@code token} is minted from {@link #reservationSeq} (not from the
     * clock) so it is globally unique per reservation regardless of clock granularity. {@link
     * #restoreCooldown} only reverts a slot whose current entry still carries this token, ensuring
     * a rejected loser never clobbers a concurrent winner's live reservation even when both ran
     * at the same clock millisecond.
     */
    private record CooldownReservation(java.util.Map<UUID, CooldownEntry> priorEntries, long token) {
    }

    /**
     * An entry in {@link #lastChangeTimestamps}: pairs a unique per-reservation {@code token} with
     * the clock {@code timestamp} at which the reservation was made.
     * <p>
     * The {@code token} is the ABA guard (Luciferase-7wzml.181); the {@code timestamp} is used for
     * cooldown-elapsed arithmetic in {@link #canProposeTopologyChange} and {@link
     * #getRemainingCooldown}.
     */
    private record CooldownEntry(long token, long timestamp) {
    }

    /**
     * Gets the remaining cooldown time for a bubble.
     * <p>
     * Used for diagnostics and testing.
     *
     * @param bubbleId the bubble identifier
     * @return remaining cooldown milliseconds (0 if cooldown elapsed)
     */
    public long getRemainingCooldown(UUID bubbleId) {
        var lastEntry = lastChangeTimestamps.get(bubbleId);

        // If no prior change, no cooldown remaining
        if (lastEntry == null) {
            return 0L;
        }

        long now = clock.currentTimeMillis();
        var elapsed = now - lastEntry.timestamp();
        var remaining = cooldownMillis - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * Resets cooldown tracking.
     * <p>
     * Used for testing and cleanup.
     */
    public void reset() {
        lastChangeTimestamps.clear();
    }
}
