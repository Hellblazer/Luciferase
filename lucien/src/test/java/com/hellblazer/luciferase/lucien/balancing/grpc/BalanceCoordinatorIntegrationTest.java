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
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for BalanceCoordinator using InProcessChannelBuilder.
 *
 * @author Hal Hildebrand
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BalanceCoordinatorIntegrationTest {

    private Server server;
    private ManagedChannel channel;
    private BalanceCoordinatorGrpc.BalanceCoordinatorBlockingStub blockingStub;
    private MockBalanceProvider balanceProvider;
    private String serverName;

    @BeforeEach
    void setUp() throws Exception {
        serverName = "test-server-" + System.nanoTime();
        balanceProvider = new MockBalanceProvider();

        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new BalanceCoordinatorServer(balanceProvider))
            .build()
            .start();

        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();

        blockingStub = BalanceCoordinatorGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testRequestRefinement() {
        var spatialKey = SpatialKey.newBuilder()
            .setMorton(MortonKey.newBuilder().setMortonCode(0x12345L).build())
            .build();

        var request = RefinementRequest.newBuilder()
            .setRequesterRank(1)
            .setRequesterTreeId(1000L)
            .setRoundNumber(1)
            .setTreeLevel(5)
            .addBoundaryKeys(spatialKey)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var response = blockingStub.requestRefinement(request);

        assertNotNull(response);
        assertEquals(0, response.getResponderRank());
        assertEquals(1000L, response.getResponderTreeId());
        assertEquals(1, response.getRoundNumber());
    }

    @Test
    void testCoordinateBalance() {
        var request = BalanceCoordinationRequest.newBuilder()
            .setInitiatorRank(0)
            .setInitiatorTreeId(5000L)
            .setTotalPartitions(4)
            .setMaxRounds(10)
            .setRefinementThreshold(0.2f)
            .build();

        var response = blockingStub.coordinateBalance(request);

        assertNotNull(response);
        assertTrue(response.getCoordinationAccepted());
        assertEquals(1, response.getAssignedRound());
    }

    @Test
    void testGetBalanceStatistics() {
        balanceProvider.recordRefinementRequest(1);
        balanceProvider.recordRefinementApplied(1);

        var request = BalanceCoordinationRequest.newBuilder()
            .setInitiatorRank(0)
            .setInitiatorTreeId(0L)
            .setTotalPartitions(1)
            .setMaxRounds(1)
            .setRefinementThreshold(0.2f)
            .build();

        var stats = blockingStub.getBalanceStatistics(request);

        assertNotNull(stats);
        assertTrue(stats.getTotalRefinementsRequested() >= 1);
    }

    /**
     * Mock balance provider for testing.
     */
    private static class MockBalanceProvider implements BalanceCoordinatorServer.BalanceProvider {
        private final List<GhostElement> ghostElements = new ArrayList<>();
        private final AtomicInteger refinementsRequested = new AtomicInteger(0);
        private final AtomicInteger refinementsApplied = new AtomicInteger(0);

        public void recordRefinementRequest(int rank) {
            refinementsRequested.incrementAndGet();
        }

        public void recordRefinementApplied(int rank) {
            refinementsApplied.incrementAndGet();
        }

        @Override
        public int getCurrentRank() {
            return 0;
        }

        @Override
        public List<GhostElement> getGhostElementsForRefinement(RefinementRequest request) {
            return new ArrayList<>(ghostElements);
        }

        @Override
        public boolean acceptCoordination(BalanceCoordinationRequest request) {
            return true;
        }

        @Override
        public int assignRound(BalanceCoordinationRequest request) {
            return 1;
        }

        @Override
        public BalanceStatistics getStatistics() {
            return BalanceStatistics.newBuilder()
                .setTotalRoundsCompleted(0)
                .setTotalRefinementsRequested(refinementsRequested.get())
                .setTotalRefinementsApplied(refinementsApplied.get())
                .setTotalTimeMicros(0)
                .build();
        }

        @Override
        public void recordRefinementRequest(RefinementRequest request) {
            refinementsRequested.incrementAndGet();
        }

        @Override
        public void recordRefinementApplied(int rank, int count) {
            refinementsApplied.addAndGet(count);
        }
    }
}
