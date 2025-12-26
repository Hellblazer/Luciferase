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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.sfc.SFCArrayIndex;

/**
 * Factory for creating spatial index implementations based on workload characteristics.
 *
 * <h2>Available Implementations</h2>
 * <ul>
 *   <li><b>Octree</b>: Hierarchical tree structure. Best for k-NN queries, tree traversal,
 *       and workloads requiring hierarchical spatial organization.</li>
 *   <li><b>SFCArrayIndex</b>: Flat SFC-sorted array. Best for high insertion rates,
 *       frequent range queries, and memory-constrained environments.</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <table>
 *   <tr><th>Operation</th><th>Octree</th><th>SFCArrayIndex</th></tr>
 *   <tr><td>Insertion</td><td>Baseline</td><td>2.9x faster</td></tr>
 *   <tr><td>Range Query</td><td>Baseline</td><td>2x faster</td></tr>
 *   <tr><td>k-NN Query</td><td>Baseline</td><td>15% slower</td></tr>
 *   <tr><td>Memory</td><td>Baseline</td><td>33% less</td></tr>
 * </table>
 *
 * @author hal.hildebrand
 */
public class SpatialIndexFactory {

    /**
     * Workload type hints for automatic index selection.
     */
    public enum WorkloadType {
        /**
         * High insertion rate workload. Recommends SFCArrayIndex.
         */
        HIGH_INSERTION_RATE,

        /**
         * Frequent range query workload. Recommends SFCArrayIndex.
         */
        RANGE_QUERY_HEAVY,

        /**
         * k-NN query heavy workload. Recommends Octree.
         */
        KNN_HEAVY,

        /**
         * Tree traversal required. Recommends Octree.
         */
        TREE_TRAVERSAL,

        /**
         * Memory constrained environment. Recommends SFCArrayIndex.
         */
        MEMORY_CONSTRAINED,

        /**
         * Balanced workload. Recommends Octree (default).
         */
        BALANCED,

        /**
         * Let the factory decide based on other factors.
         */
        AUTO
    }

    /**
     * Index type selection.
     */
    public enum IndexType {
        OCTREE,
        SFC_ARRAY
    }

    private static final int DEFAULT_MAX_ENTITIES_PER_NODE = 10;

    /**
     * Create an Octree with default configuration.
     */
    public static <Content> Octree<LongEntityID, Content> createOctree() {
        return new Octree<>(new SequentialLongIDGenerator());
    }

    /**
     * Create an Octree with custom ID generator.
     */
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> Octree<ID, Content> createOctree(
            EntityIDGenerator<ID> idGenerator) {
        return new Octree<>(idGenerator);
    }

    /**
     * Create an Octree with custom configuration.
     */
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> Octree<ID, Content> createOctree(
            EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth) {
        return new Octree<>(idGenerator, maxEntitiesPerNode, maxDepth);
    }

    /**
     * Create an SFCArrayIndex with default configuration.
     */
    public static <Content> SFCArrayIndex<LongEntityID, Content> createSFCArray() {
        return new SFCArrayIndex<>(new SequentialLongIDGenerator());
    }

    /**
     * Create an SFCArrayIndex with custom ID generator.
     */
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> SFCArrayIndex<ID, Content> createSFCArray(
            EntityIDGenerator<ID> idGenerator) {
        return new SFCArrayIndex<>(idGenerator);
    }

    /**
     * Create an SFCArrayIndex with custom configuration.
     */
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> SFCArrayIndex<ID, Content> createSFCArray(
            EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth) {
        return new SFCArrayIndex<>(idGenerator, maxEntitiesPerNode, maxDepth);
    }

    /**
     * Create a spatial index based on workload type.
     *
     * @param workloadType the expected workload characteristics
     * @return the recommended spatial index implementation
     */
    public static <Content> SpatialIndex<MortonKey, LongEntityID, Content> createForWorkload(WorkloadType workloadType) {
        return createForWorkload(workloadType, new SequentialLongIDGenerator());
    }

    /**
     * Create a spatial index based on workload type with custom ID generator.
     *
     * @param workloadType the expected workload characteristics
     * @param idGenerator  the entity ID generator
     * @return the recommended spatial index implementation
     */
    @SuppressWarnings("unchecked")
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> SpatialIndex<MortonKey, ID, Content> createForWorkload(
            WorkloadType workloadType, EntityIDGenerator<ID> idGenerator) {
        return switch (workloadType) {
            case HIGH_INSERTION_RATE, RANGE_QUERY_HEAVY, MEMORY_CONSTRAINED ->
                new SFCArrayIndex<>(idGenerator);

            case KNN_HEAVY, TREE_TRAVERSAL, BALANCED ->
                new Octree<>(idGenerator);

            case AUTO ->
                // Default to Octree for balanced workloads
                new Octree<>(idGenerator);
        };
    }

    /**
     * Create a spatial index by explicit type.
     *
     * @param indexType the type of index to create
     * @return the spatial index implementation
     */
    public static <Content> SpatialIndex<MortonKey, LongEntityID, Content> create(IndexType indexType) {
        return create(indexType, new SequentialLongIDGenerator());
    }

