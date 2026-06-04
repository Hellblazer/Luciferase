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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.distributed.migration.EntitySnapshot;
import com.hellblazer.luciferase.simulation.distributed.migration.IdempotencyToken;

import javax.vecmath.Point3d;
import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Bidirectional converter between Message (domain) and TransportVonMessage (wire format).
 * <p>
 * Converts all Message sealed interface types:
 * - JoinRequest, JoinResponse, Move, Leave, GhostSync, Ack, Query
 * <p>
 * Design:
 * - toTransport: Pattern matches on message type to extract serializable fields
 * - fromTransport: Uses type field to dispatch reconstruction of correct subtype
 *
 * @author hal.hildebrand
 */
public class MessageConverter {

    /**
     * Convert domain Message to serializable TransportVonMessage.
     * <p>
     * Pattern matches all Message subtypes and extracts their fields
     * into the wire format. GhostSync messages include the ghost list and bucket.
     *
     * @param message Domain Message
     * @return TransportVonMessage ready for serialization
     * @throws IllegalArgumentException if message type is unknown
     */
    public static TransportVonMessage toTransport(Message message) {
        return switch (message) {
            case Message.GhostSync ghostSync ->
                ghostSyncToTransport(ghostSync);
            case Message.JoinRequest joinReq ->
                joinRequestToTransport(joinReq);
            case Message.JoinResponse joinResp ->
                joinResponseToTransport(joinResp);
            case Message.Move move ->
                moveToTransport(move);
            case Message.Leave leave ->
                leaveToTransport(leave);
            case Message.Ack ack ->
                ackToTransport(ack);
            case Message.Query query ->
                queryToTransport(query);
            case Message.QueryResponse queryResp ->
                queryResponseToTransport(queryResp);
            case MigrationProtocolMessages migration ->
                migrationToTransport(migration);
            default ->
                throw new IllegalArgumentException("Unknown Message type: " + message.getClass().getSimpleName());
        };
    }

    /**
     * Convert serializable TransportVonMessage back to domain Message.
     * <p>
     * Uses type field to dispatch reconstruction of correct subtype.
     * Handles GhostSync specially by reconstructing the ghost list.
     *
     * @param transport Wire-format message
     * @return Reconstructed Message
     * @throws IllegalArgumentException if type is unknown
     */
    public static Message fromTransport(TransportVonMessage transport) {
        return switch (transport.type()) {
            case "GhostSync" ->
                ghostSyncFromTransport(transport);
            case "JoinRequest" ->
                joinRequestFromTransport(transport);
            case "JoinResponse" ->
                joinResponseFromTransport(transport);
            case "Move" ->
                moveFromTransport(transport);
            case "Leave" ->
                leaveFromTransport(transport);
            case "Ack" ->
                ackFromTransport(transport);
            case "Query" ->
                queryFromTransport(transport);
            case "QueryResponse" ->
                queryResponseFromTransport(transport);
            case "Migration" ->
                migrationFromTransport(transport);
            default ->
                throw new IllegalArgumentException("Unknown message type: " + transport.type());
        };
    }

    // ==================== GhostSync Conversion ====================

    private static TransportVonMessage ghostSyncToTransport(Message.GhostSync msg) {
        var ghosts = new ArrayList<TransportGhostData>(msg.ghosts().size());
        for (var ghost : msg.ghosts()) {
            ghosts.add(TransportGhostData.from(ghost));
        }

        return new TransportVonMessage(
            "GhostSync",
            msg.sourceBubbleId().toString(),
            msg.sourceBubbleId().toString(),  // No specific target in GhostSync
            0f, 0f, 0f,  // Position not used for GhostSync
            "",  // Entity ID not used for GhostSync
            msg.timestamp(),
            ghosts,
            msg.bucket(),
            null,  // neighbors not used for GhostSync
            null   // queryId not used for GhostSync
        );
    }

    private static Message ghostSyncFromTransport(TransportVonMessage transport) {
        var sourceBubbleId = UUID.fromString(transport.sourceBubbleId());
        var ghosts = new ArrayList<Message.TransportGhost>();

        if (transport.ghosts() != null) {
            for (var ghostData : transport.ghosts()) {
                ghosts.add(ghostData.toTransportGhost());
            }
        }

        return new Message.GhostSync(
            sourceBubbleId,
            ghosts,
            transport.bucket() != null ? transport.bucket() : 0L,
            transport.timestamp()
        );
    }

    // ==================== JoinRequest Conversion ====================

