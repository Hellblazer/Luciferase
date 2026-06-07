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
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;

import java.util.UUID;

/**
 * Proposal to collapse a COMPLETE set of 8 sibling Bey child leaves back into their parent leaf —
 * the coverage-preserving inverse-Bey merge under RDR-018 Option B (AC-3 prerequisite, AC-4
 * sibling-collapse semantics).
 * <p>
 * Unlike {@link MergeProposal} — an arbitrary two-bubble merge that is hard-fenced because removing
 * one of two unrelated bubbles untiles its tetrahedral region (RDR-018 F4 coverage hole) — a
 * sibling collapse removes a full set of 8 level-{@code (L+1)} children whose union is exactly their
 * level-{@code L} parent tetrahedron, then re-registers that parent. Coverage is preserved by
 * construction: the parent tiles precisely the region the 8 children tiled.
 * <p>
 * The proposal carries a single {@code anchorChild} — any one of the 8 siblings. The parent and the
 * full sibling set are derived from its registration key: {@code parent = Tet.tetrahedron(key).parent()},
 * {@code siblings = parent.geometricSubdivide()}. The collapse is rejected fail-loud unless ALL 8
 * sibling keys are currently registered (an incomplete set cannot collapse without punching a hole).
 *
 * @param proposalId  unique proposal identifier
 * @param anchorChild any one of the 8 sibling child leaves to collapse (parent + full set derived from its key)
 * @param viewId      view context (prevents cross-view double-commit)
 * @param timestamp   proposal creation time (simulation time, from injected Clock)
 *
 * @author hal.hildebrand
 */
public record CollapseProposal(
    UUID proposalId,
    UUID anchorChild,
    Digest viewId,
    long timestamp
) implements TopologyProposal {

    @Override
    public ValidationResult validate(TetreeBubbleGrid grid) {
        var anchor = grid.getBubbleById(anchorChild);
        if (anchor == null) {
            return new ValidationResult(false, "Anchor child bubble not found: " + anchorChild);
        }
        var childKey = grid.getKeyForBubble(anchorChild);
        if (childKey == null) {
            return new ValidationResult(false, "No grid key for anchor child: " + anchorChild);
        }
        var childTet = Tet.tetrahedron(childKey);
        if (childTet.l() < 1) {
            return new ValidationResult(false,
                                        "Anchor child is at level " + childTet.l() + " (root has no parent to collapse into)");
        }

        var parentTet = childTet.parent();
        var parentKey = parentTet.tmIndex();
        if (grid.containsBubble(parentKey)) {
            return new ValidationResult(false,
                                        "Parent key already registered (" + parentKey
                                        + ") — not a collapsible refinement state");
        }

        // The full sibling set must ALL be present; an incomplete set cannot collapse without
        // leaving the missing siblings' regions untiled (coverage hole).
        var siblings = parentTet.geometricSubdivide();
        for (var sibling : siblings) {
            if (!grid.containsBubble(sibling.tmIndex())) {
                return new ValidationResult(false,
                                            "Incomplete sibling set: child key " + sibling.tmIndex()
                                            + " is not registered — cannot collapse without untiling its region");
            }
        }

        return ValidationResult.success();
    }
}
