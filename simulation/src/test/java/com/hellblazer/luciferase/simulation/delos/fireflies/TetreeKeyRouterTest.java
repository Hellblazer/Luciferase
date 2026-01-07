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

package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test for TetreeKeyRouter - deterministic routing based on TetreeKey.
 * <p>
 * These tests verify that the router provides:
 * 1. Deterministic routing from TetreeKey to Member
 * 2. Position-based routing via TetreeKey conversion
 * 3. Consistent hash-based member selection
 *
 * @author hal.hildebrand
 */
class TetreeKeyRouterTest {

    private DynamicContext<Member> mockContext;
    private TetreeKeyRouter        router;
    private Member                 member1;
    private Member                 member2;
    private Member                 member3;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockContext = mock(DynamicContext.class);
        router = new TetreeKeyRouter(mockContext);

        // Create mock members with distinct digests
        member1 = mock(Member.class);
        member2 = mock(Member.class);
        member3 = mock(Member.class);

        var digest1 = mock(Digest.class);
        var digest2 = mock(Digest.class);
        var digest3 = mock(Digest.class);

        when(member1.getId()).thenReturn(digest1);
        when(member2.getId()).thenReturn(digest2);
        when(member3.getId()).thenReturn(digest3);

        when(digest1.toString()).thenReturn("digest1");
        when(digest2.toString()).thenReturn("digest2");
        when(digest3.toString()).thenReturn("digest3");
    }

    /**
     * Test 1: Verify routeTo() provides deterministic routing for TetreeKeys
     */
    @Test
    void testRouteTo() {
        // Given: A context with multiple members
        when(mockContext.size()).thenReturn(3);

        var key = TetreeKey.getRoot();  // Use root key for testing

        // When: We route to the same key multiple times
        var route1 = router.routeTo(key);
        var route2 = router.routeTo(key);
        var route3 = router.routeTo(key);

        // Then: Should return consistent member index
        assertThat(route1)
            .as("Route should be deterministic for same key")
            .isEqualTo(route2)
            .isEqualTo(route3);

        assertThat(route1)
            .as("Route should be within member range")
            .isGreaterThanOrEqualTo(0)
            .isLessThan(3);
    }

    /**
     * Test 2: Verify position-based routing converts to TetreeKey
     */
    @Test
    void testPositionRouting() {
        // Given: A specific position
        when(mockContext.size()).thenReturn(3);

        var position = new Point3D(0.5f, 0.5f, 0.5f);
        var cubeSizeMeters = 10.0f;
        var maxLevel = 5;

        // When: We route to the same position multiple times
        var route1 = router.routeToPosition(position, cubeSizeMeters, maxLevel);
        var route2 = router.routeToPosition(position, cubeSizeMeters, maxLevel);

        // Then: Should return consistent member index
        assertThat(route1)
            .as("Position routing should be deterministic")
            .isEqualTo(route2);

        assertThat(route1)
            .as("Route should be within member range")
            .isGreaterThanOrEqualTo(0)
            .isLessThan(3);
    }

    /**
     * Test 3: Verify different keys produce different routes
     */
    @Test
    void testDeterministicHashing() {
        // Given: A context with multiple members
        when(mockContext.size()).thenReturn(10);

        // When: We route to different TetreeKeys
        // Get root tetrahedral cell and its children
        var rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        var key1 = rootTet.tmIndex();
        var key2 = rootTet.child(0).tmIndex();  // First child
        var key3 = rootTet.child(1).tmIndex();  // Second child

        var route1 = router.routeTo(key1);
        var route2 = router.routeTo(key2);
        var route3 = router.routeTo(key3);

        // Then: Different keys should likely route to different members
        // (With 10 members, high probability of getting different routes)
        var routes = List.of(route1, route2, route3);
        var uniqueRoutes = routes.stream().distinct().count();

        assertThat(uniqueRoutes)
            .as("Different keys should produce varied routes (distribution check)")
            .isGreaterThanOrEqualTo(2);  // At least 2 different routes out of 3

        // All routes should be valid
        assertThat(routes).allMatch(r -> r >= 0 && r < 10);
    }
}
