/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

/**
 * The face-neighbor of a hybrid pyramid/tetrahedron element (RDR-010 q3p). Parallel to
 * {@code Tet.FaceNeighbor(byte, Tet)} but able to carry a cross-shape neighbor: a tetrahedron's face
 * neighbor may be a pyramid and vice versa (Knapp 2026 §4.4, t8code {@code t8_dpyramid_face_neighbour}).
 *
 * <p>{@code face} is the index of the <b>neighbor's</b> face that abuts the queried element (the
 * reciprocal face), matching the convention of the pure-Tetree {@code Tet.FaceNeighbor} record.
 *
 * @param face    the neighbor's reciprocal face index
 * @param element the neighboring element (tetrahedron types 0&ndash;5, or pyramid types 6&ndash;7)
 * @author hal.hildebrand
 */
public record HybridFaceNeighbor(byte face, HybridElement element) {
}
