/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.grpc;

import com.hellblazer.luciferase.lucien.balancing.proto.*;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.Point3f;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests proto message serialization/deserialization for balance protocol.
 *
 * @author Hal Hildebrand
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BalanceProtoSerializationTest {

    @Test
    void testRefinementRequestSerialization() {
        var spatialKey = SpatialKey.newBuilder()
            .setMorton(MortonKey.newBuilder().setMortonCode(0x12345L).build())
            .build();

        var request = RefinementRequest.newBuilder()
            .setRequesterRank(1)
            .setRequesterTreeId(1000L)
            .setRoundNumber(3)
            .setTreeLevel(5)
            .addBoundaryKeys(spatialKey)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var bytes = request.toByteArray();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        try {
            var deserialized = RefinementRequest.parseFrom(bytes);
            assertEquals(1, deserialized.getRequesterRank());
            assertEquals(1000L, deserialized.getRequesterTreeId());
            assertEquals(3, deserialized.getRoundNumber());
            assertEquals(5, deserialized.getTreeLevel());
            assertEquals(1, deserialized.getBoundaryKeysCount());
        } catch (Exception e) {
            fail("Failed to deserialize: " + e.getMessage());
        }
    }

    @Test
    void testRefinementResponseSerialization() {
        var ghostElement = GhostElement.newBuilder()
            .setEntityId("entity-123")
            .setPosition(Point3f.newBuilder().setX(1.0f).setY(2.0f).setZ(3.0f).build())
            .setOwnerRank(0)
            .setGlobalTreeId(2000L)
            .setLevel(4)
            .setNeedsRefinement(true)
            .build();

        var response = RefinementResponse.newBuilder()
            .setResponderRank(2)
            .setResponderTreeId(2000L)
            .setRoundNumber(3)
            .addGhostElements(ghostElement)
            .setNeedsFurtherRefinement(true)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var bytes = response.toByteArray();
        assertNotNull(bytes);

        try {
            var deserialized = RefinementResponse.parseFrom(bytes);
            assertEquals(2, deserialized.getResponderRank());
            assertEquals(1, deserialized.getGhostElementsCount());
            assertTrue(deserialized.getNeedsFurtherRefinement());
        } catch (Exception e) {
            fail("Failed to deserialize: " + e.getMessage());
        }
    }

    @Test
    void testBalanceStatisticsSerialization() {
        var stats = BalanceStatistics.newBuilder()
            .setTotalRoundsCompleted(5)
            .setTotalRefinementsRequested(20)
            .setTotalRefinementsApplied(18)
            .setTotalTimeMicros(1000000L)
            .addRoundTimesMicros(150000L)
            .putRefinementsPerRank(0, 5)
            .build();

        var bytes = stats.toByteArray();
        assertNotNull(bytes);

        try {
            var deserialized = BalanceStatistics.parseFrom(bytes);
            assertEquals(5, deserialized.getTotalRoundsCompleted());
            assertEquals(20, deserialized.getTotalRefinementsRequested());
            assertEquals(18, deserialized.getTotalRefinementsApplied());
        } catch (Exception e) {
            fail("Failed to deserialize: " + e.getMessage());
        }
    }
}