    /**
     * Create a spatial index by explicit type with custom ID generator.
     *
     * @param indexType   the type of index to create
     * @param idGenerator the entity ID generator
     * @return the spatial index implementation
     */
    @SuppressWarnings("unchecked")
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> SpatialIndex<MortonKey, ID, Content> create(
            IndexType indexType, EntityIDGenerator<ID> idGenerator) {
        return switch (indexType) {
            case OCTREE -> new Octree<>(idGenerator);
            case SFC_ARRAY -> new SFCArrayIndex<>(idGenerator);
        };
    }

    /**
     * Create a spatial index with full configuration.
     *
     * @param indexType          the type of index to create
     * @param idGenerator        the entity ID generator
     * @param maxEntitiesPerNode maximum entities per node before subdivision (Octree only)
     * @param maxDepth           maximum tree depth
     * @param spanningPolicy     entity spanning policy
     * @return the configured spatial index
     */
    @SuppressWarnings("unchecked")
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> SpatialIndex<MortonKey, ID, Content> create(
            IndexType indexType,
            EntityIDGenerator<ID> idGenerator,
            int maxEntitiesPerNode,
            byte maxDepth,
            EntitySpanningPolicy spanningPolicy) {
        return switch (indexType) {
            case OCTREE -> new Octree<>(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
            case SFC_ARRAY -> new SFCArrayIndex<>(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
        };
    }

    /**
     * Get a recommendation for which index type to use based on workload characteristics.
     *
     * @param expectedEntityCount   approximate number of entities
     * @param insertionRate         insertions per second (approximate)
     * @param rangeQueryRate        range queries per second (approximate)
     * @param knnQueryRate          k-NN queries per second (approximate)
     * @param memoryConstrainedMB   memory budget in MB (0 = unlimited)
     * @return recommended index type
     */
    public static IndexType recommend(
            int expectedEntityCount,
            float insertionRate,
            float rangeQueryRate,
            float knnQueryRate,
            int memoryConstrainedMB) {

        // Memory constraint check
        if (memoryConstrainedMB > 0) {
            // SFCArrayIndex uses ~1KB/entity, Octree uses ~1.5KB/entity
            var sfcMemoryMB = expectedEntityCount / 1000;
            var octreeMemoryMB = (int) (expectedEntityCount * 1.5 / 1000);

            if (octreeMemoryMB > memoryConstrainedMB && sfcMemoryMB <= memoryConstrainedMB) {
                return IndexType.SFC_ARRAY;
            }
        }

        // Workload analysis
        var totalQueryRate = rangeQueryRate + knnQueryRate;
        var knnRatio = totalQueryRate > 0 ? knnQueryRate / totalQueryRate : 0;

        // If k-NN is dominant (>60% of queries), prefer Octree
        if (knnRatio > 0.6) {
            return IndexType.OCTREE;
        }

        // If insertion rate is high relative to queries, prefer SFCArrayIndex
        if (insertionRate > totalQueryRate * 2) {
            return IndexType.SFC_ARRAY;
        }

        // If range queries dominate, prefer SFCArrayIndex
        if (rangeQueryRate > knnQueryRate * 2) {
            return IndexType.SFC_ARRAY;
        }

        // Default to Octree for balanced workloads
        return IndexType.OCTREE;
    }

    /**
     * Builder for creating spatial indices with fluent configuration.
     */
    public static <ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> Builder<ID, Content> builder(
            EntityIDGenerator<ID> idGenerator) {
        return new Builder<>(idGenerator);
    }

    /**
     * Fluent builder for spatial index creation.
     */
    public static class Builder<ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> {
        private final EntityIDGenerator<ID> idGenerator;
        private IndexType indexType = IndexType.OCTREE;
        private int maxEntitiesPerNode = DEFAULT_MAX_ENTITIES_PER_NODE;
        private byte maxDepth = Constants.getMaxRefinementLevel();
        private EntitySpanningPolicy spanningPolicy = new EntitySpanningPolicy();

        private Builder(EntityIDGenerator<ID> idGenerator) {
            this.idGenerator = idGenerator;
        }

        public Builder<ID, Content> octree() {
            this.indexType = IndexType.OCTREE;
            return this;
        }

        public Builder<ID, Content> sfcArray() {
            this.indexType = IndexType.SFC_ARRAY;
            return this;
        }

        public Builder<ID, Content> forWorkload(WorkloadType workloadType) {
            this.indexType = switch (workloadType) {
                case HIGH_INSERTION_RATE, RANGE_QUERY_HEAVY, MEMORY_CONSTRAINED -> IndexType.SFC_ARRAY;
                case KNN_HEAVY, TREE_TRAVERSAL, BALANCED, AUTO -> IndexType.OCTREE;
            };
            return this;
        }

        public Builder<ID, Content> maxEntitiesPerNode(int max) {
            this.maxEntitiesPerNode = max;
            return this;
        }

        public Builder<ID, Content> maxDepth(byte depth) {
            this.maxDepth = depth;
            return this;
        }

        public Builder<ID, Content> spanningPolicy(EntitySpanningPolicy policy) {
            this.spanningPolicy = policy;
            return this;
        }

        @SuppressWarnings("unchecked")
        public SpatialIndex<MortonKey, ID, Content> build() {
            return switch (indexType) {
                case OCTREE -> new Octree<>(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
                case SFC_ARRAY -> new SFCArrayIndex<>(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
            };
        }
    }
}
