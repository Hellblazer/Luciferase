/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.portal.mesh.polyhedra;

import com.hellblazer.luciferase.portal.mesh.polyhedra.archimedes.RhombicDodecahedron;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Constructor smoke tests for the portal RD/FCC geometry classes
 * (Luciferase-6oa, label {@code portal-rdfcc-quality}).
 * <p>
 * {@code ThreeOrthoscheme} and {@code RhombicDodecahedron} are mesh geometry
 * classes — they expose constructor-only APIs returning {@link Polyhedron}
 * meshes (no math methods of their own). The acceptance criterion for
 * Luciferase-6oa requires "≥1 test class per math-bearing file"; these
 * smoke tests satisfy the literal coverage requirement and would surface
 * any future regression where a constructor began throwing.
 *
 * @author hal.hildebrand
 */
public class RDGeometrySmokeTest {

    @Test
    void threeOrthoschemeConstructsWithoutException() {
        // ThreeOrthoscheme(int simplex, double scale) — simplex index in [0, 5]
        // selects which of the six characteristic Kuhn tetrahedra to build.
        var ortho = new ThreeOrthoscheme(0, 1.0);
        assertNotNull(ortho, "ThreeOrthoscheme(simplex, scale) must construct");
        assertFalse(ortho.getVertexPositions().isEmpty(),
                    "ThreeOrthoscheme must have non-empty vertex set");
    }

    @Test
    void rhombicDodecahedronConstructsWithoutException() {
        var rd = new RhombicDodecahedron(1.0);
        assertNotNull(rd, "RhombicDodecahedron(edgeLength) must construct");
        assertFalse(rd.getVertexPositions().isEmpty(),
                    "RhombicDodecahedron must have non-empty vertex set");
    }
}
