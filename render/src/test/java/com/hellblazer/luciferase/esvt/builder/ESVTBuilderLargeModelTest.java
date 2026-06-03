/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.esvt.builder;

import com.hellblazer.luciferase.geometry.Point3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-yhue6: ESVTBuilder laid nodes out breadth-first, so a parent's child block sat tens-of-thousands of
 * slots ahead → relativeOffset &gt; 32767 for most internal nodes → a far pointer was allocated for nearly every
 * node. Because the far-pointer INDEX is stored back in the same 15-bit child-ptr field, once &gt;32767 far
 * pointers existed the build threw "Child pointer must fit in 15 bits (max 32767), got: 32768". Any model large
 * enough to need &gt;32767 far pointers (the Stanford Bunny, 47,705 voxels) could not build.
 *
 * <p>The child-contiguous layout keeps child blocks adjacent to their parents, so offsets stay subtree-local and
 * far pointers become rare. This test builds a large (bunny-scale) voxel model and requires the build to succeed
 * with a far-pointer count far below the 32767 cap.
 *
 * @author hal.hildebrand
 */
class ESVTBuilderLargeModelTest {

    /** A dense ~50k-voxel block in a 40^3 region — comfortably larger than the 32767 far-pointer cap under BFS. */
    private static List<Point3i> denseBlock() {
        var voxels = new ArrayList<Point3i>();
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 40; y++) {
                for (int z = 0; z < 40; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        return voxels;  // 64,000 voxels
    }

    @Test
    void buildsLargeModelWithoutChildPointerOverflow() {
        var voxels = denseBlock();
        assertTrue(voxels.size() > 47_705, "precondition: model is at least bunny-scale");

        var builder = new ESVTBuilder();
        var data = assertDoesNotThrow(() -> builder.buildFromVoxels(voxels, 8, 64),
                                      "large models must build without overflowing the 15-bit child pointer "
                                      + "(Luciferase-yhue6)");

        assertTrue(data.nodeCount() > 0, "expected a non-empty tree");
        assertTrue(data.farPointerCount() < 32_767,
                   "far pointers must stay well under the 15-bit cap with the child-contiguous layout, got "
                   + data.farPointerCount());
    }
}
