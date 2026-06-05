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

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.google.protobuf.Empty;
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeMigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeServiceGrpc;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeVote;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.QuorumAchieved;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC service implementation for committee-based consensus voting.
 *
 * Handles:
 * - Incoming migration proposals from proposer nodes
 * - Incoming votes from committee members
 * - Query results for completed proposals
 *
 * Converts proto messages to domain objects and delegates to ViewCommitteeConsensus and CommitteeVotingProtocol.
 *
 * Phase 7G Day 4: Proto & P2P Communication Layer
 *
 * @author hal.hildebrand
 */
public class CommitteeServiceImpl extends CommitteeServiceGrpc.CommitteeServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(CommitteeServiceImpl.class);

    /**
     * Default TTL for completed proposal results. Entries not consumed via
     * getQuorumResult within this window are evicted lazily on the next put or get
     * operation. 60 seconds is generous for any reasonable RPC round-trip.
     *
     * <p><strong>Honest bound.</strong> Eviction is <em>lazy-only</em>: it is triggered by
     * incoming RPCs ({@code submitMigrationProposal} and {@code getQuorumResult}), not by a
     * background scheduler thread. On a fully-idle node, stale entries persist past this TTL
     * until the next RPC arrives. This is acceptable for the production context where
     * committee nodes receive a continuous stream of proposals; an idle node holds no live
     * proposal traffic and its stale entries are harmless. The deliberate trade-off avoids a
     * background thread with its associated lifecycle, locking, and shutdown complexity.
     */
    static final long RESULT_TTL_MS = 60_000L;

    /**
     * Maximum number of pending results. When exceeded, the oldest entries are
     * evicted before inserting the new one.
     *
     * <p><strong>Honest bound.</strong> The steady-state size is bounded by
     * {@code MAX_RESULTS + C}, where C is the number of concurrent {@code put} operations
     * that race with an in-progress eviction pass. The TTL pass and the size-cap pass inside
     * {@link #evictStaleAndOversize()} run without a global lock, so a burst of concurrent
     * completions can transiently push the map above this limit by at most the number of
     * in-flight concurrent puts during that pass. In practice C is small (bounded by the
     * gRPC executor's thread count), so the effective overrun is a constant addend, not an
     * unbounded growth. True hard-cap enforcement would require a lock across put + size-check,
     * which would serialize all proposal completions and is not warranted here.
     */
    static final int MAX_RESULTS = 1000;

    private final ViewCommitteeConsensus consensus;
    private final CommitteeVotingProtocol votingProtocol;

    // Cache for proposal results (for GetQuorumResult queries).
    // Values are (Boolean result, long completedAtMs) pairs encoded as a single record.
    // Package-private so CommitteeServiceImplTest can inspect the cache directly.
    final ConcurrentHashMap<String, ResultEntry> proposalResults = new ConcurrentHashMap<>();

    private volatile Clock clock = Clock.system();

    public CommitteeServiceImpl(ViewCommitteeConsensus consensus, CommitteeVotingProtocol votingProtocol) {
        this.consensus = consensus;
        this.votingProtocol = votingProtocol;
    }

    /**
     * Injects a clock for deterministic testing.
     *
     * @param clock the clock to use; must not be null
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Immutable (result, completedAtMs) pair stored per proposal.
     */
    record ResultEntry(boolean result, long completedAtMs) {
    }

    @Override
    public void submitMigrationProposal(CommitteeMigrationProposal proto, StreamObserver<Empty> responseObserver) {
        try {
            // Convert proto to domain object
            var proposal = CommitteeProtoConverter.fromProto(proto);
            var proposerAddress = CommitteeProtoConverter.getProposerAddress(proto);

            log.debug("Received migration proposal: proposalId={}, entity={}, proposer={}",
                     proposal.proposalId(), proposal.entityId(), proposerAddress);

            // Process proposal via consensus orchestrator
            var resultFuture = consensus.requestConsensus(proposal);

            // Store proposal ID for later result lookup
            resultFuture.whenComplete((result, ex) -> {
                if (ex == null) {
                    evictStaleAndOversize();
                    proposalResults.put(proposal.proposalId().toString(),
                                        new ResultEntry(result, clock.currentTimeMillis()));
                    log.debug("Proposal {} consensus result: {}", proposal.proposalId(), result);
                } else {
                    log.warn("Proposal {} consensus failed", proposal.proposalId(), ex);
                }
            });

            // Send empty response immediately (voting happens asynchronously)
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error processing migration proposal", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void submitVote(CommitteeVote proto, StreamObserver<Empty> responseObserver) {
        try {
            // Convert proto to domain object
            var vote = CommitteeProtoConverter.fromProto(proto);

            log.debug("Received vote: proposalId={}, voter={}, approved={}, viewId={}",
                     vote.proposalId(), vote.voterId(), vote.approved(), vote.viewId());

            // Submit vote to voting protocol
            votingProtocol.recordVote(vote);

            // Send empty response
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error processing vote", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void getQuorumResult(CommitteeMigrationProposal proto, StreamObserver<QuorumAchieved> responseObserver) {
        try {
            var proposal = CommitteeProtoConverter.fromProto(proto);
            var proposalId = proposal.proposalId().toString();

            // Lazily evict stale entries on each read, then atomically claim the result.
            // ConcurrentHashMap.remove() is a single atomic get-and-remove: exactly one
            // concurrent caller wins a non-null entry; all others fall through to the
            // "not yet available" path below. This closes the H2 double-delivery race
            // where two callers could both see the entry via get() and both emit
            // onNext()+onCompleted() on the same StreamObserver contract.
            evictStaleAndOversize();
            var entry = proposalResults.remove(proposalId);
            if (entry != null) {
                var response = QuorumAchieved.newBuilder()
                    .setProposalId(proposalId)
                    .setResult(entry.result())
                    .setViewId(CommitteeProtoConverter.digestToHex(proposal.viewId()))
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } else {
                // Result not yet available
                responseObserver.onError(new RuntimeException("Proposal result not yet available: " + proposalId));
            }

        } catch (Exception e) {
            log.error("Error querying quorum result", e);
            responseObserver.onError(e);
        }
    }

    /**
     * Clear cached results (useful for testing).
     */
    public void clearResultCache() {
        proposalResults.clear();
    }

    /**
     * Get number of cached results (for testing).
     */
    public int getCachedResultCount() {
        return proposalResults.size();
    }

    /**
     * Seed a result entry directly (for testing only). Package-private so
     * tests in the same package can populate the cache without going through
     * the full gRPC/proto submission path.
     */
    void putResultEntry(String proposalId, ResultEntry entry) {
        proposalResults.put(proposalId, entry);
    }

    /**
     * Check whether a proposal id is still in the cache (for testing only).
     */
    boolean hasResultEntry(String proposalId) {
        return proposalResults.containsKey(proposalId);
    }

    /**
     * Lazily evict entries that are either older than {@link #RESULT_TTL_MS} or
     * that push the map beyond {@link #MAX_RESULTS}.
     *
     * <p>TTL pass: a single iterator scan removes expired entries in O(n).
     * Size cap pass: if the map is still oversized after TTL eviction, remove
     * entries until we are under the cap, preferring the oldest by completedAtMs.
     * The oldest-first preference is best-effort (no global sort) — we simply
     * scan and evict until the cap is met, which is sufficient to bound growth.
     *
     * <p><strong>Non-atomic, best-effort eviction.</strong> Both passes run without a
     * global lock. Concurrent {@code put} operations from the gRPC executor thread pool
     * may race with either pass: a {@code put} completing between the TTL pass and the
     * size-cap pass can cause the map to transiently exceed {@code MAX_RESULTS} by the
     * number of concurrent puts active at that moment. See {@link #MAX_RESULTS} for the
     * quantified honest bound. This is intentional: a global lock would serialize all
     * proposal completions on the gRPC executor, which is a worse trade-off than the
     * bounded transient overrun.
     *
     * <p>This method is triggered on every {@code submitMigrationProposal} completion and
     * every {@code getQuorumResult} call. There is no background sweep thread; see
     * {@link #RESULT_TTL_MS} for the lazy-eviction implication on idle nodes.
     */
    private void evictStaleAndOversize() {
        long cutoff = clock.currentTimeMillis() - RESULT_TTL_MS;

        // TTL pass
        Iterator<Map.Entry<String, ResultEntry>> it = proposalResults.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().completedAtMs() <= cutoff) {
                it.remove();
                log.debug("Evicted stale proposal result: key={}", entry.getKey());
            }
        }

        // Size-cap pass: evict oldest entries first until under MAX_RESULTS.
        // Snapshot size once so a concurrent put between the guard check and the
        // limit() call cannot produce a negative limit (M3 fix).
        long excess = (long) proposalResults.size() - MAX_RESULTS;
        if (excess > 0) {
            proposalResults.entrySet().stream()
                           .sorted(Map.Entry.comparingByValue(
                               (a, b) -> Long.compare(a.completedAtMs(), b.completedAtMs())))
                           .limit(excess)
                           .forEach(e -> proposalResults.remove(e.getKey()));
        }
    }
}
