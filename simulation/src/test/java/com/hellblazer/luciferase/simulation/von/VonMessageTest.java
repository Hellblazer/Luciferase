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

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for VonMessage sealed types.
 *
 * @author hal.hildebrand
 */
public class VonMessageTest {

    @Test
    void testJoinRequest_createsWithTimestamp() {
        var joinerId = UUID.randomUUID();
        var position = new Point3D(1.0, 2.0, 3.0);
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f)));

        var request = new VonMessage.JoinRequest(joinerId, position, bounds);

        assertThat(request.joinerId()).isEqualTo(joinerId);
        assertThat(request.position()).isEqualTo(position);
        assertThat(request.bounds()).isNotNull();
        assertThat(request.timestamp()).isGreaterThan(0);
    }

    @Test
    void testJoinResponse_withNeighbors() {
        var acceptorId = UUID.randomUUID();
        var neighborId = UUID.randomUUID();
        var position = new Point3D(1.0, 2.0, 3.0);
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f)));

        var neighborInfo = new VonMessage.NeighborInfo(neighborId, position, bounds);
        var response = new VonMessage.JoinResponse(acceptorId, Set.of(neighborInfo));

        assertThat(response.acceptorId()).isEqualTo(acceptorId);
        assertThat(response.neighbors()).hasSize(1);
        assertThat(response.neighbors()).contains(neighborInfo);
        assertThat(response.timestamp()).isGreaterThan(0);
    }

    @Test
    void testMove_withNewPositionAndBounds() {
        var nodeId = UUID.randomUUID();
        var newPosition = new Point3D(4.0, 5.0, 6.0);
        var newBounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(4.0f, 5.0f, 6.0f)));

        var move = new VonMessage.Move(nodeId, newPosition, newBounds);

        assertThat(move.nodeId()).isEqualTo(nodeId);
        assertThat(move.newPosition()).isEqualTo(newPosition);
        assertThat(move.newBounds()).isNotNull();
        assertThat(move.timestamp()).isGreaterThan(0);
    }

    @Test
    void testLeave_withNodeId() {
        var nodeId = UUID.randomUUID();

        var leave = new VonMessage.Leave(nodeId);

        assertThat(leave.nodeId()).isEqualTo(nodeId);
        assertThat(leave.timestamp()).isGreaterThan(0);
    }

    @Test
    void testGhostSync_withEmptyGhosts() {
        var sourceBubbleId = UUID.randomUUID();
        long bucket = 100L;

        var sync = new VonMessage.GhostSync(sourceBubbleId, List.of(), bucket);

        assertThat(sync.sourceBubbleId()).isEqualTo(sourceBubbleId);
        assertThat(sync.ghosts()).isEmpty();
        assertThat(sync.bucket()).isEqualTo(bucket);
        assertThat(sync.timestamp()).isGreaterThan(0);
    }

    @Test
    void testAck_withMessageId() {
        var ackFor = UUID.randomUUID();
        var senderId = UUID.randomUUID();

        var ack = new VonMessage.Ack(ackFor, senderId);

        assertThat(ack.ackFor()).isEqualTo(ackFor);
        assertThat(ack.senderId()).isEqualTo(senderId);
        assertThat(ack.timestamp()).isGreaterThan(0);
    }

    @Test
    void testNeighborInfo_record() {
        var nodeId = UUID.randomUUID();
        var position = new Point3D(1.0, 2.0, 3.0);
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f)));

        var info = new VonMessage.NeighborInfo(nodeId, position, bounds);

        assertThat(info.nodeId()).isEqualTo(nodeId);
        assertThat(info.position()).isEqualTo(position);
        assertThat(info.bounds()).isNotNull();
    }

    @Test
    void testSealedInterface_patternMatching() {
        VonMessage message = new VonMessage.Leave(UUID.randomUUID());

        // Verify VonMessage allows pattern matching (non-sealed for Phase 6B extensions)
        var result = switch (message) {
            case VonMessage.JoinRequest r -> "join";
            case VonMessage.JoinResponse r -> "response";
            case VonMessage.Move m -> "move";
            case VonMessage.Leave l -> "leave";
            case VonMessage.GhostSync g -> "ghost";
            case VonMessage.Ack a -> "ack";
            default -> "other"; // Phase 6B extensions (RegisterProcessMessage, etc.)
        };

        assertThat(result).isEqualTo("leave");
    }
}
