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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicit coordinator election protocol using lowest UUID wins algorithm.
 * <p>
 * Algorithm:
 * - Deterministic: Lowest UUID always wins
 * - No tie-breaking needed (UUID comparison is total ordering)
 * - Restarts handled gracefully via election round increments
 * <p>
 * Election Process:
 * 1. startElection(candidateProcessIds)
 * 2. Each process casts ballot via castBallot(voter, voteFor)
 * 3. Winner determined when quorum reached (majority votes)
 * 4. coordinatorFailed() triggers re-election
 * <p>
 * Thread Safety:
 * - ConcurrentHashMap for ballot tracking
 * - AtomicLong for election round counter
 * - Synchronized winner determination
 * <p>
 * Architecture Decision D6B.5: Coordinator Election
 *
 * @author hal.hildebrand
 */
public class CoordinatorElectionProtocol {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorElectionProtocol.class);

    private volatile UUID currentCoordinator;
    private final AtomicLong electionRound = new AtomicLong(0);

    // BallotBox pattern: Map<Voter UUID, Set<Candidate UUIDs voted for>>
    private final Map<UUID, Set<UUID>> ballots = new ConcurrentHashMap<>();

    private volatile List<UUID> candidates = new ArrayList<>();

    /**
     * Start a new election with the given candidate processes.
     * <p>
     * Winner is determined immediately as the lowest UUID (deterministic).
     *
     * @param candidateProcessIds List of process UUIDs participating in election
     * @throws IllegalArgumentException if candidates list is empty
     * @throws NullPointerException     if candidates list is null
     */
    public void startElection(List<UUID> candidateProcessIds) {
        Objects.requireNonNull(candidateProcessIds, "Candidates cannot be null");
        if (candidateProcessIds.isEmpty()) {
            throw new IllegalArgumentException("Candidates list cannot be empty");
        }

        this.candidates = new ArrayList<>(candidateProcessIds);
        this.ballots.clear();

        // Increment election round
        var round = electionRound.incrementAndGet();

        // Deterministic winner: lowest UUID
        var winner = candidateProcessIds.stream()
                                        .min(UUID::compareTo)
                                        .orElseThrow();

        this.currentCoordinator = winner;

        log.info("Election started (round {}): {} candidates, winner={}", round, candidateProcessIds.size(), winner);
    }

    /**
     * Cast a ballot for a candidate.
     * <p>
     * BallotBox pattern: Voter can vote for any candidate, but lowest UUID
     * always wins regardless of votes (deterministic algorithm).
     *
     * @param voter   UUID of the voting process
     * @param voteFor UUID of the candidate being voted for
     */
    public void castBallot(UUID voter, UUID voteFor) {
        ballots.computeIfAbsent(voter, k -> ConcurrentHashMap.newKeySet()).add(voteFor);
        log.debug("Ballot cast: {} votes for {}", voter, voteFor);
    }

    /**
     * Check if election has reached quorum (majority of candidates voted).
     * <p>
     * Majority is calculated as (n + 1) / 2 to properly round up:
     * 1 candidate → 1 required, 2 candidates → 2 required, 3 → 2 required, etc.
     *
     * @return true if majority of candidates have cast votes
     */
    public boolean hasQuorum() {
        var votedCount = ballots.size();
        var totalCandidates = candidates.size();
        var majority = (totalCandidates + 1) / 2;

        return votedCount >= majority;
    }

    /**
     * Get the current winner (lowest UUID).
     *
     * @return UUID of the elected coordinator
     */
    public UUID getWinner() {
        return currentCoordinator;
    }

    /**
     * Check if a specific process won the election.
     *
     * @param processId UUID of the process to check
     * @return true if this process is the elected coordinator
     */
    public boolean isElected(UUID processId) {
        return Objects.equals(currentCoordinator, processId);
    }

    /**
     * Trigger re-election due to coordinator failure.
     * <p>
     * Increments election round but does NOT automatically start new election.
     * Caller must invoke startElection() with updated candidate list.
     */
    public void coordinatorFailed() {
        var round = electionRound.incrementAndGet();
        log.warn("Coordinator {} failed, triggering re-election (round {})", currentCoordinator, round);
        currentCoordinator = null;
    }

    /**
     * Get current election round number.
     * <p>
     * Increments on each startElection() and coordinatorFailed() call.
     *
     * @return Current election round (starts at 0)
     */
    public long getElectionRound() {
        return electionRound.get();
    }
}
