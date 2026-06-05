/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-7wzml.108: distributeEntities is non-spatial round-robin;
 * documents and asserts the conservation contract.
 */
class DefaultBalancingStrategyDistributeTest {

    private final DefaultBalancingStrategy<LongEntityID> strategy = new DefaultBalancingStrategy<>();

    private Set<LongEntityID> ids(int count) {
        var gen = new SequentialLongIDGenerator();
        var s = new HashSet<LongEntityID>();
        for (int i = 0; i < count; i++) {
            s.add(gen.generateID());
        }
        return s;
    }

    @Test
    void allChildPartitionsPopulated_evenDistribution() {
        int childCount = 4;
        var entities = ids(20);
        var distribution = strategy.distributeEntities(entities, childCount);

        assertEquals(childCount, distribution.length, "Must return exactly childCount partitions");
        for (int i = 0; i < childCount; i++) {
            assertNotNull(distribution[i], "Partition " + i + " must not be null");
            assertFalse(distribution[i].isEmpty(), "With 20 entities / 4 children each partition gets 5");
        }
    }

    @Test
    void entitiesConserved_noDuplicatesNoLoss() {
        int childCount = 3;
        var entities = ids(17); // intentionally not divisible by childCount
        var distribution = strategy.distributeEntities(entities, childCount);

        // Flatten all children
        Set<LongEntityID> allAssigned = Arrays.stream(distribution)
                                              .flatMap(Set::stream)
                                              .collect(Collectors.toSet());

        assertEquals(entities.size(), allAssigned.size(),
                     "Total entities after distribution must equal input (no loss, no dup)");
        assertEquals(entities, allAssigned, "Exactly the input entities must appear in the distribution");
    }

    @Test
    void singleChild_allEntitiesInChild0() {
        var entities = ids(8);
        var distribution = strategy.distributeEntities(entities, 1);

        assertEquals(1, distribution.length);
        assertEquals(entities, distribution[0], "All entities must go to the single child");
    }

    @Test
    void emptyInput_allChildrenEmpty() {
        int childCount = 4;
        var distribution = strategy.distributeEntities(Set.of(), childCount);

        assertEquals(childCount, distribution.length);
        for (var partition : distribution) {
            assertNotNull(partition);
            assertTrue(partition.isEmpty(), "Empty input must produce empty partitions");
        }
    }
}
