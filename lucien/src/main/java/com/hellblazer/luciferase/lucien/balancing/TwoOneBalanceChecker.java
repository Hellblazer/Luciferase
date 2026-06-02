/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.pyramid.PyramidKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detects 2:1 balance constraint violations at partition boundaries.
 *
 * <p>The 2:1 balance constraint requires that adjacent elements differ by at most 1 level
 * in the spatial hierarchy. This checker finds all violations by examining ghost elements
 * (non-local boundary elements) and comparing their levels with neighboring local elements.
 *
 * <p>When a violation is detected (level difference > 1), a refinement request is generated
 * to ask the ghost's source partition for refined (subdivided) elements.
 *
 * @param <Key> spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> entity identifier type
 * @param <Content> entity content type
 *
 * @author hal.hildebrand
 */
public class TwoOneBalanceChecker<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(TwoOneBalanceChecker.class);

    /**
     * Local refinement set (Luciferase-uhsn, B10b — D3). Violations where the LOCAL element is the coarser side
     * ({@link BalanceViolation#localNeedsRefinement()}) are NOT sent to a remote partition; the local partition must
     * subdivide its own element. Such localKeys are accumulated here by {@link #createRefinementRequests} and drained
     * by the downstream local-refinement step (m27q/B10c, which owns the actual {@code SpatialIndex} subdivide).
     *
     * <p><b>Deduplicated by design.</b> Until m27q subdivides between rounds, the same local-coarser violation
     * reappears in {@code findViolations()} every round and would be enqueued repeatedly; a coarse element bordering
     * several finer ghosts likewise yields the same localKey more than once in a single round. A set collapses both
     * to one subdivide request, so {@link #drainLocalRefinements} never returns duplicates regardless of how many
     * rounds run before the queue is drained.
     */
    private final java.util.Set<Key> localRefinementQueue = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Record representing a 2:1 balance constraint violation.
     *
     * <p>A violation occurs when a local element and adjacent ghost element differ by more than 1 level.
     *
     * @param localKey local element key
     * @param ghostKey ghost element key
     * @param localLevel local element level in spatial hierarchy
     * @param ghostLevel ghost element level
     * @param levelDifference abs(localLevel - ghostLevel), must be > 1
     * @param sourceRank rank of partition owning the ghost element
     */
    public record BalanceViolation<K extends SpatialKey<K>>(
        K localKey,
        K ghostKey,
        int localLevel,
        int ghostLevel,
        int levelDifference,
        int sourceRank
    ) {
        /**
         * Constructor with validation.
         *
         * @throws IllegalArgumentException if levelDifference <= 1 (not a violation)
         */
        public BalanceViolation {
            if (levelDifference <= 1) {
                throw new IllegalArgumentException(
                    "Level difference must be > 1 to be a violation, got " + levelDifference);
            }
        }

        /**
         * Determine which side of the violation needs refinement.
         *
         * @return true if local element needs refinement (is coarser), false if ghost needs refinement
         */
        public boolean localNeedsRefinement() {
            return localLevel < ghostLevel;
        }
    }

    /**
     * Find all 2:1 balance violations in the ghost layer.
     *
     * <p>Iterates through all ghost elements and checks for level violations with local elements.
     * For each ghost, checks its neighboring local elements for level differences > 1.
     *
     * @param ghostLayer ghost elements to check (non-local boundary elements from adjacent partitions)
     * @param forest local forest containing local elements
     * @return list of violations found (empty if none)
     * @throws IllegalArgumentException if ghostLayer or forest is null
     * @implNote MortonKey ghosts are probed in both directions: coarser ancestors (single masked-ancestor
     *           cell) AND finer descendants (the full descendant code range via {@code spatialKeysInRange};
     *           exhaustive as of Luciferase-a5nd). TetreeKey / PyramidKey ghosts (RDR-010 pi1.6) are routed
     *           through the {@link NeighborDetector} and probe only the <em>coarser</em> direction
     *           (ghost-fine / local-coarse): the ghost-coarse / local-fine arrangement is NOT probed locally
     *           for these key types, because they have no level-aware descendant-enumeration primitive (no
     *           {@code neighbor(Direction)} / {@code spatialKeysInRange} descent). Detecting it relies on the
     *           partition that owns the finer element running its own boundary-ghost balance check — an assumed
     *           distributed-protocol invariant. This is a deliberate asymmetry: Morton exhaustively probes
     *           finer locally; Tet/Pyramid do not. Unhandled key types are logged, never silently dropped.
     */
    public List<BalanceViolation<Key>> findViolations(
        GhostLayer<Key, ID, Content> ghostLayer,
        Forest<Key, ID, Content> forest
    ) {
        if (ghostLayer == null) {
            throw new IllegalArgumentException("ghostLayer cannot be null");
        }
        if (forest == null) {
            throw new IllegalArgumentException("forest cannot be null");
        }

        List<BalanceViolation<Key>> violations = new ArrayList<>();

        // Iterate through all ghost elements
        for (var ghost : ghostLayer.getAllGhostElements()) {
            // Get ghost spatial key and level
            var ghostKey = ghost.getSpatialKey();
            int ghostLevel = ghostKey.getLevel();

            log.debug("Checking ghost element: key={}, level={}", ghostKey, ghostLevel);

            // Check each neighbor of this ghost element
            // For MortonKey, use Direction-based neighbor iteration
            if (ghostKey instanceof MortonKey mortonGhost) {
                checkMortonNeighborsForViolations(mortonGhost, ghostLevel, forest,
                                                 ghost.getOwnerRank(), violations);
            } else if (ghostKey instanceof TetreeKey<?> || ghostKey instanceof PyramidKey) {
                // RDR-010 pi1.6: tetrahedral / pyramid (cross-shape) neighbor topology via the wired
                // NeighborDetector — closes the silent-skip gap for non-Morton keys (Approach §4d).
                checkDetectorNeighborsForViolations(ghostKey, ghostLevel, forest,
                                                    ghost.getOwnerRank(), violations);
            } else {
                log.warn("No balance-neighbor strategy for ghost key type {}; skipping (would silently "
                         + "miss violations — file a follow-on if this key type needs balance checking)",
                         ghostKey.getClass().getSimpleName());
            }
        }

        log.debug("Found {} violations in ghost layer with {} ghost elements",
                 violations.size(), ghostLayer.getNumGhostElements());

        return violations;
    }

    /**
     * Check MortonKey neighbors for 2:1 balance violations.
     */
    @SuppressWarnings("unchecked")
    private void checkMortonNeighborsForViolations(
        MortonKey ghostKey,
        int ghostLevel,
        Forest<Key, ID, Content> forest,
        int sourceRank,
        List<BalanceViolation<Key>> violations
    ) {
        int neighborsChecked = 0;
        int neighborsFound = 0;
        final int maxLevel = com.hellblazer.luciferase.lucien.Constants.getMaxRefinementLevel();

        // Iterate through all possible directions
        for (var direction : MortonKey.Direction.values()) {
            MortonKey neighborAtSameLevel = ghostKey.neighbor(direction);
            if (neighborAtSameLevel == null) continue;

            // Get the Morton code of the neighbor position (independent of level)
            long neighborMortonCode = neighborAtSameLevel.getMortonCode();

            // Level-scan pruning (Luciferase-kd79): a 2:1 violation requires |localLevel - ghostLevel| > 1, so
            // levels within 1 of the ghost (ghostLevel-1, ghostLevel, ghostLevel+1) can never be violations.
            // Scan only [0, ghostLevel-2] U [ghostLevel+2, 21]; the skipped levels are behavior-equivalent (they
            // would find an element but add no violation). This bounds the per-direction scan to the two
            // violating bands instead of all 22 levels.
            //
            // COARSE-BAND ANCESTOR CODE (Luciferase-3aut item 1, fixed): MortonKey stores ABSOLUTE Morton
            // codes (the low 3*(maxLevel-level) bits of a level-`level` cell are zero — see
            // MortonKey.fromCoordinates / Constants.calculateMortonIndex). To probe whether a COARSER local
            // cell occupies this neighbor position, mask the neighbor's fine code down to that coarse cell's
            // origin; the full fine code at a coarse level is NOT a valid stored key and matched only in the
            // rare cell-aligned case, so coarse-local violations were previously missed.
            //
            // NOTE the bead's proposed `neighborMortonCode >>> (3*(ghostLevel-C))` is the *level-relative*
            // (right-justified) convention and does NOT match the absolute codes this index stores (verified:
            // for an absolute code the coarse ancestor is obtained by masking, not right-shifting — the same
            // mismatch makes MortonKey.parent()'s `>> 3` wrong for level < maxLevel; tracked in Luciferase-3avp).
            //
            // FINER-BAND DESCENDANT RANGE (Luciferase-a5nd, fixed): the finer band [ghostLevel+2, maxLevel]
            // previously probed only the neighbor code unchanged (the first-octant / min-corner descendant), so
            // a finer local element anywhere else inside the neighbor cell was missed. Under MortonKey's
            // level-first ordering the descendants of one cell at a fixed level form a CONTIGUOUS code range
            // [firstDescendantAtLevel, lastDescendantAtLevel], so probe the whole range via spatialKeysInRange.
            //
            // KNOWN GAP still deferred: (3) the numTrees-axis scan (forest.getAllTrees) is not reduced: the
            // bead's floorKey/ceilingKey is infeasible under MortonKey's level-first ordering; a real bound needs
            // forest spatial routing (Luciferase-36lp).
            for (byte level = 0; level <= maxLevel; level++) {
                if (Math.abs(level - ghostLevel) <= 1) {
                    continue; // within 1 level of the ghost -> levelDiff <= 1 -> not a violation
                }
                int levelDiff = Math.abs(level - ghostLevel); // > 1 here by the prune above
                neighborsChecked++;

                if (level < ghostLevel) {
                    // COARSE band: a single coarse-ancestor cell — mask the fine code to that cell's origin.
                    int dropBits = 3 * (maxLevel - level);
                    var coarseKey = new MortonKey(neighborMortonCode & ~((1L << dropBits) - 1), level);
                    for (var tree : forest.getAllTrees()) {
                        if (tree.getSpatialIndex().containsSpatialKey((Key) coarseKey)) {
                            neighborsFound++;
                            violations.add(new BalanceViolation<>((Key) coarseKey, (Key) ghostKey, level, ghostLevel,
                                                                 levelDiff, sourceRank));
                            log.debug("Coarse-local violation: local={} vs ghost level {}", coarseKey, ghostLevel);
                            break; // one coarse ancestor cell per (level, direction)
                        }
                    }
                } else {
                    // FINER band: any descendant of the neighbor cell at this level is a violating finer element.
                    var lo = neighborAtSameLevel.firstDescendantAtLevel(level);
                    var hi = neighborAtSameLevel.lastDescendantAtLevel(level);
                    // Dedup across trees so a key present in more than one tree's index yields a single
                    // violation per (level, direction), mirroring the coarse band's one-cell-per-position rule.
                    var reported = new java.util.HashSet<Key>();
                    for (var tree : forest.getAllTrees()) {
                        var found = tree.getSpatialIndex().spatialKeysInRange((Key) lo, true, (Key) hi, true);
                        for (var localKey : found) {
                            if (reported.add(localKey)) {
                                neighborsFound++;
                                violations.add(new BalanceViolation<>(localKey, (Key) ghostKey, level, ghostLevel,
                                                                     levelDiff, sourceRank));
                            }
                        }
                        if (!found.isEmpty()) {
                            log.debug("Finer-local violation(s): {} descendant(s) at level {} vs ghost level {}",
                                     found.size(), level, ghostLevel);
                        }
                    }
                }
            }
        }

        log.debug("Checked {} neighbor positions, found {} neighbors", neighborsChecked, neighborsFound);
    }

    /**
     * Check non-Morton (Tetree / Pyramid) ghost-element neighbors for 2:1 balance violations via the
     * forest's wired {@link NeighborDetector} (RDR-010 pi1.6, Approach §4d — closes the silent-skip gap).
     *
     * <p>TetreeKey/PyramidKey have no {@code neighbor(Direction)} method (unlike MortonKey), so neighbor
     * topology comes from the shape's {@link NeighborDetector#findFaceNeighbors}. For each same-level
     * face neighbor we then walk its {@link SpatialKey#parent()} chain to the root, probing the forest at
     * each level — this mirrors the <em>coarser</em> half of the Morton level-scan (where a fine ghost
     * neighbors a coarser local element).
     *
     * <p><b>Scope (documented, not silent — matches the chosen "Morton-behavior" scope).</b> This detects
     * the ghost-fine / local-coarse arrangement. The finer arrangement (a coarse ghost neighboring a
     * finer local element) requires descending a canonical child chain, which has no key-level primitive
     * on PyramidKey; it is deferred. In a distributed forest that arrangement is still detected from the
     * partition that owns the finer element (whose own boundary ghost is checked from its side), so no
     * violation goes globally undetected — only locally, on this side, for this arrangement.
     */
    private void checkDetectorNeighborsForViolations(
        Key ghostKey,
        int ghostLevel,
        Forest<Key, ID, Content> forest,
        int sourceRank,
        List<BalanceViolation<Key>> violations
    ) {
        var detector = detectorFor(forest);
        if (detector == null) {
            // Every AbstractSpatialIndex subclass wires a detector in its constructor, so a null here is
            // unexpected and would SILENTLY skip balance checking — warn rather than hide it.
            log.warn("No neighbor detector available from forest trees; cannot balance-check ghost {} "
                     + "(its violations are silently skipped)", ghostKey);
            return;
        }

        var probed = new HashSet<Key>();
        for (var neighbor : detector.findFaceNeighbors(ghostKey)) {
            // Same-level neighbor plus its coarser ancestors (mirrors Morton's coarser level-scan).
            Key probe = neighbor;
            while (probe != null) {
                if (probed.add(probe) && forestContains(forest, probe)) {
                    int localLevel = probe.getLevel();
                    int levelDiff = Math.abs(localLevel - ghostLevel);
                    if (levelDiff > 1) {
                        violations.add(new BalanceViolation<>(probe, ghostKey, localLevel, ghostLevel,
                                                              levelDiff, sourceRank));
                        log.debug("VIOLATION (detector): local level {} vs ghost level {} (diff={})",
                                  localLevel, ghostLevel, levelDiff);
                    }
                }
                probe = probe.parent();
            }
        }
    }

    /** First non-null neighbor detector among the forest's trees (geometry is index-independent). */
    private NeighborDetector<Key> detectorFor(Forest<Key, ID, Content> forest) {
        for (var tree : forest.getAllTrees()) {
            var detector = tree.getSpatialIndex().getNeighborDetector();
            if (detector != null) {
                return detector;
            }
        }
        return null;
    }

    /** True if any tree in the forest contains {@code key} as an occupied spatial key. */
    private boolean forestContains(Forest<Key, ID, Content> forest, Key key) {
        for (var tree : forest.getAllTrees()) {
            if (tree.getSpatialIndex().containsSpatialKey(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create refinement requests from detected violations (Luciferase-w3lm, B10a).
     *
     * <p>Groups violations by the rank owning the ghost element ({@link BalanceViolation#sourceRank}) and emits one
     * refinement request per remote partition, each carrying the boundary keys and the maximum level seen in that
     * group. Adapted from {@code CrossPartitionBalancePhase.identifyRefinementNeeds} and {@code t8_forest_balance.cxx}.
     *
     * <p><b>Placeholders deferred to B10b</b> ({@code Luciferase-uhsn}, {@code RefinementCoordinator}): each request
     * is stamped {@code requesterTreeId=0L} and {@code roundNumber=0}. These are pre-coordinator placeholders — the
     * coordinator overwrites them with the partner tree id and the actual Allreduce-LAND convergence round before the
     * request goes on the wire. Do not interpret {@code roundNumber==0} from this method as a real round.
     *
     * <p><b>Refinement-direction semantics deferred to B10b/B10c:</b> {@code boundaryKeys} bundles both the local and
     * ghost key of every violation (per the B10a design and matching {@code CrossPartitionBalancePhase}). Whether the
     * remote should refine its (ghost) side, or only violations with {@code !localNeedsRefinement()} should produce a
     * remote request at all, is a wire-protocol decision owned by B10b ({@code buildRequestsForPartner}) /
     * B10c ({@code applyRefinementResponses}), not by this grouping step. See {@code Luciferase-uhsn}.
     *
     * @param violations list of balance violations to process
     * @param timestamp   current timestamp (for request metadata)
     * @param localRank   the local partition rank making the request — becomes {@link RefinementRequest#requesterRank}
     *                    so the remote knows who to reply to (NOT the coordinator/initiator rank)
     * @return list of refinement requests to send to remote partitions; empty if there are no violations
     */
    public List<RefinementRequest<Key>> createRefinementRequests(
        List<BalanceViolation<Key>> violations,
        long timestamp,
        int localRank
    ) {
        if (violations == null || violations.isEmpty()) {
            return new ArrayList<>();
        }

        // D3 (Luciferase-uhsn): partition violations by refinement direction.
        //  - LOCAL coarser (localNeedsRefinement()): the local partition must subdivide its own element. Enqueue the
        //    localKey for the downstream local-refinement step (m27q/B10c) — do NOT ask a remote partition.
        //  - GHOST coarser (!localNeedsRefinement()): the ghost-owning partition is the coarse side that must refine,
        //    so a remote request to its sourceRank is warranted. Only these reach the wire.
        var remoteViolations = new ArrayList<BalanceViolation<Key>>(violations.size());
        for (var v : violations) {
            if (v.localNeedsRefinement()) {
                localRefinementQueue.add(v.localKey());
            } else {
                remoteViolations.add(v);
            }
        }

        var byRank = remoteViolations.stream().collect(Collectors.groupingBy(BalanceViolation::sourceRank));

        var requests = new ArrayList<RefinementRequest<Key>>(byRank.size());
        for (var group : byRank.values()) {
            int treeLevel = 0;
            var boundaryKeys = new ArrayList<Key>(group.size() * 2);
            for (var v : group) {
                treeLevel = Math.max(treeLevel, Math.max(v.localLevel(), v.ghostLevel()));
                boundaryKeys.add(v.localKey());
                boundaryKeys.add(v.ghostKey());
            }
            requests.add(new RefinementRequest<>(localRank, 0L, 0, treeLevel, boundaryKeys, timestamp));
        }
        return requests;
    }

    /**
     * Drain the local refinement queue (Luciferase-uhsn, B10b — D3).
     *
     * <p>Returns and removes all localKeys queued by {@link #createRefinementRequests} for violations where the local
     * element is the coarser side ({@link BalanceViolation#localNeedsRefinement()}). These elements must be subdivided
     * locally rather than via a remote request. Consumed by the downstream local-refinement step (m27q/B10c). After
     * this call the queue is empty.
     *
     * @return the queued local refinement keys (empty if none); insertion order is not guaranteed
     */
    public List<Key> drainLocalRefinements() {
        // Iterate-and-remove in one pass so a key added concurrently between a snapshot and a bulk remove cannot be
        // silently dropped (it is either returned here or remains for the next drain). The newKeySet() iterator is
        // weakly consistent and supports remove().
        var drained = new ArrayList<Key>(localRefinementQueue.size());
        for (var it = localRefinementQueue.iterator(); it.hasNext(); ) {
            drained.add(it.next());
            it.remove();
        }
        return drained;
    }
}