    private static TransportVonMessage joinRequestToTransport(Message.JoinRequest msg) {
        return new TransportVonMessage(
            "JoinRequest",
            msg.joinerId().toString(),
            msg.joinerId().toString(),
            msg.position().getX(),
            msg.position().getY(),
            msg.position().getZ(),
            msg.joinerId().toString(),
            msg.timestamp(),
            null,  // ghosts
            null,  // bucket
            null,  // neighbors
            null,  // queryId
            TransportBubbleBounds.from(msg.bounds())  // Luciferase-vzyrf: bounds now on the wire
        );
    }

    private static Message joinRequestFromTransport(TransportVonMessage transport) {
        var joinerId = UUID.fromString(transport.sourceBubbleId());
        var position = new javax.vecmath.Point3d(transport.posX(), transport.posY(), transport.posZ());
        var bounds = transport.bounds() != null ? transport.bounds().toBubbleBounds() : null;

        return new Message.JoinRequest(
            joinerId,
            position,
            bounds,  // Luciferase-vzyrf: round-tripped from wire (was hard-coded null in Phase 6A)
            transport.timestamp()
        );
    }

    // ==================== JoinResponse Conversion ====================

    private static TransportVonMessage joinResponseToTransport(Message.JoinResponse msg) {
        // Convert neighbor set to transport format. Collect into a concrete ArrayList: Stream.toList()
        // returns an ImmutableCollections type that is not on the VoN deserialization allow-list
        // (RDR-004), so it would be rejected on the receiving side.
        var transportNeighbors = msg.neighbors().stream()
            .map(TransportNeighborInfo::from)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        return new TransportVonMessage(
            "JoinResponse",
            msg.acceptorId().toString(),
            msg.acceptorId().toString(),
            0f, 0f, 0f,
            "",
            msg.timestamp(),
            null,  // ghosts
            null,  // bucket
            transportNeighbors,  // neighbors
            null   // queryId not used for JoinResponse
        );
    }

    private static Message joinResponseFromTransport(TransportVonMessage transport) {
        var acceptorId = UUID.fromString(transport.sourceBubbleId());

        // Reconstruct neighbor set from transport format
        var neighbors = transport.neighbors() != null
            ? transport.neighbors().stream()
                .map(TransportNeighborInfo::toNeighborInfo)
                .collect(java.util.stream.Collectors.toSet())
            : java.util.Set.<Message.NeighborInfo>of();

        return new Message.JoinResponse(
            acceptorId,
            neighbors,
            transport.timestamp()
        );
    }

    // ==================== Move Conversion ====================

    private static TransportVonMessage moveToTransport(Message.Move msg) {
        return new TransportVonMessage(
            "Move",
            msg.nodeId().toString(),
            msg.nodeId().toString(),
            msg.newPosition().getX(),
            msg.newPosition().getY(),
            msg.newPosition().getZ(),
            msg.nodeId().toString(),
            msg.timestamp(),
            null,  // ghosts
            null,  // bucket
            null,  // neighbors
            null,  // queryId
            TransportBubbleBounds.from(msg.newBounds())  // Luciferase-vzyrf: bounds now on the wire
        );
    }

    private static Message moveFromTransport(TransportVonMessage transport) {
        var nodeId = UUID.fromString(transport.sourceBubbleId());
        var newPosition = new javax.vecmath.Point3d(transport.posX(), transport.posY(), transport.posZ());
        var newBounds = transport.bounds() != null ? transport.bounds().toBubbleBounds() : null;

        return new Message.Move(
            nodeId,
            newPosition,
            newBounds,  // Luciferase-vzyrf: round-tripped from wire (was hard-coded null in Phase 6A)
            transport.timestamp()
        );
    }

    // ==================== Leave Conversion ====================

    private static TransportVonMessage leaveToTransport(Message.Leave msg) {
        return new TransportVonMessage(
            "Leave",
            msg.nodeId().toString(),
            msg.nodeId().toString(),
            0f, 0f, 0f,
            msg.nodeId().toString(),
            msg.timestamp()
        );
    }

    private static Message leaveFromTransport(TransportVonMessage transport) {
        var nodeId = UUID.fromString(transport.sourceBubbleId());

        return new Message.Leave(
            nodeId,
            transport.timestamp()
        );
    }

    // ==================== Ack Conversion ====================

    private static TransportVonMessage ackToTransport(Message.Ack msg) {
        return new TransportVonMessage(
            "Ack",
            msg.senderId().toString(),
            msg.ackFor().toString(),
            0f, 0f, 0f,
            msg.ackFor().toString(),
            msg.timestamp()
        );
    }

    private static Message ackFromTransport(TransportVonMessage transport) {
        var ackFor = UUID.fromString(transport.targetBubbleId());
        var senderId = UUID.fromString(transport.sourceBubbleId());

        return new Message.Ack(
            ackFor,
            senderId,
            transport.timestamp()
        );
    }

    // ==================== Query Conversion ====================

