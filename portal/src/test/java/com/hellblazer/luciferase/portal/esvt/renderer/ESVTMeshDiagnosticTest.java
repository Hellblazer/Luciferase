/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.esvt.renderer;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test to validate ESVT mesh rendering geometry.
 */
class ESVTMeshDiagnosticTest {

    @BeforeAll
    static void initJavaFX() throws InterruptedException {
        // Initialize JavaFX toolkit for headless testing
        CountDownLatch latch = new CountDownLatch(1);
        try {
            // This initializes JavaFX
            new JFXPanel();
            Platform.runLater(latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX initialization timed out");
        } catch (Exception e) {
            System.out.println("JavaFX init warning: " + e.getMessage());
        }
    }

    @Test
    void testMeshGeometryBounds() throws Exception {
        // Build a small tree for faster testing
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

        System.out.printf("=== ESVT Data ===\n");
        System.out.printf("Nodes: %d, Leaves: %d, Depth: %d\n",
            data.nodeCount(), data.leafCount(), data.maxDepth());
        System.out.printf("Root: childMask=0x%02X, leafMask=0x%02X, childPtr=%d\n",
            data.nodes()[0].getChildMask(), data.nodes()[0].getLeafMask(),
            data.nodes()[0].getChildPtr());

        var meshRenderer = new ESVTNodeMeshRenderer(data);
        var group = meshRenderer.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 0.7);

        System.out.printf("\n=== Rendered Group ===\n");
        System.out.printf("Children count: %d\n", group.getChildren().size());

        // Collect all MeshViews and analyze their bounds
        var meshViews = collectMeshViews(group);
        System.out.printf("MeshView count: %d\n", meshViews.size());

        assertTrue(meshViews.size() > 0, "Should have at least one MeshView");

        // Analyze bounds of all meshes
        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = Double.MIN_VALUE;

        int validMeshes = 0;
        for (int i = 0; i < Math.min(10, meshViews.size()); i++) {
            var mv = meshViews.get(i);

            System.out.printf("\nMeshView[%d]:\n", i);
            System.out.printf("  Translate: (%.2f, %.2f, %.2f)\n",
                mv.getTranslateX(), mv.getTranslateY(), mv.getTranslateZ());
            System.out.printf("  Scale: (%.2f, %.2f, %.2f)\n",
                mv.getScaleX(), mv.getScaleY(), mv.getScaleZ());

            if (mv.getMesh() instanceof TriangleMesh mesh) {
                var points = mesh.getPoints();
                System.out.printf("  Points: %d vertices\n", points.size() / 3);

                if (points.size() >= 3) {
                    System.out.printf("  First vertex: (%.2f, %.2f, %.2f)\n",
                        points.get(0), points.get(1), points.get(2));
                }

                // Check if mesh has actual geometry
                if (points.size() > 0) {
                    validMeshes++;
                }
            }

            // Track bounds based on translation and scale
            double tx = mv.getTranslateX();
            double ty = mv.getTranslateY();
            double tz = mv.getTranslateZ();
            double sx = mv.getScaleX();
            double sy = mv.getScaleY();
            double sz = mv.getScaleZ();

            minX = Math.min(minX, tx);
            maxX = Math.max(maxX, tx + sx);
            minY = Math.min(minY, ty);
            maxY = Math.max(maxY, ty + sy);
            minZ = Math.min(minZ, tz);
            maxZ = Math.max(maxZ, tz + sz);
        }

        System.out.printf("\n=== Bounds Analysis ===\n");
        System.out.printf("X range: [%.2f, %.2f] (span: %.2f)\n", minX, maxX, maxX - minX);
        System.out.printf("Y range: [%.2f, %.2f] (span: %.2f)\n", minY, maxY, maxY - minY);
        System.out.printf("Z range: [%.2f, %.2f] (span: %.2f)\n", minZ, maxZ, maxZ - minZ);
        System.out.printf("Valid meshes with geometry: %d\n", validMeshes);

        assertTrue(validMeshes > 0, "Should have meshes with valid geometry");

        // Tree is scaled to world size of 400 units, centered at origin
        // So coordinates should span roughly [-200, +200]
        double worldSize = 400.0;
        System.out.printf("\nExpected world size: %.0f (centered at origin)\n", worldSize);

        // Verify bounds are reasonable - should be within world space
        double xSpan = maxX - minX;
        double ySpan = maxY - minY;
        double zSpan = maxZ - minZ;

        // Bounds should be within world space
        assertTrue(minX >= -worldSize && maxX <= worldSize,
            String.format("X should be in world space [-%.0f, %.0f], got: [%.2f, %.2f]",
                worldSize, worldSize, minX, maxX));
        assertTrue(minY >= -worldSize && maxY <= worldSize,
            String.format("Y should be in world space [-%.0f, %.0f], got: [%.2f, %.2f]",
                worldSize, worldSize, minY, maxY));
        assertTrue(minZ >= -worldSize && maxZ <= worldSize,
            String.format("Z should be in world space [-%.0f, %.0f], got: [%.2f, %.2f]",
                worldSize, worldSize, minZ, maxZ));

        // Span should be reasonable (non-zero, not too large)
        assertTrue(xSpan > 10, "X span should be meaningful, got: " + xSpan);
        assertTrue(ySpan > 10, "Y span should be meaningful, got: " + ySpan);
        assertTrue(zSpan > 10, "Z span should be meaningful, got: " + zSpan);
    }

