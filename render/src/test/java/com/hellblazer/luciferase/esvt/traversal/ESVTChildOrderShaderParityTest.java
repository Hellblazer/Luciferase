/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.esvt.traversal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Luciferase-d5o9: the GPU traversal table {@code CHILD_ORDER[6][4][4]} hardcoded in
 * {@code shaders/raycast_esvt.comp} must match the CPU {@link ESVTChildOrder} table (which is computed from
 * the corrected PER-TYPE {@code TetreeConnectivity.CHILDREN_AT_FACE[type][face]}, t8code
 * {@code t8_dtet_face_child_id_by_type}). If they diverge, the GPU ray traversal descends children in a
 * different order than the CPU path. This test pins the shader literal against the live CPU computation so a
 * future correction to the per-type table (as Luciferase-koaw did) cannot silently leave the shader stale.
 *
 * <p>If this test fails, regenerate the {@code CHILD_ORDER} literal in {@code raycast_esvt.comp} from the
 * values {@link ESVTChildOrder#getChildOrder(int, int)} produces.
 *
 * @author hal.hildebrand
 */
class ESVTChildOrderShaderParityTest {

    /** The CHILD_ORDER[6][4][4] literal currently in shaders/raycast_esvt.comp (kept in sync by this test). */
    private static final int[][][] SHADER_CHILD_ORDER = {
        { { 7, 5, 1, 4 }, { 6, 7, 4, 0 }, { 1, 7, 0, 2 }, { 4, 1, 0, 3 } }, // Type 0
        { { 7, 5, 1, 4 }, { 6, 0, 7, 5 }, { 7, 3, 0, 1 }, { 5, 2, 1, 0 } }, // Type 1
        { { 7, 3, 5, 4 }, { 6, 7, 4, 0 }, { 1, 7, 0, 3 }, { 4, 0, 2, 3 } }, // Type 2
        { { 7, 5, 1, 6 }, { 6, 4, 0, 7 }, { 7, 3, 0, 1 }, { 6, 2, 1, 0 } }, // Type 3
        { { 7, 6, 3, 5 }, { 7, 4, 5, 0 }, { 1, 7, 0, 3 }, { 5, 0, 2, 3 } }, // Type 4
        { { 7, 5, 6, 3 }, { 6, 4, 0, 7 }, { 7, 3, 0, 2 }, { 6, 1, 3, 0 } }  // Type 5
    };

    @Test
    void shaderChildOrderMatchesCpuChildOrder() {
        for (int type = 0; type < 6; type++) {
            for (int face = 0; face < 4; face++) {
                byte[] cpu = ESVTChildOrder.getChildOrder(type, face);
                int[] cpuInts = new int[cpu.length];
                for (int i = 0; i < cpu.length; i++) {
                    cpuInts[i] = cpu[i];
                }
                assertArrayEquals(SHADER_CHILD_ORDER[type][face], cpuInts,
                                  "GPU shader CHILD_ORDER[" + type + "][" + face + "] ("
                                  + Arrays.toString(SHADER_CHILD_ORDER[type][face])
                                  + ") must match CPU ESVTChildOrder (" + Arrays.toString(cpuInts) + ")");
            }
        }
    }
}