    private static TransportVonMessage queryToTransport(Message.Query msg) {
        return new TransportVonMessage(
            "Query",
            msg.senderId().toString(),
            msg.targetId().toString(),
            0f, 0f, 0f,
            msg.queryType(),
            msg.timestamp(),
            null,  // ghosts
            null,  // bucket
            null,  // neighbors
            msg.queryId().toString()  // queryId
        );
    }

    private static Message queryFromTransport(TransportVonMessage transport) {
        var queryId = UUID.fromString(transport.queryId());
        var senderId = UUID.fromString(transport.sourceBubbleId());
        var targetId = UUID.fromString(transport.targetBubbleId());
        var queryType = transport.entityId();

        return new Message.Query(
            queryId,
            senderId,
            targetId,
            queryType,
            transport.timestamp()
        );
    }

    // ==================== QueryResponse Conversion ====================

    private static TransportVonMessage queryResponseToTransport(Message.QueryResponse msg) {
        return new TransportVonMessage(
            "QueryResponse",
            msg.responderId().toString(),
            msg.responderId().toString(),  // No specific target
            0f, 0f, 0f,
            msg.responseData(),  // Response data in entityId field
            msg.timestamp(),
            null,  // ghosts
            null,  // bucket
            null,  // neighbors
            msg.queryId().toString()  // queryId
        );
    }

    private static Message queryResponseFromTransport(TransportVonMessage transport) {
        var queryId = UUID.fromString(transport.queryId());
        var responderId = UUID.fromString(transport.sourceBubbleId());
        var responseData = transport.entityId();

        return new Message.QueryResponse(
            queryId,
            responderId,
            responseData,
            transport.timestamp()
        );
    }

    // ==================== Migration 2PC Conversion (Luciferase-l5gr9) ====================
    //
    // The 2PC subtypes carry rich domain types (IdempotencyToken, EntitySnapshot). They are
    // decomposed into a primitive-only TransportMigrationMessage so the VonTransportFilter
    // deserialization allow-list never has to admit a domain type (RDR-004 hygiene). Every
    // subtype's control fields round-trip non-null; EntitySnapshot.content is carried as a
    // String only (see TransportMigrationMessage javadoc).

    private static TransportVonMessage migrationToTransport(MigrationProtocolMessages msg) {
        var tm = switch (msg) {
            case MigrationProtocolMessages.PrepareRequest m -> new TransportMigrationMessage(
                "PrepareRequest", m.transactionId().toString(), m.timestamp(),
                tokEntityId(m.idempotencyToken()), tokSource(m.idempotencyToken()),
                tokDest(m.idempotencyToken()), tokTs(m.idempotencyToken()), tokNonce(m.idempotencyToken()),
                snapEntityId(m.entitySnapshot()), snapX(m.entitySnapshot()), snapY(m.entitySnapshot()),
                snapZ(m.entitySnapshot()), snapContent(m.entitySnapshot()), snapAuthority(m.entitySnapshot()),
                snapEpoch(m.entitySnapshot()), snapVersion(m.entitySnapshot()), snapTs(m.entitySnapshot()),
                uuidStr(m.sourceId()), uuidStr(m.destId()),
                null, null, null, null);
            case MigrationProtocolMessages.PrepareResponse m -> new TransportMigrationMessage(
                "PrepareResponse", m.transactionId().toString(), m.timestamp(),
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null,
                m.success(), m.reason(), uuidStr(m.destProcessId()), null);
            case MigrationProtocolMessages.CommitRequest m -> new TransportMigrationMessage(
                "CommitRequest", m.transactionId().toString(), m.timestamp(),
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null,
                null, null, null, m.confirmed());
            case MigrationProtocolMessages.CommitResponse m -> new TransportMigrationMessage(
                "CommitResponse", m.transactionId().toString(), m.timestamp(),
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null,
                m.success(), m.reason(), null, null);
            case MigrationProtocolMessages.AbortRequest m -> new TransportMigrationMessage(
                "AbortRequest", m.transactionId().toString(), m.timestamp(),
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null,
                null, m.reason(), null, null);
            case MigrationProtocolMessages.AbortResponse m -> new TransportMigrationMessage(
                "AbortResponse", m.transactionId().toString(), m.timestamp(),
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null,
                m.rolledBack(), null, null, null);
        };

        return new TransportVonMessage(
            "Migration",
            tm.transactionId(),
            tm.transactionId(),
            0f, 0f, 0f,
            "",
            tm.timestamp(),
            null, null, null, null, null,
            tm
        );
    }

