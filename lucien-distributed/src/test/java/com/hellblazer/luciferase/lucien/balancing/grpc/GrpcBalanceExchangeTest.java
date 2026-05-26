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
package com.hellblazer.luciferase.lucien.balancing.grpc;

import com.google.protobuf.ByteString;
import com.hellblazer.luciferase.lucien.balancing.BalanceExchangeException;
import com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker;
import com.hellblazer.luciferase.lucien.balancing.ViolationBatch;
import com.hellblazer.luciferase.lucien.balancing.proto.BalanceViolation;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.ProtobufConverters;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inc3-C2 (RDR-007 Phase 0): boundary tests for {@link GrpcBalanceExchange}, the sole protobuf&lt;-&gt;domain
 * conversion point for the balancing path. Covers the verbatim skip-invalid ghost deserialization (migrated
 * from CrossPartitionBalancePhaseResponseHandlingTest), the non-null refinement-response contract, the
 * {@code StatusRuntimeException}-&gt;{@link BalanceExchangeException} classification, and violation round-trip.
 *
 * @author hal.hildebrand
 */
class GrpcBalanceExchangeTest {

    private MockBalanceCoordinatorClient client;
    private ContentSerializer<String> contentSerializer;
    private GrpcBalanceExchange<MortonKey, LongEntityID, String> adapter;

