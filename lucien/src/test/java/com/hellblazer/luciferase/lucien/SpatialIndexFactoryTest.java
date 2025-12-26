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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.sfc.SFCArrayIndex;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpatialIndexFactory.
 *
 * @author hal.hildebrand
 */
public class SpatialIndexFactoryTest {

    @Test
    void testCreateOctree() {
        var index = SpatialIndexFactory.createOctree();
        assertNotNull(index);
        assertInstanceOf(Octree.class, index);

        // Verify it works
        var id = index.insert(new Point3f(100, 100, 100), (byte) 10, "test");
        assertNotNull(id);
        assertEquals(1, index.entityCount());
    }

    @Test
    void testCreateSFCArray() {
        var index = SpatialIndexFactory.createSFCArray();
        assertNotNull(index);
        assertInstanceOf(SFCArrayIndex.class, index);

        // Verify it works
        var id = index.insert(new Point3f(100, 100, 100), (byte) 10, "test");
        assertNotNull(id);
        assertEquals(1, index.entityCount());
    }

    @Test
    void testCreateByIndexType() {
        var octree = SpatialIndexFactory.create(SpatialIndexFactory.IndexType.OCTREE);
        assertInstanceOf(Octree.class, octree);

        var sfcArray = SpatialIndexFactory.create(SpatialIndexFactory.IndexType.SFC_ARRAY);
        assertInstanceOf(SFCArrayIndex.class, sfcArray);
    }

    @Test
    void testCreateForWorkloadHighInsertion() {
        var index = SpatialIndexFactory.createForWorkload(SpatialIndexFactory.WorkloadType.HIGH_INSERTION_RATE);
        assertInstanceOf(SFCArrayIndex.class, index, "High insertion rate should use SFCArrayIndex");
    }

    @Test
    void testCreateForWorkloadRangeQuery() {
        var index = SpatialIndexFactory.createForWorkload(SpatialIndexFactory.WorkloadType.RANGE_QUERY_HEAVY);
        assertInstanceOf(SFCArrayIndex.class, index, "Range query heavy should use SFCArrayIndex");
    }

    @Test
    void testCreateForWorkloadMemoryConstrained() {
        var index = SpatialIndexFactory.createForWorkload(SpatialIndexFactory.WorkloadType.MEMORY_CONSTRAINED);
        assertInstanceOf(SFCArrayIndex.class, index, "Memory constrained should use SFCArrayIndex");
    }

    @Test
    void testCreateForWorkloadKNNHeavy() {
        var index = SpatialIndexFactory.createForWorkload(SpatialIndexFactory.WorkloadType.KNN_HEAVY);
        assertInstanceOf(Octree.class, index, "k-NN heavy should use Octree");
    }

    @Test
    void testCreateForWorkloadTreeTraversal() {
        var index = SpatialIndexFactory.createForWorkload(SpatialIndexFactory.WorkloadType.TREE_TRAVERSAL);
        assertInstanceOf(Octree.class, index, "Tree traversal should use Octree");
    }

    @Test
    void testCreateForWorkloadBalanced() {
        var index = SpatialIndexFactory.createForWorkload(SpatialIndexFactory.WorkloadType.BALANCED);
        assertInstanceOf(Octree.class, index, "Balanced workload should use Octree");
    }

    @Test
    void testRecommendHighInsertionRate() {
        // High insertion rate relative to queries
        var recommendation = SpatialIndexFactory.recommend(
            10000,  // entities
            1000,   // insertions/sec
            100,    // range queries/sec
            100,    // knn queries/sec
            0       // no memory constraint
        );
        assertEquals(SpatialIndexFactory.IndexType.SFC_ARRAY, recommendation);
    }

    @Test
    void testRecommendKNNDominant() {
        // k-NN dominant workload
        var recommendation = SpatialIndexFactory.recommend(
            10000,  // entities
            10,     // insertions/sec
            100,    // range queries/sec
            1000,   // knn queries/sec (dominant)
            0       // no memory constraint
        );
        assertEquals(SpatialIndexFactory.IndexType.OCTREE, recommendation);
    }

    @Test
    void testRecommendRangeQueryDominant() {
        // Range query dominant workload
        var recommendation = SpatialIndexFactory.recommend(
            10000,  // entities
            10,     // insertions/sec
            1000,   // range queries/sec (dominant)
            100,    // knn queries/sec
            0       // no memory constraint
        );
        assertEquals(SpatialIndexFactory.IndexType.SFC_ARRAY, recommendation);
    }

    @Test
    void testRecommendMemoryConstrained() {
        // Memory constraint forces SFCArrayIndex
        var recommendation = SpatialIndexFactory.recommend(
            100000, // entities
            10,     // insertions/sec
            100,    // range queries/sec
            100,    // knn queries/sec
            120     // memory budget 120MB (Octree would need ~150MB)
        );
        assertEquals(SpatialIndexFactory.IndexType.SFC_ARRAY, recommendation);
    }

    @Test
    void testBuilderOctree() {
        var index = SpatialIndexFactory.builder(new SequentialLongIDGenerator())
            .octree()
            .maxEntitiesPerNode(20)
            .maxDepth((byte) 15)
            .build();

        assertInstanceOf(Octree.class, index);
    }

    @Test
    void testBuilderSFCArray() {
        var index = SpatialIndexFactory.builder(new SequentialLongIDGenerator())
            .sfcArray()
            .maxDepth((byte) 12)
            .build();

        assertInstanceOf(SFCArrayIndex.class, index);
    }

    @Test
    void testBuilderForWorkload() {
        var sfcIndex = SpatialIndexFactory.builder(new SequentialLongIDGenerator())
            .forWorkload(SpatialIndexFactory.WorkloadType.HIGH_INSERTION_RATE)
            .build();

        assertInstanceOf(SFCArrayIndex.class, sfcIndex);

        var octreeIndex = SpatialIndexFactory.builder(new SequentialLongIDGenerator())
            .forWorkload(SpatialIndexFactory.WorkloadType.KNN_HEAVY)
            .build();

        assertInstanceOf(Octree.class, octreeIndex);
    }

    @Test
    void testBothIndexTypesProduceSameResults() {
        var octree = SpatialIndexFactory.createOctree();
        var sfcArray = SpatialIndexFactory.createSFCArray();

        // Insert same entities
        var positions = new Point3f[]{
            new Point3f(100, 100, 100),
            new Point3f(200, 200, 200),
            new Point3f(300, 300, 300),
            new Point3f(150, 150, 150)
        };

        for (var i = 0; i < positions.length; i++) {
            octree.insert(positions[i], (byte) 10, "Entity" + i);
            sfcArray.insert(positions[i], (byte) 10, "Entity" + i);
        }

        // Same entity count
        assertEquals(octree.entityCount(), sfcArray.entityCount());

        // Same range query results
        var region = new Spatial.Cube(50, 50, 50, 200);
        var octreeResults = octree.entitiesInRegion(region);
        var sfcResults = sfcArray.entitiesInRegion(region);

        assertEquals(octreeResults.size(), sfcResults.size());
    }
}
