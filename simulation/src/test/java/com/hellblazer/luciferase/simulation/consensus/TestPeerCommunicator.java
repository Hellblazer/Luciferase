/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus;

import java.util.List;
import java.util.UUID;

/**
 * Test implementation of PeerCommunicator for in-process multi-node testing.
 * <p>
 * Enables ConsensusCoordinator instances to communicate directly without gRPC,
 * making integration tests simpler and faster.
 */
class TestPeerCommunicator implements ConsensusElectionProtocol.PeerCommunicator {

    private final UUID sourceNodeId;
    private final List<ConsensusCoordinator> allCoordinators;

    /**
     * Creates a test peer communicator.
     *
     * @param sourceNodeId     the ID of the source node (to exclude from broadcasts)
     * @param allCoordinators  all coordinators in the test cluster
     */
    public TestPeerCommunicator(UUID sourceNodeId, List<ConsensusCoordinator> allCoordinators) {
        this.sourceNodeId = sourceNodeId;
        this.allCoordinators = allCoordinators;
    }

    @Override
    public void broadcastVoteRequest(UUID candidateId, long term) {
        // Send vote request to all other nodes (skip stopped nodes)
        for (var coordinator : allCoordinators) {
            if (!coordinator.getNodeId().equals(sourceNodeId) && coordinator.isRunning()) {
                // Call requestVote on the peer's protocol
                coordinator.getProtocol().requestVote(candidateId, term)
                          .thenAccept(granted -> {
                              if (granted) {
                                  // Record vote on the candidate's ballot box
                                  var candidateCoordinator = findCoordinator(candidateId);
                                  if (candidateCoordinator != null && candidateCoordinator.isRunning()) {
                                      candidateCoordinator.recordElectionVote(coordinator.getNodeId(), true);
                                  }
                              }
                          });
            }
        }
    }

    @Override
    public void broadcastHeartbeat(UUID leaderId, long term) {
        // Send heartbeat to all other nodes (skip stopped nodes)
        for (var coordinator : allCoordinators) {
            if (!coordinator.getNodeId().equals(sourceNodeId) && coordinator.isRunning()) {
                coordinator.getProtocol().sendHeartbeat(leaderId, term);
            }
        }
    }

    private ConsensusCoordinator findCoordinator(UUID nodeId) {
        for (var coordinator : allCoordinators) {
            if (coordinator.getNodeId().equals(nodeId)) {
                return coordinator;
            }
        }
        return null;
    }
}