    @BeforeEach
    void setUp() {
        client = new MockBalanceCoordinatorClient();
        contentSerializer = new ContentSerializer<>() {
            @Override
            public byte[] serialize(String content) {
                return content.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String deserialize(byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }

            @Override
            public String getContentType() {
                return "string";
            }
        };
        adapter = new GrpcBalanceExchange<>(client, contentSerializer, LongEntityID.class);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void requestRefinementSkipsInvalidGhostElements() throws Exception {
        var valid1 = validGhostProto(new MortonKey(100L, (byte) 3), 1001L, "valid-1", 1);
        var invalid = GhostElement.newBuilder()  // missing spatial key -> deserialization fails
            .setEntityId("invalid")
            .setContent(ByteString.copyFromUtf8("x"))
            .setPosition(ProtobufConverters.point3fToProtobuf(new Point3f(0, 0, 0)))
            .setOwnerRank(1)
            .setGlobalTreeId(0L)
            .build();
        var valid2 = validGhostProto(new MortonKey(200L, (byte) 3), 2001L, "valid-2", 2);

        client.refinementResponse = RefinementResponse.newBuilder()
            .setRequesterRank(0)
            .setResponderRank(1)
            .setRoundNumber(1)
            .addGhostElements(valid1)
            .addGhostElements(invalid)
            .addGhostElements(valid2)
            .setNeedsFurtherRefinement(false)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var domain = adapter.requestRefinementAsync(1, 0L, 1, 3, List.of()).get();

        assertEquals(2, domain.ghostElements().size(), "Invalid ghost element must be skipped, valid ones kept");
        assertEquals(new MortonKey(100L, (byte) 3), domain.ghostElements().get(0).getSpatialKey());
        assertEquals("valid-1", domain.ghostElements().get(0).getContent());
        assertEquals(new MortonKey(200L, (byte) 3), domain.ghostElements().get(1).getSpatialKey());
        assertEquals(1, domain.responderRank());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void requestRefinementMapsScalarFieldsAndEmptyGhosts() throws Exception {
        client.refinementResponse = RefinementResponse.newBuilder()
            .setRequesterRank(7)
            .setResponderRank(9)
            .setResponderTreeId(42L)
            .setRoundNumber(3)
            .setNeedsFurtherRefinement(true)
            .setTimestamp(12345L)
            .build();

        var domain = adapter.requestRefinementAsync(9, 42L, 3, 0, List.of()).get();

        assertTrue(domain.ghostElements().isEmpty());
        assertTrue(domain.needsFurtherRefinement());
        assertEquals(7, domain.requesterRank());
        assertEquals(9, domain.responderRank());
        assertEquals(42L, domain.responderTreeId());
        assertEquals(3, domain.roundNumber());
        assertEquals(12345L, domain.timestamp());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void requestRefinementNullClientResponseMapsToEmpty() throws Exception {
        client.refinementResponse = null;  // wrapped client returns null when no connection

        var domain = adapter.requestRefinementAsync(1, 0L, 1, 0, List.of()).get();

        assertNotNull(domain, "Domain refinement response contract is non-null");
        assertTrue(domain.ghostElements().isEmpty());
        assertFalse(domain.needsFurtherRefinement());
    }

    @Test
    void exchangeViolationsUnavailableIsTransient() {
        client.violationError = Status.UNAVAILABLE.asRuntimeException();
        var ex = assertThrows(BalanceExchangeException.class, () -> adapter.exchangeViolations(emptyBatch()));
        assertTrue(ex.isTransient());
        assertFalse(ex.isTimeout());
    }

    @Test
    void exchangeViolationsResourceExhaustedIsTransient() {
        client.violationError = Status.RESOURCE_EXHAUSTED.asRuntimeException();
        var ex = assertThrows(BalanceExchangeException.class, () -> adapter.exchangeViolations(emptyBatch()));
        assertTrue(ex.isTransient());
        assertFalse(ex.isTimeout());
    }

    @Test
    void exchangeViolationsDeadlineExceededIsTimeout() {
        client.violationError = Status.DEADLINE_EXCEEDED.asRuntimeException();
        var ex = assertThrows(BalanceExchangeException.class, () -> adapter.exchangeViolations(emptyBatch()));
        assertFalse(ex.isTransient());
        assertTrue(ex.isTimeout());
    }

    @Test
    void exchangeViolationsOtherStatusIsNeitherTransientNorTimeout() {
        client.violationError = Status.INTERNAL.asRuntimeException();
        var ex = assertThrows(BalanceExchangeException.class, () -> adapter.exchangeViolations(emptyBatch()));
        assertFalse(ex.isTransient());
        assertFalse(ex.isTimeout());
    }

    @Test
    void exchangeViolationsRoundTripsBatchAndViolations() throws Exception {
        var sent = new TwoOneBalanceChecker.BalanceViolation<>(
            new MortonKey(10L, (byte) 5), new MortonKey(20L, (byte) 2), 5, 2, 3, 7);
        var domainBatch = new ViolationBatch<>(0, 1, 4, List.of(sent), 555L);

        client.violationResponse = com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch.newBuilder()
            .setRequesterRank(99)
            .setResponderRank(0)
            .setRoundNumber(4)
            .addViolations(BalanceViolation.newBuilder()
                .setLocalKey(ProtobufConverters.spatialKeyToProtobuf(new MortonKey(30L, (byte) 6)))
                .setGhostKey(ProtobufConverters.spatialKeyToProtobuf(new MortonKey(40L, (byte) 3)))
                .setLocalLevel(6).setGhostLevel(3).setLevelDifference(3).setSourceRank(8)
                .build())
            .setTimestamp(777L)
            .build();

        var received = adapter.exchangeViolations(domainBatch);

        // Sent batch was faithfully serialized to proto.
        assertNotNull(client.lastSentViolationBatch);
        assertEquals(0, client.lastSentViolationBatch.getRequesterRank());
        assertEquals(1, client.lastSentViolationBatch.getResponderRank());
        assertEquals(4, client.lastSentViolationBatch.getRoundNumber());
        assertEquals(1, client.lastSentViolationBatch.getViolationsCount());
        var sentProto = client.lastSentViolationBatch.getViolations(0);
        assertEquals(5, sentProto.getLocalLevel());
        assertEquals(3, sentProto.getLevelDifference());
        assertEquals(7, sentProto.getSourceRank());
        assertEquals(new MortonKey(10L, (byte) 5), ProtobufConverters.spatialKeyFromProtobuf(sentProto.getLocalKey()));

        // Partner response was faithfully deserialized to domain.
        assertEquals(99, received.requesterRank());
        assertEquals(1, received.violations().size());
        var recvViolation = received.violations().get(0);
        assertEquals(new MortonKey(30L, (byte) 6), recvViolation.localKey());
        assertEquals(new MortonKey(40L, (byte) 3), recvViolation.ghostKey());
        assertEquals(3, recvViolation.levelDifference());
        assertEquals(8, recvViolation.sourceRank());
    }

    @Test
    void exchangeViolationsSkipsWireViolationWithLevelDifferenceOne() throws Exception {
        client.violationResponse = com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch.newBuilder()
            .setRequesterRank(1).setResponderRank(0).setRoundNumber(0)
            .addViolations(protoViolation(new MortonKey(1L, (byte) 2), new MortonKey(2L, (byte) 1), 2, 1, 1, 3))
            .setTimestamp(0L)
            .build();

        var received = adapter.exchangeViolations(emptyBatch());

        assertTrue(received.violations().isEmpty(),
                   "A wire violation with levelDifference==1 must be skipped (domain record requires >1)");
    }

    @Test
    void exchangeViolationsSkipsWireViolationWithLevelDifferenceZero() throws Exception {
        client.violationResponse = com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch.newBuilder()
            .setRequesterRank(1).setResponderRank(0).setRoundNumber(0)
            .addViolations(protoViolation(new MortonKey(1L, (byte) 1), new MortonKey(2L, (byte) 1), 1, 1, 0, 3))
            .setTimestamp(0L)
            .build();

        var received = adapter.exchangeViolations(emptyBatch());

        assertTrue(received.violations().isEmpty(),
                   "A wire violation with levelDifference==0 must be skipped");
    }

    @Test
    void exchangeViolationsKeepsOnlyValidViolationsInMixedBatch() throws Exception {
        client.violationResponse = com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch.newBuilder()
            .setRequesterRank(1).setResponderRank(0).setRoundNumber(0)
            .addViolations(protoViolation(new MortonKey(10L, (byte) 5), new MortonKey(11L, (byte) 2), 5, 2, 3, 3))
            .addViolations(protoViolation(new MortonKey(12L, (byte) 2), new MortonKey(13L, (byte) 1), 2, 1, 1, 3))
            .addViolations(protoViolation(new MortonKey(14L, (byte) 4), new MortonKey(15L, (byte) 2), 4, 2, 2, 3))
            .setTimestamp(0L)
            .build();

        var received = adapter.exchangeViolations(emptyBatch());

        assertEquals(2, received.violations().size(),
                     "Only the two valid (levelDifference>1) violations survive; the invalid one is skipped, not the batch");
        assertEquals(3, received.violations().get(0).levelDifference());
        assertEquals(2, received.violations().get(1).levelDifference());
    }

    private com.hellblazer.luciferase.lucien.balancing.proto.BalanceViolation protoViolation(
            MortonKey localKey, MortonKey ghostKey, int localLevel, int ghostLevel, int levelDiff, int sourceRank) {
        return BalanceViolation.newBuilder()
            .setLocalKey(ProtobufConverters.spatialKeyToProtobuf(localKey))
            .setGhostKey(ProtobufConverters.spatialKeyToProtobuf(ghostKey))
            .setLocalLevel(localLevel).setGhostLevel(ghostLevel)
            .setLevelDifference(levelDiff).setSourceRank(sourceRank)
            .build();
    }

    private ViolationBatch<MortonKey> emptyBatch() {
        return new ViolationBatch<>(0, 1, 0, List.of(), 0L);
    }

    private GhostElement validGhostProto(MortonKey key, long entityId, String content, int ownerRank)
            throws ContentSerializer.SerializationException {
        return GhostElement.newBuilder()
            .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(key))
            .setEntityId(ProtobufConverters.entityIdToString(new LongEntityID(entityId)))
            .setContent(ByteString.copyFrom(contentSerializer.serialize(content)))
            .setPosition(ProtobufConverters.point3fToProtobuf(new Point3f(1, 2, 3)))
            .setOwnerRank(ownerRank)
            .setGlobalTreeId(0L)
            .build();
    }

    /** Hand-rolled client stub: settable response/error fields, captures the last sent violation batch. */
    private static class MockBalanceCoordinatorClient extends BalanceCoordinatorClient {
        RefinementResponse refinementResponse;
        com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch violationResponse;
        StatusRuntimeException violationError;
        com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch lastSentViolationBatch;

        MockBalanceCoordinatorClient() {
            super(0, new MockServiceDiscovery());
        }

        @Override
        public CompletableFuture<RefinementResponse> requestRefinementAsync(
                int targetRank, long treeId, int roundNumber, int treeLevel,
                List<com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey> boundaryKeys) {
            return CompletableFuture.completedFuture(refinementResponse);
        }

        @Override
        public com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch exchangeViolations(
                com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch batch) {
            lastSentViolationBatch = batch;
            if (violationError != null) {
                throw violationError;
            }
            return violationResponse;
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
        public Map<Integer, String> getAllEndpoints() {
            return Map.of();
        }
    }
}
