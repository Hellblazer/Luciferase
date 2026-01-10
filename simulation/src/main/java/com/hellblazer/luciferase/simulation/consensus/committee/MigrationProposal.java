/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.hellblazer.delos.cryptography.Digest;

import java.util.UUID;

/**
 * Immutable migration proposal for committee-based consensus voting.
 * <p>
 * Represents a proposed entity migration from source to target node.
 * The viewId field prevents double-commit race conditions across view boundaries.
 * <p>
 * Phase 7G Day 1: Committee Selector & Data Structures
 *
 * @param proposalId   Unique proposal identifier
 * @param entityId     Entity being migrated
 * @param sourceNodeId Proposer's node ID
 * @param targetNodeId Target node for migration
 * @param viewId       View context (prevents cross-view double-commit)
 * @param timestamp    Proposal creation time (optional, for ordering/debugging)
 * @author hal.hildebrand
 */
public record MigrationProposal(
    UUID proposalId,
    UUID entityId,
    Digest sourceNodeId,
    Digest targetNodeId,
    Digest viewId,
    long timestamp
) {
}
