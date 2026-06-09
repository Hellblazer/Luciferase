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

/**
 * Discriminates the node-identity model a {@link MigrationProposal} carries (RDR-020 S3).
 * <p>
 * The two kinds use structurally different source/target node identities, which
 * {@code ViewCommitteeConsensus.validateProposal} must branch on:
 * <ul>
 *   <li>{@link #ENTITY_MIGRATION} — two distinct nodes: {@code source = local holder
 *       (possession)}, {@code target = HRW owner of the destination region}. The
 *       self-migration reject ({@code source == target}) is enforced: a cross-node
 *       migration to oneself is meaningless.</li>
 *   <li>{@link #TOPOLOGY} — a single-region structural change (split/merge/move/collapse)
 *       owned by exactly one node, so {@code source == target == owner(region)} by
 *       construction. The self-migration reject is therefore <em>skipped</em>; all other
 *       validity gates (null-viewId, in-view membership) apply unchanged.</li>
 * </ul>
 * <p>
 * <b>Wire/upgrade contract.</b> {@code kind} is serialized on
 * {@code CommitteeMigrationProposal} because every committee member re-validates a
 * received proposal. The proto field defaults to {@link #ENTITY_MIGRATION} (ordinal 0),
 * so a member running pre-amendment code reads any TOPOLOGY proposal as ENTITY_MIGRATION
 * and self-migration-rejects it. TOPOLOGY consensus therefore requires all committee
 * members on amended code — a documented multi-node prerequisite (RDR-020).
 */
public enum ProposalKind {
    /**
     * Two-distinct-node entity migration (source = possession, target = HRW owner).
     * Default kind for back-compatibility; ordinal 0 matches the proto default.
     */
    ENTITY_MIGRATION,

    /**
     * Single-region topology change where {@code source == target == owner(region)}.
     * The self-migration reject is skipped for this kind.
     */
    TOPOLOGY
}
