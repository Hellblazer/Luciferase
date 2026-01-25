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

import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorClient;
import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorServer;
import com.hellblazer.luciferase.lucien.balancing.proto.*;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for DistributedViolationAggregator with real gRPC infrastructure.
 *
 * @author hal.hildebrand
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DistributedViolationAggregatorIntegrationTest {

    private static final int TOTAL_PARTITIONS = 4;

    private List<Server> servers;
    private List<ManagedChannel> channels;
    private List<BalanceCoordinatorClient> clients;
    private List<DistributedViolationAggregator> aggregators;
    private Map<Integer, MockBalanceProvider> balanceProviders;

    @BeforeEach
    void setUp() throws Exception {
        servers = new ArrayList<>();
        channels = new ArrayList<>();
        clients = new ArrayList<>();
        aggregators = new ArrayList<>();
        balanceProviders = new ConcurrentHashMap<>();

        // Create 4 partitions with gRPC servers and clients
        for (int rank = 0; rank < TOTAL_PARTITIONS; rank++) {
            var serverName = "test-partition-" + rank;
            var provider = new MockBalanceProvider(rank);
            balanceProviders.put(rank, provider);

            // Start gRPC server
            var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new BalanceCoordinatorServer(provider))
                .build()
                .start();
            servers.add(server);
        }

        // Create clients and aggregators for each partition
        for (int rank = 0; rank < TOTAL_PARTITIONS; rank++) {
            var discovery = new MockServiceDiscovery(rank);
            var client = new BalanceCoordinatorClient(rank, discovery);
            clients.add(client);

            var aggregator = new DistributedViolationAggregator(rank, TOTAL_PARTITIONS, client);
            aggregators.add(aggregator);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        // Shutdown aggregators
        aggregators.forEach(DistributedViolationAggregator::shutdown);

        // Shutdown clients
        clients.forEach(BalanceCoordinatorClient::shutdown);

        // Shutdown channels
        channels.forEach(channel -> {
            channel.shutdownNow();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Shutdown servers
        servers.forEach(server -> {
            server.shutdownNow();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    void testFullDistributedAggregation() {
        // Each partition has different violations
        var partition0Violations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );
        var partition1Violations = List.of(
            createViolation(3, 4, 6, 8, 2, 1)
        );
        var partition2Violations = List.of(
            createViolation(5, 6, 7, 9, 2, 2)
        );
        var partition3Violations = List.of(
            createViolation(7, 8, 8, 10, 2, 3)
        );

        var allViolations = new ArrayList<BalanceViolation>();
        allViolations.addAll(partition0Violations);
        allViolations.addAll(partition1Violations);
        allViolations.addAll(partition2Violations);
        allViolations.addAll(partition3Violations);

        // Store violations in providers for exchange
        balanceProviders.get(0).setViolations(partition0Violations);
        balanceProviders.get(1).setViolations(partition1Violations);
        balanceProviders.get(2).setViolations(partition2Violations);
        balanceProviders.get(3).setViolations(partition3Violations);

        // Each partition aggregates - should all get the same global result
        var results = new ArrayList<Set<BalanceViolation>>();
        for (int rank = 0; rank < TOTAL_PARTITIONS; rank++) {
            var localViolations = switch (rank) {
                case 0 -> partition0Violations;
                case 1 -> partition1Violations;
                case 2 -> partition2Violations;
                case 3 -> partition3Violations;
                default -> throw new IllegalStateException("Unexpected rank: " + rank);
            };

            var result = aggregators.get(rank).aggregateDistributed(localViolations);
            results.add(result);
        }

        // Verify all partitions converged to the same global set
        for (var result : results) {
            assertNotNull(result);
            assertEquals(4, result.size(), "Each partition should have all 4 violations");
            assertTrue(result.containsAll(allViolations), "Result should contain all violations");
        }

        // Verify metrics
        for (int rank = 0; rank < TOTAL_PARTITIONS; rank++) {
            var metrics = aggregators.get(rank).getMetrics();
            assertTrue((long) metrics.get("successfulExchanges") > 0,
                      "Rank " + rank + " should have successful exchanges");
        }
    }

    @Test
    void testDeduplicationAcrossPartitions() {
        // Partitions 0 and 1 both report the same violation
        var duplicateViolation = createViolation(100, 200, 5, 7, 2, 0);

        var partition0Violations = List.of(duplicateViolation);
        var partition1Violations = List.of(duplicateViolation);

        balanceProviders.get(0).setViolations(partition0Violations);
        balanceProviders.get(1).setViolations(partition1Violations);

        // Aggregate from partition 0
        var result = aggregators.get(0).aggregateDistributed(partition0Violations);

        // Should deduplicate to 1 violation
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(duplicateViolation));
    }

    /**
     * Helper to create a BalanceViolation for testing.
     */
    private BalanceViolation createViolation(long localKeyId, long ghostKeyId,
                                            int localLevel, int ghostLevel,
                                            int levelDiff, int sourceRank) {
        return BalanceViolation.newBuilder()
            .setLocalKey(SpatialKey.newBuilder()
                .setMorton(MortonKey.newBuilder().setMortonCode(localKeyId).build())
                .build())
            .setGhostKey(SpatialKey.newBuilder()
                .setMorton(MortonKey.newBuilder().setMortonCode(ghostKeyId).build())
                .build())
            .setLocalLevel(localLevel)
            .setGhostLevel(ghostLevel)
            .setLevelDifference(levelDiff)
            .setSourceRank(sourceRank)
            .build();
    }

    /**
     * Mock service discovery for in-process testing.
     */
    private static class MockServiceDiscovery implements BalanceCoordinatorClient.ServiceDiscovery {
        private final int myRank;

        MockServiceDiscovery(int myRank) {
            this.myRank = myRank;
        }

        @Override
        public String getEndpoint(int rank) {
            // Return in-process channel name
            return "test-partition-" + rank;
        }

        @Override
        public void registerEndpoint(int rank, String endpoint) {
            // Not needed for test
        }

        @Override
        public Map<Integer, String> getAllEndpoints() {
            var endpoints = new HashMap<Integer, String>();
            for (int rank = 0; rank < TOTAL_PARTITIONS; rank++) {
                endpoints.put(rank, "test-partition-" + rank);
            }
            return endpoints;
        }
    }

    /**
     * Mock balance provider that stores violations for exchange.
     */
    private static class MockBalanceProvider implements BalanceCoordinatorServer.BalanceProvider {
        private final int rank;
        private final List<BalanceViolation> violations;

        MockBalanceProvider(int rank) {
            this.rank = rank;
            this.violations = new ArrayList<>();
        }

        void setViolations(List<BalanceViolation> violations) {
            this.violations.clear();
            this.violations.addAll(violations);
        }

        @Override
        public int getCurrentRank() {
            return rank;
        }

        @Override
        public List<GhostElement> getGhostElementsForRefinement(RefinementRequest request) {
            return List.of();
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
                .setTotalRefinementsRequested(0)
                .setTotalRefinementsApplied(0)
                .setTotalTimeMicros(0)
                .build();
        }

        @Override
        public void recordRefinementRequest(RefinementRequest request) {
            // Not needed for violation exchange test
        }

        @Override
        public void recordRefinementApplied(int rank, int count) {
            // Not needed for violation exchange test
        }

        @Override
        public ViolationBatch processViolations(ViolationBatch batch) {
            // Return this partition's violations for bidirectional exchange
            var responseBuilder = ViolationBatch.newBuilder()
                .setRequesterRank(getCurrentRank())
                .setResponderRank(batch.getRequesterRank())
                .setRoundNumber(batch.getRoundNumber())
                .setTimestamp(System.currentTimeMillis());

            // Add this partition's violations
            for (var violation : violations) {
                responseBuilder.addViolations(violation);
            }

            return responseBuilder.build();
        }
    }
}
