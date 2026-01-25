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
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.ProtobufConverters;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.Builder;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for D.6: CrossPartitionBalancePhase.applyRefinementResponses()
 *
 * Tests the new public method that processes a single RefinementResponse and updates the ghost layer.
 * This complements the existing private method that handles List<RefinementResponse>.
 *
 * @author hal.hildebrand
 */
public class CrossPartitionBalancePhaseResponseHandlingTest {

    private static final Logger log = LoggerFactory.getLogger(CrossPartitionBalancePhaseResponseHandlingTest.class);

    private CrossPartitionBalancePhase<MortonKey, LongEntityID, String> phase;
    private GhostLayer<MortonKey, LongEntityID, String> ghostLayer;
    private Forest<MortonKey, LongEntityID, String> forest;
    private MockBalanceCoordinatorClient client;
    private MockPartitionRegistry registry;
    private RefinementCoordinator coordinator;
    private ContentSerializer<String> contentSerializer;

    @BeforeEach
    public void setUp() {
        forest = new Forest<>(ForestConfig.defaultConfig());
        ghostLayer = new GhostLayer<>(GhostType.FACES);
        client = new MockBalanceCoordinatorClient();
        registry = new MockPartitionRegistry(4);

        phase = new CrossPartitionBalancePhase<>(client, registry, BalanceConfiguration.defaultConfig());

        // Create simple String content serializer
        contentSerializer = new ContentSerializer<>() {
            @Override
            public ByteString serialize(String content) {
                return ByteString.copyFrom(content, StandardCharsets.UTF_8);
            }

            @Override
            public String deserialize(ByteString bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }

            @Override
            public String getContentType() {
                return "string";
            }
        };

        // Set forest context with serialization support
        phase.setForestContext(forest, ghostLayer, contentSerializer, LongEntityID.class);

        // Create coordinator (will be passed to method)
        coordinator = new RefinementCoordinator(client, new RefinementRequestManager(), 0, 4);
    }

    // TEST 1: Null response - should handle gracefully
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_Null() throws Exception {
        var initialGhostCount = ghostLayer.getNumGhostElements();

        // Call with null - should not throw
        assertDoesNotThrow(() -> phase.applyRefinementResponses(null, coordinator),
                          "Should handle null response gracefully");

        // Verify ghost layer unchanged
        assertEquals(initialGhostCount, ghostLayer.getNumGhostElements(),
                    "Ghost layer should be unchanged after null response");
    }

    // TEST 2: Empty response - should return without adding anything
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_EmptyResponse() throws Exception {
        var emptyResponse = RefinementResponse.newBuilder()
            .setRequesterRank(0)
            .setResponderRank(1)
            .setResponderTreeId(0L)
            .setRoundNumber(1)
            .setNeedsFurtherRefinement(false)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var initialGhostCount = ghostLayer.getNumGhostElements();

        phase.applyRefinementResponses(emptyResponse, coordinator);

        // Verify ghost layer unchanged
        assertEquals(initialGhostCount, ghostLayer.getNumGhostElements(),
                    "Ghost layer should be unchanged after empty response");
    }

    // TEST 3: Single ghost element - should add correctly
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_SingleGhostElement() throws Exception {
        var ghostKey = new MortonKey(100L, (byte) 3);
        var entityId = new LongEntityID(1001L);
        var content = "ghost-content-1";
        var position = new Point3f(10.0f, 20.0f, 30.0f);

        var ghostProto = com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(ghostKey))
            .setEntityId(ProtobufConverters.entityIdToString(entityId))
            .setContent(contentSerializer.serialize(content))
            .setPosition(ProtobufConverters.point3fToProtobuf(position))
            .setOwnerRank(1)
            .setGlobalTreeId(0L)
            .build();

        var response = RefinementResponse.newBuilder()
            .setRequesterRank(0)
            .setResponderRank(1)
            .setResponderTreeId(0L)
            .setRoundNumber(1)
            .addGhostElements(ghostProto)
            .setNeedsFurtherRefinement(false)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var initialGhostCount = ghostLayer.getNumGhostElements();

        phase.applyRefinementResponses(response, coordinator);

        // Verify ghost element was added
        assertEquals(initialGhostCount + 1, ghostLayer.getNumGhostElements(),
                    "Should add exactly 1 ghost element");

        // Verify the element is in the ghost layer
        var ghostElements = ghostLayer.getAllGhostElements();
        assertNotNull(ghostElements, "Ghost elements should not be null");
        assertEquals(1, ghostElements.size(), "Should have exactly 1 ghost element");

