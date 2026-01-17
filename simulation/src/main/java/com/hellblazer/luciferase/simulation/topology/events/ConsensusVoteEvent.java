/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.events;

import java.util.UUID;

/**
 * Event fired during consensus voting for topology changes.
 *
 * @param eventId    unique event identifier
 * @param timestamp  event timestamp (ms since epoch)
 * @param proposalId topology proposal being voted on
 * @param voterId    ID of the voter (bubble or process)
 * @param vote       vote cast (approve, reject, abstain)
 * @param quorum     current quorum count
 * @param needed     votes needed for approval
 *
 * @author hal.hildebrand
 */
public record ConsensusVoteEvent(
    UUID eventId,
    long timestamp,
    UUID proposalId,
    UUID voterId,
    String vote,
    int quorum,
    int needed
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "consensus_vote";
    }
}
