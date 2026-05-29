/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.tetree.Tet;

/**
 * Contract surface for minTetLevel re-injection in the hybrid pyramid/tetrahedron tree (RDR-010 pi1.3).
 *
 * <p>When the PyramidIndex stores or retrieves a {@link Tet} that lives beneath a pyramid ancestor,
 * the {@code minTetLevel} field on the {@link Tet} value-object must reflect the level of the
 * lowest pyramidal ancestor in its lineage (Knapp 2026, Algorithm 4.1 / §4.3). This utility
 * provides the single canonical call-site for that re-injection.
 *
 * <p><b>Phase A contract:</b> no callers wire this yet. It is established here so that Phases C–E
 * have a stable, reviewable contract surface rather than ad-hoc re-injection at each call site.
 * The method signature is intentionally minimal — expand only as Phase C–E implementations require.
 *
 * <p><b>Usage pattern (Phase C+ callers):</b>
 * <pre>{@code
 *     // After retrieving a Tet from the spatial index, re-inject its pyramid context:
 *     Tet reinjected = PyramidHybridContext.reinject(ancestorKey, retrieved);
 *     // Use reinjected.parent() / reinjected.child(i) / reinjected.computeType() safely.
 * }</pre>
 *
 * @author hal.hildebrand
 */
public final class PyramidHybridContext {

    private PyramidHybridContext() {
        // utility class — no instances
    }

    /**
     * Return a copy of {@code retrieved} with {@code minTetLevel} set to {@code ancestor.getLevel()}.
     *
     * <p>This call is a no-op identity when {@code ancestor.getLevel() == retrieved.minTetLevel}
     * (the value-object is already consistent); callers need not guard against the no-op case.
     *
     * @param ancestor  the {@link PyramidKey} of the shallowest pyramidal ancestor in the lineage
     *                  ({@code ancestor.getLevel() <= retrieved.l()} — passing a deeper ancestor is a
     *                  programming error and would silently produce wrong hybrid topology on
     *                  {@code parent()}/{@code child()}/{@code computeType()} navigation)
     * @param retrieved the {@link Tet} fetched from the spatial index or navigation traversal
     * @return a {@link Tet} value-object identical to {@code retrieved} except that
     *         {@code minTetLevel == ancestor.getLevel()}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code ancestor.getLevel() > retrieved.l()} (deeper-than-tet
     *                                  ancestor — see invariant in parameter doc)
     */
    public static Tet reinject(PyramidKey ancestor, Tet retrieved) {
        java.util.Objects.requireNonNull(ancestor, "ancestor");
        java.util.Objects.requireNonNull(retrieved, "retrieved");
        byte ancestorLevel = ancestor.getLevel();
        if (ancestorLevel > retrieved.l()) {
            throw new IllegalArgumentException(
            "Pyramidal ancestor must be at or above the retrieved tet's level: ancestor.level="
            + ancestorLevel + ", tet.level=" + retrieved.l());
        }
        return retrieved.withMinTetLevel(ancestorLevel);
    }
}