        var addedElement = ghostElements.get(0);
        assertEquals(ghostKey, addedElement.getSpatialKey(), "Spatial key should match");
        assertEquals(entityId, addedElement.getEntityId(), "Entity ID should match");
        assertEquals(content, addedElement.getContent(), "Content should match");
        assertEquals(1, addedElement.getOwnerRank(), "Owner rank should match");
    }

    // TEST 4: Multiple ghost elements - should add all
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_MultipleGhostElements() throws Exception {
        var responseBuilder = RefinementResponse.newBuilder()
            .setRequesterRank(0)
            .setResponderRank(1)
            .setResponderTreeId(0L)
            .setRoundNumber(1)
            .setNeedsFurtherRefinement(false)
            .setTimestamp(System.currentTimeMillis());

        // Add 5 ghost elements from different ranks
        for (int i = 0; i < 5; i++) {
            var ghostKey = new MortonKey(100L + i, (byte) 3);
            var entityId = new LongEntityID(1000L + i);
            var content = "ghost-content-" + i;
            var position = new Point3f(10.0f + i, 20.0f + i, 30.0f + i);

            var ghostProto = com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
                .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(ghostKey))
                .setEntityId(ProtobufConverters.entityIdToString(entityId))
                .setContent(contentSerializer.serialize(content))
                .setPosition(ProtobufConverters.point3fToProtobuf(position))
                .setOwnerRank(1 + (i % 3)) // Different ranks
                .setGlobalTreeId(0L)
                .build();

            responseBuilder.addGhostElements(ghostProto);
        }

        var response = responseBuilder.build();
        var initialGhostCount = ghostLayer.getNumGhostElements();

        phase.applyRefinementResponses(response, coordinator);

        // Verify all 5 elements were added
        assertEquals(initialGhostCount + 5, ghostLayer.getNumGhostElements(),
                    "Should add all 5 ghost elements");

        // Verify order preserved
        var ghostElements = ghostLayer.getAllGhostElements();
        assertEquals(5, ghostElements.size(), "Should have exactly 5 ghost elements");

        for (int i = 0; i < 5; i++) {
            var element = ghostElements.get(i);
            assertEquals(new MortonKey(100L + i, (byte) 3), element.getSpatialKey(),
                        "Element " + i + " should have correct spatial key");
            assertEquals(new LongEntityID(1000L + i), element.getEntityId(),
                        "Element " + i + " should have correct entity ID");
            assertEquals("ghost-content-" + i, element.getContent(),
                        "Element " + i + " should have correct content");
        }
    }

    // TEST 5: Partial deserialization - invalid element should be skipped
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_PartialDeserialization() throws Exception {
        var responseBuilder = RefinementResponse.newBuilder()
            .setRequesterRank(0)
            .setResponderRank(1)
            .setResponderTreeId(0L)
            .setRoundNumber(1)
            .setNeedsFurtherRefinement(false)
            .setTimestamp(System.currentTimeMillis());

        // Add valid ghost element
        var validKey1 = new MortonKey(100L, (byte) 3);
        var validId1 = new LongEntityID(1001L);
        var validProto1 = com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(validKey1))
            .setEntityId(ProtobufConverters.entityIdToString(validId1))
            .setContent(contentSerializer.serialize("valid-1"))
            .setPosition(ProtobufConverters.point3fToProtobuf(new Point3f(10.0f, 20.0f, 30.0f)))
            .setOwnerRank(1)
            .setGlobalTreeId(0L)
            .build();
        responseBuilder.addGhostElements(validProto1);

        // Add invalid ghost element (missing spatial key)
        var invalidProto = com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setEntityId("invalid-id")
            .setContent(ByteString.copyFromUtf8("invalid"))
            .setPosition(ProtobufConverters.point3fToProtobuf(new Point3f(0, 0, 0)))
            .setOwnerRank(1)
            .setGlobalTreeId(0L)
            .build();
        responseBuilder.addGhostElements(invalidProto);

        // Add another valid ghost element
        var validKey2 = new MortonKey(200L, (byte) 3);
        var validId2 = new LongEntityID(2001L);
        var validProto2 = com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(validKey2))
            .setEntityId(ProtobufConverters.entityIdToString(validId2))
            .setContent(contentSerializer.serialize("valid-2"))
            .setPosition(ProtobufConverters.point3fToProtobuf(new Point3f(40.0f, 50.0f, 60.0f)))
            .setOwnerRank(2)
            .setGlobalTreeId(0L)
            .build();
        responseBuilder.addGhostElements(validProto2);

        var response = responseBuilder.build();
        var initialGhostCount = ghostLayer.getNumGhostElements();

        // Should not throw - just skip invalid element
        assertDoesNotThrow(() -> phase.applyRefinementResponses(response, coordinator),
                          "Should skip invalid element without throwing");

        // Verify only valid elements (2) were added
        assertEquals(initialGhostCount + 2, ghostLayer.getNumGhostElements(),
                    "Should add only 2 valid ghost elements, skipping invalid one");

        var ghostElements = ghostLayer.getAllGhostElements();
        assertEquals(2, ghostElements.size(), "Should have exactly 2 ghost elements");

        // Verify valid elements were added
        var element1 = ghostElements.get(0);
        assertEquals(validKey1, element1.getSpatialKey(), "First element should have correct key");
        assertEquals("valid-1", element1.getContent(), "First element should have correct content");

        var element2 = ghostElements.get(1);
        assertEquals(validKey2, element2.getSpatialKey(), "Second element should have correct key");
        assertEquals("valid-2", element2.getContent(), "Second element should have correct content");
    }

    // TEST 6: Integration with real GhostLayer
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_IntegrationWithGhostLayer() throws Exception {
        // Create response with diverse elements (different levels, ranks, positions)
        var responseBuilder = RefinementResponse.newBuilder()
            .setRequesterRank(0)
            .setResponderRank(1)
            .setResponderTreeId(0L)
            .setRoundNumber(1)
            .setNeedsFurtherRefinement(false)
            .setTimestamp(System.currentTimeMillis());

        // Level 2, rank 1
        responseBuilder.addGhostElements(createGhostProto(
            new MortonKey(100L, (byte) 2), 1001L, "content-1", new Point3f(1, 2, 3), 1
        ));

        // Level 4, rank 2
        responseBuilder.addGhostElements(createGhostProto(
            new MortonKey(200L, (byte) 4), 2001L, "content-2", new Point3f(10, 20, 30), 2
        ));

        // Level 3, rank 3
        responseBuilder.addGhostElements(createGhostProto(
            new MortonKey(300L, (byte) 3), 3001L, "content-3", new Point3f(100, 200, 300), 3
        ));

        var response = responseBuilder.build();

        phase.applyRefinementResponses(response, coordinator);

        // Verify all elements in ghost layer
        assertEquals(3, ghostLayer.getNumGhostElements(), "Should have all 3 ghost elements");

        // Verify subsequent queries work correctly
        var allGhosts = ghostLayer.getAllGhostElements();
        assertEquals(3, allGhosts.size(), "getAllGhostElements should return 3 elements");

        // Verify element properties
        assertTrue(allGhosts.stream().anyMatch(g -> g.getOwnerRank() == 1), "Should have element from rank 1");
        assertTrue(allGhosts.stream().anyMatch(g -> g.getOwnerRank() == 2), "Should have element from rank 2");
        assertTrue(allGhosts.stream().anyMatch(g -> g.getOwnerRank() == 3), "Should have element from rank 3");

        // Verify content was deserialized correctly
        assertTrue(allGhosts.stream().anyMatch(g -> "content-1".equals(g.getContent())), "Should have content-1");
        assertTrue(allGhosts.stream().anyMatch(g -> "content-2".equals(g.getContent())), "Should have content-2");
        assertTrue(allGhosts.stream().anyMatch(g -> "content-3".equals(g.getContent())), "Should have content-3");
    }

    // Helper method to create GhostElement protobuf
    private com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement createGhostProto(
            MortonKey key, long entityId, String content, Point3f position, int ownerRank)
            throws ContentSerializer.SerializationException {
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(key))
            .setEntityId(ProtobufConverters.entityIdToString(new LongEntityID(entityId)))
            .setContent(contentSerializer.serialize(content))
            .setPosition(ProtobufConverters.point3fToProtobuf(position))
            .setOwnerRank(ownerRank)
            .setGlobalTreeId(0L)
            .build();
    }

    // Mock classes (reused from CrossPartitionBalancePhaseTest)
    private static class MockBalanceCoordinatorClient extends BalanceCoordinatorClient {
        public MockBalanceCoordinatorClient() {
            super(0, new MockServiceDiscovery());
        }
    }

    private static class MockServiceDiscovery implements BalanceCoordinatorClient.ServiceDiscovery {
        @Override
        public String getEndpoint(int rank) {
            return "localhost:" + (50000 + rank);
        }

        @Override
        public void registerEndpoint(int rank, String endpoint) {
        }

        @Override
        public java.util.Map<Integer, String> getAllEndpoints() {
            return new java.util.HashMap<>();
        }
    }

    private static class MockPartitionRegistry implements ParallelBalancer.PartitionRegistry {
        private final int partitionCount;

        public MockPartitionRegistry(int partitionCount) {
            this.partitionCount = partitionCount;
        }

        @Override
        public int getCurrentPartitionId() {
            return 0;
        }

        @Override
        public int getPartitionCount() {
            return partitionCount;
        }

        @Override
        public void barrier(int round) {
        }

        @Override
        public void requestRefinement(Object elementKey) {
        }

        @Override
        public int getPendingRefinements() {
            return 0;
        }
    }
}