    @Test
    void testReferenceMeshes() {
        // Test that reference meshes are properly created
        var voxels = List.of(new Point3i(0, 0, 0));
        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 1);
        var data = bridge.getData();

        var renderer = new ESVTNodeMeshRenderer(data);

        // Render and check mesh structure
        var group = renderer.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 1.0);
        var meshViews = collectMeshViews(group);

        System.out.printf("\n=== Reference Mesh Test ===\n");
        System.out.printf("Single voxel produced %d meshes\n", meshViews.size());

        for (int i = 0; i < meshViews.size(); i++) {
            var mv = meshViews.get(i);
            if (mv.getMesh() instanceof TriangleMesh mesh) {
                var points = mesh.getPoints();
                var faces = mesh.getFaces();
                System.out.printf("Mesh[%d]: %d points, %d face indices\n",
                    i, points.size(), faces.size());

                // Print all vertices
                System.out.printf("  Vertices:\n");
                for (int j = 0; j < points.size(); j += 3) {
                    System.out.printf("    v%d: (%.1f, %.1f, %.1f)\n",
                        j/3, points.get(j), points.get(j+1), points.get(j+2));
                }
            }
        }
    }

    @Test
    void testChildOriginCalculation() {
        // Test that child origins are computed correctly
        List<Point3i> voxels = new ArrayList<>();
        // Just 8 voxels at corners to test positioning
        voxels.add(new Point3i(0, 0, 0));
        voxels.add(new Point3i(1, 0, 0));
        voxels.add(new Point3i(0, 1, 0));
        voxels.add(new Point3i(0, 0, 1));
        voxels.add(new Point3i(1, 1, 0));
        voxels.add(new Point3i(1, 0, 1));
        voxels.add(new Point3i(0, 1, 1));
        voxels.add(new Point3i(1, 1, 1));

        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 2);
        var data = bridge.getData();

        System.out.printf("\n=== Child Origin Test ===\n");
        System.out.printf("Nodes: %d, Leaves: %d\n", data.nodeCount(), data.leafCount());

        var meshRenderer = new ESVTNodeMeshRenderer(data);
        var group = meshRenderer.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 0.7);
        var meshViews = collectMeshViews(group);

        System.out.printf("Leaf meshes: %d\n", meshViews.size());

        // Group meshes by their translation to see distribution
        var positions = new java.util.HashMap<String, Integer>();
        for (var mv : meshViews) {
            String key = String.format("(%.1f,%.1f,%.1f)",
                mv.getTranslateX(), mv.getTranslateY(), mv.getTranslateZ());
            positions.merge(key, 1, Integer::sum);
        }

        System.out.printf("Unique positions: %d\n", positions.size());
        positions.forEach((pos, count) ->
            System.out.printf("  %s: %d meshes\n", pos, count));
    }

    private List<MeshView> collectMeshViews(Node node) {
        var result = new ArrayList<MeshView>();
        collectMeshViewsRecursive(node, result);
        return result;
    }

    private void collectMeshViewsRecursive(Node node, List<MeshView> result) {
        if (node instanceof MeshView mv) {
            result.add(mv);
        } else if (node instanceof Group group) {
            for (var child : group.getChildren()) {
                collectMeshViewsRecursive(child, result);
            }
        }
    }
}
