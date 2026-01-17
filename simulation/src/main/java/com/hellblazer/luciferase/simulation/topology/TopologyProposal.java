/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;

import java.util.UUID;

/**
 * Sealed interface for topology change proposals in dynamic bubble adaptation.
 * <p>
 * Permits three topology operations:
 * <ul>
 *   <li>{@link SplitProposal} - Split overcrowded bubble (>5000 entities)</li>
 *   <li>{@link MergeProposal} - Merge underpopulated bubbles (<500 entities)</li>
 *   <li>{@link MoveProposal} - Relocate bubble to follow entity cluster</li>
 * </ul>
 * <p>
 * All proposals include:
 * <ul>
 *   <li>proposalId - Unique identifier</li>
 *   <li>viewId - View context (prevents cross-view double-commit)</li>
 *   <li>timestamp - Proposal creation time (from Clock, not wall-clock)</li>
 * </ul>
 * <p>
 * Proposals undergo validation before voting to reject Byzantine inputs.
 * <p>
 * The implementing record classes ({@link SplitProposal}, {@link MergeProposal},
 * {@link MoveProposal}) are defined in separate files for cross-package accessibility.
 * <p>
 * Phase 9B: Consensus Topology Coordination
 *
 * @author hal.hildebrand
 */
public sealed interface TopologyProposal
    permits SplitProposal, MergeProposal, MoveProposal {

    /**
     * Gets the unique proposal identifier.
     *
     * @return proposal UUID
     */
    UUID proposalId();

    /**
     * Gets the view context identifier.
     * <p>
     * Prevents double-commit races across view boundaries.
     *
     * @return view ID digest
     */
    Digest viewId();

    /**
     * Gets the proposal timestamp.
     * <p>
     * IMPORTANT: This is simulation time from injected Clock,
     * not wall-clock time.
     *
     * @return timestamp in milliseconds (simulation time)
     */
    long timestamp();

    /**
     * Validates this proposal against current bubble grid state.
     * <p>
     * Checks type-specific constraints (density thresholds, neighbor
     * adjacency, bounds overlap) to reject Byzantine proposals before voting.
     *
     * @param grid current bubble grid state
     * @return validation result
     */
    ValidationResult validate(TetreeBubbleGrid grid);
}