    private static Message migrationFromTransport(TransportVonMessage transport) {
        var tm = transport.migration();
        if (tm == null) {
            throw new IllegalArgumentException("Migration message missing migration payload");
        }
        var txId = UUID.fromString(tm.transactionId());
        return switch (tm.subtype()) {
            case "PrepareRequest" -> new MigrationProtocolMessages.PrepareRequest(
                txId, reconstructToken(tm), reconstructSnapshot(tm),
                strUuid(tm.sourceId()), strUuid(tm.destId()), tm.timestamp());
            case "PrepareResponse" -> new MigrationProtocolMessages.PrepareResponse(
                txId, Boolean.TRUE.equals(tm.success()), tm.reason(), strUuid(tm.destProcessId()), tm.timestamp());
            case "CommitRequest" -> new MigrationProtocolMessages.CommitRequest(
                txId, Boolean.TRUE.equals(tm.confirmed()), tm.timestamp());
            case "CommitResponse" -> new MigrationProtocolMessages.CommitResponse(
                txId, Boolean.TRUE.equals(tm.success()), tm.reason(), tm.timestamp());
            case "AbortRequest" -> new MigrationProtocolMessages.AbortRequest(
                txId, tm.reason(), tm.timestamp());
            case "AbortResponse" -> new MigrationProtocolMessages.AbortResponse(
                txId, Boolean.TRUE.equals(tm.success()), tm.timestamp());
            default -> throw new IllegalArgumentException("Unknown migration subtype: " + tm.subtype());
        };
    }

    // ---- decomposition helpers (null-safe over optional groups) ----

    private static String uuidStr(UUID u) { return u == null ? null : u.toString(); }
    private static UUID strUuid(String s) { return s == null ? null : UUID.fromString(s); }

    private static String tokEntityId(IdempotencyToken t) { return t == null ? null : t.entityId(); }
    private static String tokSource(IdempotencyToken t) { return t == null ? null : uuidStr(t.sourceProcessId()); }
    private static String tokDest(IdempotencyToken t) { return t == null ? null : uuidStr(t.destProcessId()); }
    private static Long tokTs(IdempotencyToken t) { return t == null ? null : t.timestamp(); }
    private static String tokNonce(IdempotencyToken t) { return t == null ? null : uuidStr(t.nonce()); }

    private static IdempotencyToken reconstructToken(TransportMigrationMessage tm) {
        if (tm.tokEntityId() == null && tm.tokSourceProcessId() == null && tm.tokNonce() == null) {
            return null;
        }
        return new IdempotencyToken(
            tm.tokEntityId(),
            strUuid(tm.tokSourceProcessId()),
            strUuid(tm.tokDestProcessId()),
            tm.tokTimestamp() == null ? 0L : tm.tokTimestamp(),
            strUuid(tm.tokNonce()));
    }

    private static String snapEntityId(EntitySnapshot s) { return s == null ? null : s.entityId(); }
    private static Double snapX(EntitySnapshot s) { return (s == null || s.position() == null) ? null : s.position().getX(); }
    private static Double snapY(EntitySnapshot s) { return (s == null || s.position() == null) ? null : s.position().getY(); }
    private static Double snapZ(EntitySnapshot s) { return (s == null || s.position() == null) ? null : s.position().getZ(); }
    private static String snapContent(EntitySnapshot s) {
        return (s == null || s.content() == null) ? null : s.content().toString();
    }
    private static String snapAuthority(EntitySnapshot s) { return s == null ? null : uuidStr(s.authorityBubbleId()); }
    private static Long snapEpoch(EntitySnapshot s) { return s == null ? null : s.epoch(); }
    private static Long snapVersion(EntitySnapshot s) { return s == null ? null : s.version(); }
    private static Long snapTs(EntitySnapshot s) { return s == null ? null : s.timestamp(); }

    // NOTE: EntitySnapshot.content is String-only on the wire. TransportMigrationMessage carries
    // snapContent() as a String, so any richer in-memory content type is collapsed to its String
    // form when serialized and reconstructed here. Callers must not assume non-String content
    // survives a transport round-trip (RDR-004 wire-hygiene constraint).
    private static EntitySnapshot reconstructSnapshot(TransportMigrationMessage tm) {
        if (tm.snapEntityId() == null && tm.snapAuthorityBubbleId() == null) {
            return null;
        }
        Point3d pos = (tm.snapPosX() == null) ? null
            : new Point3d(tm.snapPosX(), tm.snapPosY(), tm.snapPosZ());
        return new EntitySnapshot(
            tm.snapEntityId(),
            pos,
            tm.snapContent(),  // content carried as String only (RDR-004 hygiene)
            strUuid(tm.snapAuthorityBubbleId()),
            tm.snapEpoch() == null ? 0L : tm.snapEpoch(),
            tm.snapVersion() == null ? 0L : tm.snapVersion(),
            tm.snapTimestamp() == null ? 0L : tm.snapTimestamp());
    }
}
