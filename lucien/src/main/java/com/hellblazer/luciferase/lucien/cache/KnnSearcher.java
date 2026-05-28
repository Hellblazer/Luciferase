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
package com.hellblazer.luciferase.lucien.cache;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.FineGrainedLockingStrategy;
import com.hellblazer.luciferase.lucien.SpatialIndexCore;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.internal.ObjectPools;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The k-nearest-neighbors feature object for a spatial index (RDR-008 P3).
 *
 * <p>Owns the k-NN cluster: the public {@code kNearestNeighbors} query, the SFC-range-pruning fast path with its
 * MortonKey/TetreeKey-specific variants, the expanding-radius fallback (with the Prism-specific full-domain safety
 * sweep), the legacy breadth-first fallback for unknown key types, and the four cache/path-selection performance
 * counters. The shared storage and concurrency primitives (the sorted node map, the {@link KNNCache}, the spatial-
 * version counter for cache invalidation) arrive through {@link SpatialIndexCore}; the subclass-overridden geometry
 * hooks and the façade's input-validation, cached-position, and depth-constant helpers arrive through
 * {@link KnnGeometry}; the read-lock wrapper is supplied as a {@link Supplier} because the façade's
 * {@link FineGrainedLockingStrategy} is mutable via {@code configureFineGrainedLocking}.
 *
 * <h2>Concurrency invariant (RDR-008 §Open Questions, resolved)</h2>
 *
 * <p>The version-check pattern is load-bearing and intentionally retained verbatim from the pre-extraction façade:
 * {@link #kNearestNeighbors} reads {@link SpatialIndexCore#spatialVersion()} <em>before</em> entering the read-lock
 * region, uses that version to consult the cache (early-return on hit), and writes the cache result under the same
 * version inside the read-lock region. {@link KNNCache#get} returns {@code null} on version mismatch; combined with
 * the happens-before from the entity-lifecycle write-lock release to this read-lock acquire (Java Memory Model
 * monitor semantics), a concurrent {@code updateEntity} between the version-read and the cache-write cannot return
 * stale or torn results — at worst the next reader sees a cache miss for that key (the cache entry's version no
 * longer matches the current version) and recomputes.
 *
 * <h2>Subclass-coupled dispatch</h2>
 *
 * <p>{@link #performKNNSFCRangePruning} dispatches on the concrete spatial-key type via
 * {@code instanceof MortonKey / TetreeKey} to use each subclass's SFC range estimator. This couples
 * {@code lucien.cache} to {@code lucien.octree} and {@code lucien.tetree}; the dispatch was present before
 * extraction (in the façade) and the alternative — pushing the range estimator into a method on the key type —
 * is out of scope for the no-behavior-change extraction phase. The unknown-key-type branch and the catch-all
 * exception fallback both route to the legacy breadth-first {@link #performKNNSFCBasedSearch}, so the dispatch is
 * forward-compatible with future key types.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class KnnSearcher<Key extends SpatialKey<Key>, ID extends EntityID, Content>
implements KnnProvider<Key, ID> {

    /** Performance metrics for the k-NN cluster, exposed via {@link #getKNNPerformanceMetrics}. */
    public record KNNPerformanceMetrics(long cacheHits, long cacheMisses, long expandingSearchUsed, long sfcPruningUsed,
                                        double cacheHitRate, double sfcPruningRate) {}

    private static final Logger log = LoggerFactory.getLogger(KnnSearcher.class);

    private final SpatialIndexCore<Key, ID, Content>             core;
    private final KnnGeometry<Key, ID>                           geometry;
    private final Supplier<FineGrainedLockingStrategy<ID, Content>> lockingStrategySupplier;

    // Performance counters (P3-main: moved from AbstractSpatialIndex; subclass-private — no external mutators).
    private final AtomicLong knnCacheHits           = new AtomicLong(0);
    private final AtomicLong knnCacheMisses         = new AtomicLong(0);
    private final AtomicLong knnExpandingSearchUsed = new AtomicLong(0);
    private final AtomicLong knnSFCPruningUsed      = new AtomicLong(0);

    /**
     * Construct the k-NN searcher. References are stored only — the constructor does not invoke any method on
     * {@code core}, {@code geometry}, or {@code lockingStrategySupplier}, so it is safe to construct from inside the
     * façade ctor before the façade is fully initialized (the this-escape invariant documented on
     * {@code AbstractSpatialIndex}).
     */
    public KnnSearcher(SpatialIndexCore<Key, ID, Content> core, KnnGeometry<Key, ID> geometry,
                       Supplier<FineGrainedLockingStrategy<ID, Content>> lockingStrategySupplier) {
        this.core = core;
        this.geometry = geometry;
        this.lockingStrategySupplier = lockingStrategySupplier;
    }

    /**
     * k-NN performance metrics aggregated since construction. Returned as a snapshot record (no live binding).
     */
    public KNNPerformanceMetrics getKNNPerformanceMetrics() {
        var hits = knnCacheHits.get();
        var misses = knnCacheMisses.get();
        var expanding = knnExpandingSearchUsed.get();
        var sfc = knnSFCPruningUsed.get();

        var totalQueries = hits + misses;
        var cacheHitRate = totalQueries > 0 ? (double) hits / totalQueries : 0.0;

        var totalSearches = expanding + sfc;
        var sfcPruningRate = totalSearches > 0 ? (double) sfc / totalSearches : 0.0;

        return new KNNPerformanceMetrics(hits, misses, expanding, sfc, cacheHitRate, sfcPruningRate);
    }

    /**
     * Find k nearest neighbors to a query point using spatial locality optimization.
     *
     * @param queryPoint  the point to search from
     * @param k           the number of neighbors to find
     * @param maxDistance maximum search distance
     * @return list of entity IDs sorted by distance (closest first)
     */
    @Override
    public List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance) {
        geometry.validateSpatialConstraints(queryPoint);

        if (k <= 0) {
            return Collections.emptyList();
        }

        var spatialIndex = core.spatialIndex();
        var knnCache = core.knnCache();
        log.debug("k-NN search: queryPoint={}, k={}, maxDistance={}, spatialIndex.size()={}",
                  queryPoint, k, maxDistance, spatialIndex.size());

        // k-NN cache: Check cache with composite key (position + k + maxDistance)
        // Use level 15 for cache granularity (cell size = 64) to distinguish nearby queries
        // Level 0 is too coarse (cell size = 2,097,152) and causes false cache hits
        var spatialKey = geometry.calculateSpatialIndex(queryPoint, (byte) 15);
        var queryKey = new KNNQueryKey<>(spatialKey, k, maxDistance);
        var currentVersion = core.spatialVersion().get();
        var cached = knnCache.get(queryKey, currentVersion);
        if (cached != null) {
            // Cache hit: return cached entity IDs (0.05-0.1ms vs 0.3-0.5ms)
            knnCacheHits.incrementAndGet();
            log.debug("k-NN cache hit: returning {} entities", cached.entityIds().size());
            return cached.entityIds();
        }

        knnCacheMisses.incrementAndGet();
        log.debug("k-NN cache miss: computing k-NN");

        // Cache miss: compute k-NN and store result
        // Use fine-grained locking for read operations
        return lockingStrategySupplier.get().executeRead(0L, () -> {
            // Priority queue to keep track of k nearest entities (max heap)
            var candidates = ObjectPools.borrowPriorityQueue(EntityDistance.<ID>maxHeapComparator());
            var addedToCandidates = ObjectPools.<ID>borrowHashSet();
            try {
                // Use optimized SFC range pruning first (4-6× speedup from Paper 4)
                knnSFCPruningUsed.incrementAndGet();
                performKNNSFCRangePruning(queryPoint, k, maxDistance, candidates, addedToCandidates);

                // If SFC pruning didn't find enough entities, fall back to expanding radius search
                if (candidates.size() < k) {
                    knnExpandingSearchUsed.incrementAndGet();
                    performKNNExpandingRadiusSearch(queryPoint, k, maxDistance, candidates, addedToCandidates);
                }

                // Convert to sorted list (closest first) and extract distances
                var sorted = ObjectPools.<EntityDistance<ID>>borrowArrayList(candidates.size());
                try {
                    sorted.addAll(candidates);
                    sorted.sort(EntityDistance.minHeapComparator());

                    var entityIds = new ArrayList<ID>(sorted.size());
                    var distances = new ArrayList<Float>(sorted.size());
                    for (var entry : sorted) {
                        entityIds.add(entry.entityId());
                        distances.add(entry.distance());
                    }

                    // Store in cache for future queries
                    knnCache.put(queryKey, entityIds, distances, currentVersion);

                    return entityIds;
                } finally {
                    ObjectPools.returnArrayList(sorted);
                }
            } finally {
                ObjectPools.returnPriorityQueue(candidates);
                ObjectPools.returnHashSet(addedToCandidates);
            }
        });
    }

    // ---- SFC range pruning (Paper 4: 4-6× speedup) ----------------------------------------------------------------

    /**
     * Optimized SFC range-based k-NN search using range pruning. Dispatches on the concrete spatial-key type to use
     * each subclass's SFC range estimator; unknown key types and any thrown exception fall back to the legacy
     * breadth-first search.
     */
    private void performKNNSFCRangePruning(Point3f queryPoint, int k, float maxDistance,
                                           PriorityQueue<EntityDistance<ID>> candidates, Set<ID> addedToCandidates) {
        var spatialIndex = core.spatialIndex();
        if (spatialIndex.isEmpty()) {
            return;
        }

        // Get the root key to determine key type
        var rootKey = spatialIndex.firstKey();

        try {
            // Use appropriate SFC range estimation based on key type
            if (rootKey instanceof MortonKey) {
                performKNNSFCRangePruningMorton(queryPoint, k, maxDistance, candidates, addedToCandidates);
            } else if (rootKey instanceof TetreeKey) {
                performKNNSFCRangePruningTetree(queryPoint, k, maxDistance, candidates, addedToCandidates);
            } else {
                // Fallback to old breadth-first search for unknown key types
                performKNNSFCBasedSearch(queryPoint, k, maxDistance, candidates, addedToCandidates);
            }
        } catch (Exception e) {
            log.warn("SFC range pruning failed, falling back to breadth-first search: {}", e.getMessage());
            performKNNSFCBasedSearch(queryPoint, k, maxDistance, candidates, addedToCandidates);
        }
    }

    /**
     * SFC range-based k-NN search for MortonKey (Octree).
     *
     * <p>Because {@link MortonKey#compareTo} orders keys first by level then by Morton code, a {@code subMap} call
     * whose bounds are at level L will only return keys that are also stored at level L. Entities can be inserted at
     * different levels, so we collect the set of distinct storage levels present in the index and issue one
     * {@code subMap} query per unique level, using bounds computed at that same level.
     */
    @SuppressWarnings("unchecked")
    private void performKNNSFCRangePruningMorton(Point3f queryPoint, int k, float maxDistance,
                                                 PriorityQueue<EntityDistance<ID>> candidates,
                                                 Set<ID> addedToCandidates) {
        var spatialIndex = core.spatialIndex();
        // When maxDistance is very large, SFC range pruning at level 0 won't work properly
        // because entities are stored at a finer level. Fall back to full scan.
        if (maxDistance >= Constants.MAX_COORD) {
            performFullScanKNN(queryPoint, k, maxDistance, candidates, addedToCandidates);
            return;
        }

        // Collect the distinct levels at which keys are stored so we can issue a correctly-levelled
        // subMap query for each one.  In the common single-level case this is a single pass.
        var storageLevels = new LinkedHashSet<Byte>();
        for (var key : spatialIndex.keySet()) {
            storageLevels.add(((MortonKey) key).getLevel());
        }

        for (byte storageLevel : storageLevels) {
            // Compute SFC range bounds at the same level as the stored keys so that
            // subMap() returns the correct entries with level-aware compareTo.
            var sfcRange = MortonKey.estimateSFCRange(queryPoint, maxDistance, storageLevel);

            var rangeMap = spatialIndex.subMap((Key) sfcRange.lower(), (Key) sfcRange.upper());

            for (var entry : rangeMap.entrySet()) {
                var node = entry.getValue();
                if (node == null) {
                    continue;
                }

                collectCandidatesFromNode(node, queryPoint, k, maxDistance, candidates, addedToCandidates);
            }
        }
    }

    /** SFC range-based k-NN search for TetreeKey (Tetree). */
    @SuppressWarnings("unchecked")
    private void performKNNSFCRangePruningTetree(Point3f queryPoint, int k, float maxDistance,
                                                 PriorityQueue<EntityDistance<ID>> candidates,
                                                 Set<ID> addedToCandidates) {
        var spatialIndex = core.spatialIndex();
        // When maxDistance is very large, SFC range pruning at level 0 won't work properly
        // because entities are stored at a finer level. Fall back to full scan.
        if (maxDistance >= Constants.MAX_COORD) {
            performFullScanKNN(queryPoint, k, maxDistance, candidates, addedToCandidates);
            return;
        }

        // Estimate SFC range covering the search sphere
        var sfcRange = TetreeKey.estimateSFCRange(queryPoint, maxDistance);

        // Use subMap to iterate only over keys in the SFC range (this is the optimization!)
        var rangeMap = spatialIndex.subMap((Key) sfcRange.lower(), (Key) sfcRange.upper());

        // Process entities in nodes within the SFC range
        for (var entry : rangeMap.entrySet()) {
            var node = entry.getValue();
            if (node == null) {
                continue;
            }

            collectCandidatesFromNode(node, queryPoint, k, maxDistance, candidates, addedToCandidates);
        }
    }

    /**
     * Full scan k-NN search for unlimited distance queries. Used when {@code maxDistance} is so large that SFC range
     * pruning won't work correctly.
     */
    private void performFullScanKNN(Point3f queryPoint, int k, float maxDistance,
                                    PriorityQueue<EntityDistance<ID>> candidates, Set<ID> addedToCandidates) {
        // Iterate through all nodes in the spatial index
        for (var entry : core.spatialIndex().entrySet()) {
            var node = entry.getValue();
            if (node == null) {
                continue;
            }

            collectCandidatesFromNode(node, queryPoint, k, maxDistance, candidates, addedToCandidates);
        }
    }

    // ---- Expanding radius fallback --------------------------------------------------------------------------------

    /**
     * Expanding-radius k-NN fallback. Returns {@code true} if enough candidates were found.
     */
    private boolean performKNNExpandingRadiusSearch(Point3f queryPoint, int k, float maxDistance,
                                                    PriorityQueue<EntityDistance<ID>> candidates,
                                                    Set<ID> addedToCandidates) {
        // Start with a reasonable initial radius - at least the cell size at a mid-level
        var maxDepth = geometry.maxDepth();
        var minInitialRadius = geometry.getCellSizeAtLevel((byte) Math.min(maxDepth, 15));
        var searchRadius = Math.min(maxDistance, Math.max(minInitialRadius, geometry.getCellSizeAtLevel(maxDepth)));
        var searchExpansions = 0;
        final var maxExpansions = 10; // Allow more expansions to reach distant entities

        log.debug("k-NN expanding search: initialRadius={}, maxDistance={}", searchRadius, maxDistance);

        while (candidates.size() < k && searchRadius <= maxDistance && searchExpansions < maxExpansions) {
            var foundNewEntities = searchKNNInRadius(queryPoint, searchRadius, maxDistance, k, candidates,
                                                     addedToCandidates);

            log.debug("k-NN expansion {}: radius={}, found={}, candidates={}",
                      searchExpansions, searchRadius, foundNewEntities, candidates.size());

            if (candidates.size() < k && searchRadius < maxDistance) {
                searchRadius *= 2.0f;
                searchExpansions++;
            } else {
                break;
            }

            // If we didn't find any new entities in this expansion, stop
            if (!foundNewEntities && searchExpansions > 1) {
                break;
            }
        }

        // Final safety sweep: the incremental expansion above can terminate before covering the
        // index — its "no new entities" heuristic and fixed expansion cap assume entities are
        // densely clustered near the query, and its initial radius is a fine-level cell size. Over a
        // SPARSE index the true neighbors can sit beyond the last ring (this is what left Prism
        // k-NN under-returning — the doublings from a sub-unit cell size never spanned the [0,1)
        // world within the cap). If still short of k, do one pass whose radius spans the whole
        // coordinate domain. The radius is bounded by the root-cell edge (getCellSizeAtLevel(0) =
        // the domain extent), NOT by maxDistance: an unbounded maxDistance (e.g. Float.MAX_VALUE for
        // "all within range") would otherwise build an overflowing search box that makes the
        // integer-SFC findNodesIntersectingBounds (LITMAX/BIGMIN) non-terminating. (Luciferase-h65)
        if (candidates.size() < k && geometry.knnRequiresFullDomainSweep()) {
            // Full-domain safety sweep — gated to indices that need it (see
            // knnRequiresFullDomainSweep). The incremental expansion above can terminate before
            // covering the index when the coordinate space defeats its heuristics (Prism's
            // normalized fractional coordinates: the doublings from a sub-unit initial radius never
            // span the [0,1) world within the expansion cap, and the "no new entities" early-exit
            // fires on the empty rings before the real neighbors). One pass over a domain-spanning
            // box closes the gap. This is NOT run for the integer-SFC indices (Octree/Tetree/SFC):
            // their SFC range-pruning path already covers the whole maxDistance sphere, so a
            // domain-spanning findNodesIntersectingBounds there would be a costly LITMAX/BIGMIN no-op
            // (and non-terminating for a large box). See knnRequiresFullDomainSweep. (Luciferase-h65)
            //
            // The box spans only the root-cell edge (getCellSizeAtLevel(0)): for any query inside the
            // domain, query +/- edge already encloses the whole domain, so every node is a candidate.
            // Acceptance uses the full maxDistance (decoupled from the box radius), so far-corner
            // neighbors up to the domain diagonal are not wrongly rejected.
            var edge = geometry.getCellSizeAtLevel((byte) 0);
            var sweepBounds = new VolumeBounds(queryPoint.x - edge, queryPoint.y - edge, queryPoint.z - edge,
                                               queryPoint.x + edge, queryPoint.y + edge, queryPoint.z + edge);
            var spatialIndex = core.spatialIndex();
            for (var nodeKey : geometry.findNodesIntersectingBounds(sweepBounds)) {
                var node = spatialIndex.get(nodeKey);
                if (node == null || node.isEmpty()) {
                    continue;
                }
                for (var entityId : node.getEntityIds()) {
                    if (addedToCandidates.contains(entityId)) {
                        continue;
                    }
                    var entityPos = geometry.getCachedEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }
                    var distance = queryPoint.distance(entityPos);
                    if (distance <= maxDistance) {
                        candidates.add(new EntityDistance<>(entityId, distance));
                        addedToCandidates.add(entityId);
                        if (candidates.size() > k) {
                            var removed = candidates.poll();
                            addedToCandidates.remove(removed.entityId());
                        }
                    }
                }
            }
        }

        log.debug("k-NN expanding search complete: found {} candidates", candidates.size());
        return candidates.size() >= k;
    }

    /** Search for entities within a specific radius. Returns {@code true} if new entities were added. */
    private boolean searchKNNInRadius(Point3f queryPoint, float searchRadius, float maxDistance, int k,
                                      PriorityQueue<EntityDistance<ID>> candidates, Set<ID> addedToCandidates) {
        var visitedThisExpansion = ObjectPools.<ID>borrowHashSet();
        try {
            var searchBounds = new VolumeBounds(queryPoint.x - searchRadius, queryPoint.y - searchRadius,
                                                queryPoint.z - searchRadius, queryPoint.x + searchRadius,
                                                queryPoint.y + searchRadius, queryPoint.z + searchRadius);

            var candidateNodes = geometry.findNodesIntersectingBounds(searchBounds);
            log.debug("k-NN searchInRadius: radius={}, bounds={}, candidateNodes={}",
                      searchRadius, searchBounds, candidateNodes.size());
            var foundNewEntities = false;

            var spatialIndex = core.spatialIndex();
            for (Key nodeKey : candidateNodes) {
                var node = spatialIndex.get(nodeKey);
                if (node == null || node.isEmpty()) {
                    continue;
                }

                // Check all entities in this node
                for (ID entityId : node.getEntityIds()) {
                    if (visitedThisExpansion.add(entityId)) {
                        var entityPos = geometry.getCachedEntityPosition(entityId);
                        if (entityPos != null) {
                            var distance = queryPoint.distance(entityPos);

                            // Only consider entities within current search radius
                            if (distance <= searchRadius && distance <= maxDistance && !addedToCandidates.contains(
                            entityId)) {
                                candidates.add(new EntityDistance<>(entityId, distance));
                                addedToCandidates.add(entityId);
                                foundNewEntities = true;

                                // Keep only k elements
                                if (candidates.size() > k) {
                                    candidates.poll();
                                    // Don't remove from addedToCandidates to prevent re-adding
                                }
                            }
                        }
                    }
                }
            }

            return foundNewEntities;
        } finally {
            ObjectPools.returnHashSet(visitedThisExpansion);
        }
    }

    // ---- Legacy breadth-first fallback (unknown key types + SFC-pruning exception fallback) -----------------------

    /** Breadth-first search from the nearest node. */
    private void performKNNSFCBasedSearch(Point3f queryPoint, int k, float maxDistance,
                                          PriorityQueue<EntityDistance<ID>> candidates, Set<ID> addedToCandidates) {
        var spatialIndex = core.spatialIndex();
        if (spatialIndex.isEmpty()) {
            return;
        }

        var nearestNodeIndex = findNearestNodeToPoint(queryPoint);
        if (nearestNodeIndex == null) {
            return;
        }

        // Breadth-first search from nearest node
        var toVisit = new LinkedList<Key>();
        var visitedNodes = ObjectPools.<Key>borrowHashSet();
        try {
            toVisit.add(nearestNodeIndex);

            while (!toVisit.isEmpty() && candidates.size() < k) {
                var current = toVisit.poll();
                if (!visitedNodes.add(current)) {
                    continue;
                }

                var node = spatialIndex.get(current);
                if (node == null) {
                    continue;
                }

                // Process entities in current node
                for (var entityId : node.getEntityIds()) {
                    if (!addedToCandidates.contains(entityId)) {
                        var entityPos = geometry.getCachedEntityPosition(entityId);
                        if (entityPos != null) {
                            var distance = queryPoint.distance(entityPos);
                            if (distance <= maxDistance) {
                                candidates.add(new EntityDistance<>(entityId, distance));
                                addedToCandidates.add(entityId);
                                if (candidates.size() > k) {
                                    var removed = candidates.poll();
                                    addedToCandidates.remove(removed.entityId());
                                }
                            }
                        }
                    }
                }

                // Add neighboring nodes if we need more candidates
                if (candidates.size() < k || geometry.shouldContinueKNNSearch(current, queryPoint, candidates)) {
                    geometry.addNeighboringNodes(current, toVisit, visitedNodes);
                }
            }
        } finally {
            ObjectPools.returnHashSet(visitedNodes);
        }
    }

    /** Find the nearest node to a query point using the subclass-supplied distance estimator. */
    private Key findNearestNodeToPoint(Point3f queryPoint) {
        var bestDistance = Float.MAX_VALUE;
        Key nearestNodeIndex = null;
        var cellSize = geometry.getCellSizeAtLevel(geometry.maxDepth());
        var spatialIndex = core.spatialIndex();

        for (var nodeIndex : spatialIndex.keySet()) {
            var node = spatialIndex.get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }

            var nodeDistance = geometry.estimateNodeDistance(nodeIndex, queryPoint);
            if (nodeDistance < bestDistance) {
                bestDistance = nodeDistance;
                nearestNodeIndex = nodeIndex;
            }

            // Early termination
            if (nodeDistance < cellSize) {
                break;
            }
        }

        return nearestNodeIndex;
    }

    // ---- Shared candidate accumulation ----------------------------------------------------------------------------

    /**
     * Accumulate eligible entities from a single node into the candidate max-heap, keeping the heap size <= k.
     * Factored out of the three SFC-range / full-scan paths (Morton, Tetree, full-scan) which were byte-for-byte
     * identical inner loops.
     */
    private void collectCandidatesFromNode(SpatialNodeImpl<ID> node, Point3f queryPoint, int k, float maxDistance,
                                           PriorityQueue<EntityDistance<ID>> candidates, Set<ID> addedToCandidates) {
        for (var entityId : node.getEntityIds()) {
            if (addedToCandidates.contains(entityId)) {
                continue;
            }
            var entityPos = geometry.getCachedEntityPosition(entityId);
            if (entityPos == null) {
                continue;
            }
            var distance = queryPoint.distance(entityPos);
            if (distance <= maxDistance) {
                candidates.add(new EntityDistance<>(entityId, distance));
                addedToCandidates.add(entityId);

                // Maintain max heap of size k
                if (candidates.size() > k) {
                    var removed = candidates.poll();
                    addedToCandidates.remove(removed.entityId());
                }
            }
        }
    }
}
