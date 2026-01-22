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
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Concrete implementation of DistributedForest providing unified interface for distributed operations.
 *
 * <p>This class wraps:
 * <ul>
 *   <li>Local forest for partition-specific tree operations</li>
 *   <li>Distributed ghost manager for inter-partition communication</li>
 *   <li>Partition registry for distributed coordination</li>
 * </ul>
 *
 * <p>Thread-safe: Uses thread-safe components and immutable wrappers.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public class DistributedForestImpl<Key extends SpatialKey<Key>, ID extends EntityID, Content>
    implements ParallelBalancer.DistributedForest<Key, ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(DistributedForestImpl.class);

    private final Forest<Key, ID, Content> localForest;
    private final DistributedGhostManager<Key, ID, Content> ghostManager;
    private final ParallelBalancer.PartitionRegistry partitionRegistry;
    private final int partitionRank;
    private final int totalPartitions;

    /**
     * Create a new distributed forest wrapper.
     *
     * @param localForest the local forest for this partition
     * @param ghostManager the distributed ghost manager
     * @param partitionRegistry the partition registry for coordination
     * @param partitionRank the rank of this partition
     * @param totalPartitions the total number of partitions
     * @throws NullPointerException if any parameter is null
     */
    public DistributedForestImpl(Forest<Key, ID, Content> localForest,
                                DistributedGhostManager<Key, ID, Content> ghostManager,
                                ParallelBalancer.PartitionRegistry partitionRegistry,
                                int partitionRank,
                                int totalPartitions) {
        this.localForest = Objects.requireNonNull(localForest, "localForest cannot be null");
        this.ghostManager = Objects.requireNonNull(ghostManager, "ghostManager cannot be null");
        this.partitionRegistry = Objects.requireNonNull(partitionRegistry, "partitionRegistry cannot be null");
        this.partitionRank = partitionRank;
        this.totalPartitions = totalPartitions;

        log.debug("Created DistributedForest: rank={}, total partitions={}", partitionRank, totalPartitions);
    }

    /**
     * Get the local forest for this partition.
     *
     * @return the local forest
     */
    @Override
    public Forest<Key, ID, Content> getLocalForest() {
        return localForest;
    }

    /**
     * Get the distributed ghost manager for inter-partition communication.
     *
     * @return the ghost manager
     */
    @Override
    public DistributedGhostManager<Key, ID, Content> getGhostManager() {
        return ghostManager;
    }

    /**
     * Get the partition registry for distributed coordination.
     *
     * @return the partition registry
     */
    @Override
    public ParallelBalancer.PartitionRegistry getPartitionRegistry() {
        return partitionRegistry;
    }

    /**
     * Get the rank of this partition.
     *
     * @return the partition rank
     */
    public int getPartitionRank() {
        return partitionRank;
    }

    /**
     * Get the total number of partitions in the system.
     *
     * @return the partition count
     */
    public int getTotalPartitions() {
        return totalPartitions;
    }

    /**
     * Route a spatial query across the forest, considering ghosts from neighboring partitions.
     *
     * <p>This method combines:
     * <ul>
     *   <li>Results from local forest operations</li>
     *   <li>Ghost elements from boundary regions in neighboring partitions</li>
     * </ul>
     *
     * @param queryType the type of query being performed
     * @return the combined query result including ghosts
     */
    public QueryResult<Key, ID, Content> routeQuery(QueryType queryType) {
        log.debug("Routing {} query from partition {}", queryType, partitionRank);

        // TODO: Implement full query routing with ghost inclusion
        // For now, return empty result structure
        var ghosts = java.util.List.of();

        log.debug("Query routed with {} ghosts", ghosts.size());

        return new QueryResult<>(null, ghosts, null);
    }

    /**
     * Submit a refinement request to neighboring partitions.
     *
     * <p>This method coordinates with the partition registry to route refinement
     * requests to neighbors based on spatial locality.
     *
     * @param refinementKey the spatial key needing refinement
     * @param level the level in the tree to refine
     */
    public void submitRefinementRequest(Key refinementKey, int level) {
        log.debug("Submitting refinement request: key={}, level={}, partition={}", refinementKey, level, partitionRank);
        // TODO: Implement refinement request routing
        // Should coordinate with partition registry to send RefinementRequest messages
        // to neighboring partitions based on spatial locality
    }

    /**
     * Get distributed statistics across all partitions.
     *
     * @return statistics aggregated from all partitions
     */
    public DistributedStats getDistributedStats() {
        // TODO: Implement proper stats collection from local forest and ghost manager
        // For now, return basic structure
        var ghostCount = 0L;

        log.debug("Distributed stats collected for partition {}", partitionRank);

        return new DistributedStats(partitionRank, totalPartitions, "stats", ghostCount);
    }

    /**
     * Type of spatial query being routed.
     */
    public enum QueryType {
        REGION_QUERY,
        K_NN_SEARCH,
        RAY_INTERSECTION,
        FRUSTUM_CULLING,
        BOUNDARY_ELEMENT_SEARCH
    }

    /**
     * Result of a distributed spatial query combining local and ghost results.
     */
    public record QueryResult<Key extends SpatialKey<Key>, ID extends EntityID, Content>(
        Object localResult,
        java.util.List<?> ghosts,
        Key queryKey
    ) {
    }

    /**
     * Distributed forest statistics across all partitions.
     */
    public record DistributedStats(
        int partitionRank,
        int totalPartitions,
        Object localStats,
        long boundaryGhosts
    ) {
    }

    /**
     * Interface for spatial queries to be routed across distributed forest.
     */
    public interface SpatialQuery<Key extends SpatialKey<Key>, Content> {
        /**
         * Get the spatial key associated with this query.
         */
        Key getSpatialKey();

        /**
         * Get the query description for logging.
         */
        String getDescription();
    }
}
