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
 * Immutable committee vote for a migration proposal.
 * <p>
 * Represents a YES or NO vote from a committee member for a specific proposal.
 * The viewId must match the proposal's viewId to prevent cross-view race conditions.
 * <p>
 * Phase 7G Day 1: Committee Selector & Data Structures
 *
 * @param proposalId Which proposal is this vote for?
 * @param voterId    Which committee member is voting?
 * @param approved   YES (true) or NO (false)
 * @param viewId     View context (must match proposal viewId)
 * @author hal.hildebrand
 */
public record Vote(
    UUID proposalId,
    Digest voterId,
    boolean approved,
    Digest viewId
) {
}
