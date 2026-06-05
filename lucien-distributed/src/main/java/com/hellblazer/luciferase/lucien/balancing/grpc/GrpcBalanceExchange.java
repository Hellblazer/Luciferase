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

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.BalanceExchangeException;
import com.hellblazer.luciferase.lucien.balancing.RefinementExchange;
import com.hellblazer.luciferase.lucien.balancing.RefinementResponse;
import com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker;
import com.hellblazer.luciferase.lucien.balancing.ViolationBatch;
import com.hellblazer.luciferase.lucien.balancing.ViolationExchange;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.ProtobufConverters;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * gRPC boundary adapter for the balancing exchange ports (RDR-007 Phase 0 Inc3).
 *
 * <p>Implements the lucien-core {@link RefinementExchange} and {@link ViolationExchange} ports over the
 * concrete {@link BalanceCoordinatorClient}, performing all protobuf&lt;-&gt;domain conversion at this single
 * boundary. Mirrors the Inc2b {@code GhostServiceClient} pattern: ghost-element deserialization (with
 * per-element skip-invalid resilience) lives here, not in the core balancing classes.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public class GrpcBalanceExchange<Key extends SpatialKey<Key>, ID extends EntityID, Content>
    implements RefinementExchange<Key, ID, Content>, ViolationExchange<Key> {

    private static final Logger log = LoggerFactory.getLogger(GrpcBalanceExchange.class);

    private final BalanceCoordinatorClient client;
    private final ContentSerializer<Content> contentSerializer;
    private final Class<ID> idType;
    private final Class<Key> keyType;

    public GrpcBalanceExchange(BalanceCoordinatorClient client,
                               ContentSerializer<Content> contentSerializer,
                               Class<ID> idType,
                               Class<Key> keyType) {
        // The serializer and idType are required: the old CrossPartitionBalancePhase "skip deserialization
        // when contentSerializer == null" backward-compat path is intentionally NOT carried over — that was
        // a crutch for incomplete wiring, not a designed-for state. The boundary always deserializes.
        // keyType enables the type-id guard in toDomainViolation (Luciferase-7wzml.89).
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.contentSerializer = Objects.requireNonNull(contentSerializer, "contentSerializer cannot be null");
        this.idType = Objects.requireNonNull(idType, "idType cannot be null");
        this.keyType = Objects.requireNonNull(keyType, "keyType cannot be null");
    }

    @Override
    public CompletableFuture<RefinementResponse<Key, ID, Content>> requestRefinementAsync(
            int targetRank, long treeId, int roundNumber, int treeLevel, List<Key> boundaryKeys) {
        var protoKeys = new ArrayList<com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey>(boundaryKeys.size());
        for (var key : boundaryKeys) {
            protoKeys.add(ProtobufConverters.spatialKeyToProtobuf(key));
        }
        return client.requestRefinementAsync(targetRank, treeId, roundNumber, treeLevel, protoKeys)
                     .thenApply(this::toDomainResponse);
    }

    private RefinementResponse<Key, ID, Content> toDomainResponse(
            com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse proto) {
        // The wrapped client returns null when no connection is available. The domain port contract is
        // non-null (downstream reads ghostElements()), so map null to an empty response.
        if (proto == null) {
            return RefinementResponse.empty();
        }

        var ghostElements = new ArrayList<GhostElement<Key, ID, Content>>();
        for (var ghostProto : proto.getGhostElementsList()) {
            // Verbatim move of CrossPartitionBalancePhase.applyRefinementResponses skip-invalid resilience:
            // a PER-ELEMENT guard (not around the whole loop) so one bad element does not drop the rest.
            try {
                GhostElement<Key, ID, Content> ghost =
                    ProtobufConverters.ghostElementFromProtobuf(ghostProto, contentSerializer, idType);
                ghostElements.add(ghost);
            } catch (ContentSerializer.SerializationException e) {
                log.warn("Failed to deserialize ghost element from refinement response: {}", e.getMessage());
            } catch (Exception e) {
                log.warn("Unexpected error deserializing ghost element from refinement response: {}",
                         e.getMessage(), e);
            }
        }

        return new RefinementResponse<>(
            proto.getRequesterRank(),
            proto.getResponderRank(),
            proto.getResponderTreeId(),
            proto.getRoundNumber(),
            ghostElements,
            proto.getNeedsFurtherRefinement(),
            proto.getTimestamp());
    }

    @Override
    public ViolationBatch<Key> exchangeViolations(ViolationBatch<Key> batch) throws BalanceExchangeException {
        var protoBatch = toProtoBatch(batch);
        try {
            var protoResponse = client.exchangeViolations(protoBatch);
            // The wrapped client returns null when no connection is available; preserve the passthrough so
            // the caller's graceful-degradation (empty-result) path is unchanged.
            return protoResponse == null ? null : toDomainBatch(protoResponse);
        } catch (StatusRuntimeException e) {
            var code = e.getStatus().getCode();
            boolean transientFailure = code == Status.Code.UNAVAILABLE || code == Status.Code.RESOURCE_EXHAUSTED;
            boolean timeout = code == Status.Code.DEADLINE_EXCEEDED;
            throw new BalanceExchangeException(
                "Violation exchange failed with gRPC status " + e.getStatus(), e, transientFailure, timeout);
        }
    }

    private com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch toProtoBatch(ViolationBatch<Key> batch) {
        var builder = com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch.newBuilder()
            .setRequesterRank(batch.requesterRank())
            .setResponderRank(batch.responderRank())
            .setRoundNumber(batch.roundNumber())
            .setTimestamp(batch.timestamp());
        for (var violation : batch.violations()) {
            builder.addViolations(toProtoViolation(violation));
        }
        return builder.build();
    }

    private com.hellblazer.luciferase.lucien.balancing.proto.BalanceViolation toProtoViolation(
            TwoOneBalanceChecker.BalanceViolation<Key> v) {
        return com.hellblazer.luciferase.lucien.balancing.proto.BalanceViolation.newBuilder()
            .setLocalKey(ProtobufConverters.spatialKeyToProtobuf(v.localKey()))
            .setGhostKey(ProtobufConverters.spatialKeyToProtobuf(v.ghostKey()))
            .setLocalLevel(v.localLevel())
            .setGhostLevel(v.ghostLevel())
            .setLevelDifference(v.levelDifference())
            .setSourceRank(v.sourceRank())
            .build();
    }

    private ViolationBatch<Key> toDomainBatch(com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch proto) {
        var violations = new ArrayList<TwoOneBalanceChecker.BalanceViolation<Key>>();
        for (var protoViolation : proto.getViolationsList()) {
            // Per-element guard: a wrong-type key (ClassCastException) or an unregistered/malformed
            // type_id (IllegalArgumentException from spatialKeyFromProtobuf) must skip this violation
            // and log loudly — not abort the whole batch.  Mirrors the per-element resilience used in
            // toDomainResponse for ghost elements (Luciferase-7wzml.89).
            try {
                var domain = toDomainViolation(protoViolation);
                if (domain != null) {
                    violations.add(domain);
                }
            } catch (ClassCastException | IllegalArgumentException e) {
                // ERROR, not WARN: a key-type mismatch means a peer is sending a fundamentally wrong index
                // type (e.g. TetreeKey into a MortonKey exchange) — a systematic misconfiguration, not a
                // transient per-element glitch. WARN risks silent suppression in high-volume distributed
                // deployments, stalling balancing invisibly (RDR-004-class drop).
                log.error("Skipping violation with incompatible or malformed key type: {}", e.getMessage());
            }
        }
        return new ViolationBatch<>(
            proto.getRequesterRank(),
            proto.getResponderRank(),
            proto.getRoundNumber(),
            violations,
            proto.getTimestamp());
    }

    @SuppressWarnings("unchecked")
    private TwoOneBalanceChecker.BalanceViolation<Key> toDomainViolation(
            com.hellblazer.luciferase.lucien.balancing.proto.BalanceViolation proto) {
        // The domain record enforces levelDifference > 1. The wire always satisfies this (violations are
        // only created when the difference exceeds 1), but guard defensively and skip a malformed entry
        // rather than letting the record's constructor abort the whole batch conversion.
        if (proto.getLevelDifference() <= 1) {
            log.warn("Skipping wire violation with non-violating levelDifference={}", proto.getLevelDifference());
            return null;
        }
        var rawLocalKey = ProtobufConverters.spatialKeyFromProtobuf(proto.getLocalKey());
        var rawGhostKey = ProtobufConverters.spatialKeyFromProtobuf(proto.getGhostKey());
        // Type-id guard (Luciferase-7wzml.89): because the cast (Key) is erased at runtime and would
        // not throw, perform an explicit instanceof check here so a peer sending a wrong-type key
        // (e.g. TetreeKey into a MortonKey-typed exchange) fails loudly per element rather than
        // silently corrupting downstream consumers.
        if (!keyType.isInstance(rawLocalKey) || !keyType.isInstance(rawGhostKey)) {
            throw new IllegalArgumentException(
                "Violation key type mismatch: expected " + keyType.getSimpleName()
                + " but received local=" + rawLocalKey.getClass().getSimpleName()
                + " ghost=" + rawGhostKey.getClass().getSimpleName());
        }
        var localKey = (Key) rawLocalKey;
        var ghostKey = (Key) rawGhostKey;
        return new TwoOneBalanceChecker.BalanceViolation<>(
            localKey, ghostKey, proto.getLocalLevel(), proto.getGhostLevel(),
            proto.getLevelDifference(), proto.getSourceRank());
    }
}
