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
            (float) msg.position().getX(),
            (float) msg.position().getY(),
            (float) msg.position().getZ(),
            msg.joinerId().toString(),
            msg.timestamp()
        );
    }

    private static Message joinRequestFromTransport(TransportVonMessage transport) {
        var joinerId = UUID.fromString(transport.sourceBubbleId());
        var position = new javafx.geometry.Point3D(transport.posX(), transport.posY(), transport.posZ());

        return new Message.JoinRequest(
            joinerId,
            position,
            null,  // BubbleBounds not transmitted in wire format (Phase 6B)
            transport.timestamp()
        );
    }

    // ==================== JoinResponse Conversion ====================

    private static TransportVonMessage joinResponseToTransport(Message.JoinResponse msg) {
        // Convert neighbor set to transport format
        var transportNeighbors = msg.neighbors().stream()
            .map(TransportNeighborInfo::from)
            .toList();

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
            (float) msg.newPosition().getX(),
            (float) msg.newPosition().getY(),
            (float) msg.newPosition().getZ(),
            msg.nodeId().toString(),
            msg.timestamp()
        );
    }

    private static Message moveFromTransport(TransportVonMessage transport) {
        var nodeId = UUID.fromString(transport.sourceBubbleId());
        var newPosition = new javafx.geometry.Point3D(transport.posX(), transport.posY(), transport.posZ());

        return new Message.Move(
            nodeId,
            newPosition,
            null,  // BubbleBounds not transmitted in wire format (Phase 6B)
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
}
