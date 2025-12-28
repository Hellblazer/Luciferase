/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.esvt.renderer;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to trace ESVT rendering issues with large trees and far pointers.
 */
class ESVTRenderingDebugTest {

    @Test
    void testLargeTreeRendering() {
        // Build a large tree that will have far pointers
        int resolution = 64; // 64^3 = 262144 voxels
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        System.out.printf("Generated %d voxels%n", voxels.size());

        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 6);

        var data = bridge.getData();
        assertNotNull(data);

        System.out.printf("Built: %d nodes, %d leaves, %d far pointers%n",
            data.nodeCount(), data.leafCount(), data.farPointerCount());

        // Check root node
        var root = data.nodes()[0];
        System.out.printf("Root: childMask=0x%02X, leafMask=0x%02X, childPtr=%d, isFar=%b, isValid=%b%n",
            root.getChildMask(), root.getLeafMask(), root.getChildPtr(), root.isFar(), root.isValid());

        // Create mesh renderer and render leaves
        var meshRenderer = new ESVTNodeMeshRenderer(data);
        var group = meshRenderer.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.DEPTH_GRADIENT, 0.7);

        System.out.printf("Rendered group has %d children%n", group.getChildren().size());

        // The group should have many children (one per leaf)
        assertTrue(group.getChildren().size() > 0,
            "Rendered group should have children, got " + group.getChildren().size());
    }

    @Test
    void testSmallTreeRendering() {
        // Small tree without far pointers
        int resolution = 8; // 8^3 = 512 voxels
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }

        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 3);

        var data = bridge.getData();
        assertNotNull(data);

        System.out.printf("Built: %d nodes, %d leaves, %d far pointers%n",
            data.nodeCount(), data.leafCount(), data.farPointerCount());

        var root = data.nodes()[0];
        System.out.printf("Root: childMask=0x%02X, leafMask=0x%02X, childPtr=%d, isFar=%b, isValid=%b%n",
            root.getChildMask(), root.getLeafMask(), root.getChildPtr(), root.isFar(), root.isValid());

        var meshRenderer = new ESVTNodeMeshRenderer(data);
        var group = meshRenderer.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.DEPTH_GRADIENT, 0.7);

        System.out.printf("Rendered group has %d children%n", group.getChildren().size());

        assertTrue(group.getChildren().size() > 0,
            "Rendered group should have children, got " + group.getChildren().size());
    }
}
