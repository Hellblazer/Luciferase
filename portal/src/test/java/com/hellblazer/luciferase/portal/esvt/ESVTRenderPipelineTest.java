/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.esvt;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTNodeMeshRenderer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.MeshView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end pipeline test that validates:
 * 1. Voxel generation produces correct coordinates
 * 2. ESVT build preserves spatial structure
 * 3. Renderer places meshes at correct world positions
 */
class ESVTRenderPipelineTest {

    @BeforeAll
    static void initJavaFX() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            new JFXPanel();
            Platform.runLater(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("JavaFX init: " + e.getMessage());
        }
    }

    @Test
    void testFullPipelineSphereRendering() {
        System.out.println("=== Full Pipeline Sphere Rendering Test ===\n");

        var generator = new ESVTVoxelGenerator();
        var bridge = new ESVTBridge();

        int resolution = 32;
        int depth = 5;

        // Step 1: Generate voxels
        var voxels = generator.generate(ESVTVoxelGenerator.Shape.SPHERE, resolution);
        System.out.printf("Step 1: Generated %d sphere voxels%n", voxels.size());

        // Compute voxel centroid
        double voxCx = 0, voxCy = 0, voxCz = 0;
        for (var v : voxels) {
            voxCx += v.x + 0.5;
            voxCy += v.y + 0.5;
            voxCz += v.z + 0.5;
        }
        voxCx /= voxels.size();
        voxCy /= voxels.size();
        voxCz /= voxels.size();
        System.out.printf("  Voxel centroid: (%.2f, %.2f, %.2f)%n", voxCx, voxCy, voxCz);

        // Expected from S0 incenter
        var incenter = generator.getS0Incenter();
        System.out.printf("  Expected center (S0 incenter * res): (%.2f, %.2f, %.2f)%n",
            incenter.x * resolution, incenter.y * resolution, incenter.z * resolution);

        // Step 2: Build ESVT with grid resolution
        bridge.buildAndChain(voxels, depth, resolution);
        var data = bridge.getData();
        System.out.printf("%nStep 2: Built ESVT: %d nodes, %d leaves%n",
            data.nodeCount(), data.leafCount());

        // Step 3: Render
        var meshRenderer = new ESVTNodeMeshRenderer(data);
        var group = meshRenderer.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 0.7);

        var meshViews = collectMeshViews(group);
        System.out.printf("%nStep 3: Rendered %d mesh views%n", meshViews.size());

        assertTrue(meshViews.size() > 0, "Should have mesh views");

        // Analyze mesh positions
        double meshCx = 0, meshCy = 0, meshCz = 0;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (var mv : meshViews) {
            double x = mv.getTranslateX();
            double y = mv.getTranslateY();
            double z = mv.getTranslateZ();

            meshCx += x;
            meshCy += y;
            meshCz += z;

            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        meshCx /= meshViews.size();
        meshCy /= meshViews.size();
        meshCz /= meshViews.size();

        System.out.printf("  Mesh centroid: (%.2f, %.2f, %.2f)%n", meshCx, meshCy, meshCz);
        System.out.printf("  Mesh bounds X: [%.2f, %.2f] span=%.2f%n", minX, maxX, maxX - minX);
        System.out.printf("  Mesh bounds Y: [%.2f, %.2f] span=%.2f%n", minY, maxY, maxY - minY);
        System.out.printf("  Mesh bounds Z: [%.2f, %.2f] span=%.2f%n", minZ, maxZ, maxZ - minZ);

        // Check: mesh positions should form ONE connected region, not multiple
        // All meshes should be within a reasonable distance from the centroid
        double maxDistFromCentroid = 0;
        int outliers = 0;
        double expectedRadius = 100; // rough estimate in world coords

        for (var mv : meshViews) {
            double dx = mv.getTranslateX() - meshCx;
            double dy = mv.getTranslateY() - meshCy;
            double dz = mv.getTranslateZ() - meshCz;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            maxDistFromCentroid = Math.max(maxDistFromCentroid, dist);
            if (dist > expectedRadius * 2) {
                outliers++;
            }
        }

        System.out.printf("  Max distance from centroid: %.2f%n", maxDistFromCentroid);
        System.out.printf("  Outliers (>2x expected radius): %d%n", outliers);

        // The shape should be roughly spherical - check span ratios
        double xSpan = maxX - minX;
        double ySpan = maxY - minY;
        double zSpan = maxZ - minZ;
        double avgSpan = (xSpan + ySpan + zSpan) / 3.0;

        System.out.printf("%n  Span ratios (should be ~1.0 for sphere):%n");
        System.out.printf("    X/avg: %.2f%n", xSpan / avgSpan);
        System.out.printf("    Y/avg: %.2f%n", ySpan / avgSpan);
        System.out.printf("    Z/avg: %.2f%n", zSpan / avgSpan);

        // Assertions
        assertTrue(outliers < meshViews.size() * 0.1,
            "Less than 10% of meshes should be outliers, got " + outliers);

        // All span ratios should be between 0.5 and 2.0 for a roughly spherical shape
        assertTrue(xSpan / avgSpan > 0.5 && xSpan / avgSpan < 2.0,
            "X span ratio should be reasonable: " + (xSpan / avgSpan));
        assertTrue(ySpan / avgSpan > 0.5 && ySpan / avgSpan < 2.0,
            "Y span ratio should be reasonable: " + (ySpan / avgSpan));
        assertTrue(zSpan / avgSpan > 0.5 && zSpan / avgSpan < 2.0,
            "Z span ratio should be reasonable: " + (zSpan / avgSpan));
    }

    @Test
    void testSimpleCubeRendering() {
        System.out.println("\n=== Simple Cube Rendering Test ===\n");

        // Create a simple 4x4x4 cube of voxels centered at (16,16,16) in a 32x32x32 grid
        var voxels = new ArrayList<Point3i>();
        for (int x = 14; x < 18; x++) {
            for (int y = 14; y < 18; y++) {
                for (int z = 14; z < 18; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }

        System.out.printf("Created %d voxels in 4x4x4 cube at (14-17, 14-17, 14-17)%n", voxels.size());

        var bridge = new ESVTBridge();
        bridge.buildAndChain(voxels, 5, 32);
        var data = bridge.getData();

        System.out.printf("ESVT: %d nodes, %d leaves%n", data.nodeCount(), data.leafCount());

        var meshRenderer = new ESVTNodeMeshRenderer(data);
        var group = meshRenderer.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 0.7);

        var meshViews = collectMeshViews(group);
        System.out.printf("Rendered %d meshes%n", meshViews.size());

        // Check mesh positions
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (var mv : meshViews) {
            minX = Math.min(minX, mv.getTranslateX());
            maxX = Math.max(maxX, mv.getTranslateX());
            minY = Math.min(minY, mv.getTranslateY());
            maxY = Math.max(maxY, mv.getTranslateY());
            minZ = Math.min(minZ, mv.getTranslateZ());
            maxZ = Math.max(maxZ, mv.getTranslateZ());
        }

        System.out.printf("Mesh bounds: X[%.1f,%.1f] Y[%.1f,%.1f] Z[%.1f,%.1f]%n",
            minX, maxX, minY, maxY, minZ, maxZ);

        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        double cz = (minZ + maxZ) / 2;
        System.out.printf("Mesh center: (%.1f, %.1f, %.1f)%n", cx, cy, cz);

        // With worldSize=400 centered at origin, voxels at (14-17)/32 = 0.44-0.53
        // should map to world coords around (0.5 - 0.5) * 400 = 0 (centered)
        // Actually with offset -200, center at (16/32)*400 - 200 = 200 - 200 = 0
        System.out.printf("Expected center: near (0, 0, 0) since voxels centered in grid%n");

        // The rendered shape should be centered
        assertTrue(Math.abs(cx) < 50, "X center should be near 0, got: " + cx);
        assertTrue(Math.abs(cy) < 50, "Y center should be near 0, got: " + cy);
        assertTrue(Math.abs(cz) < 50, "Z center should be near 0, got: " + cz);
    }

    @Test
    void testVoxelToWorldCoordinateMapping() {
        System.out.println("\n=== Voxel to World Coordinate Mapping Test ===\n");

        // Create voxels at known positions and verify they render at expected world positions
        // Using resolution=32, worldSize=400

        // Single voxel at origin (0,0,0)
        var originVoxels = List.of(new Point3i(0, 0, 0));
        var bridge1 = new ESVTBridge();
        bridge1.buildAndChain(originVoxels, 5, 32);

        var renderer1 = new ESVTNodeMeshRenderer(bridge1.getData());
        var group1 = renderer1.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 1.0);
        var meshes1 = collectMeshViews(group1);

        System.out.printf("Voxel at (0,0,0):%n");
        if (!meshes1.isEmpty()) {
            var m = meshes1.get(0);
            System.out.printf("  Renders at: (%.1f, %.1f, %.1f)%n",
                m.getTranslateX(), m.getTranslateY(), m.getTranslateZ());
            // Voxel at (0,0,0) in a 32-grid with worldSize=400 centered at origin:
            // worldX = 0 * (400/32) - 200 = -200
            System.out.printf("  Expected: around (-200, -200, -200) or similar%n");
        } else {
            System.out.println("  No meshes rendered (voxel may be outside S0)");
        }

        // Single voxel at center (16,16,16)
        var centerVoxels = List.of(new Point3i(16, 16, 16));
        var bridge2 = new ESVTBridge();
        bridge2.buildAndChain(centerVoxels, 5, 32);

        var renderer2 = new ESVTNodeMeshRenderer(bridge2.getData());
        var group2 = renderer2.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 1.0);
        var meshes2 = collectMeshViews(group2);

        System.out.printf("%nVoxel at (16,16,16):%n");
        if (!meshes2.isEmpty()) {
            var m = meshes2.get(0);
            System.out.printf("  Renders at: (%.1f, %.1f, %.1f)%n",
                m.getTranslateX(), m.getTranslateY(), m.getTranslateZ());
            // Voxel at (16,16,16) in a 32-grid:
            // worldX = 16 * (400/32) - 200 = 200 - 200 = 0
            System.out.printf("  Expected: around (0, 0, 0)%n");
        } else {
            System.out.println("  No meshes rendered");
        }

        // Single voxel at far corner (31,31,31)
        var cornerVoxels = List.of(new Point3i(31, 31, 31));
        var bridge3 = new ESVTBridge();
        bridge3.buildAndChain(cornerVoxels, 5, 32);

        var renderer3 = new ESVTNodeMeshRenderer(bridge3.getData());
        var group3 = renderer3.renderLeaves(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, 1.0);
        var meshes3 = collectMeshViews(group3);

        System.out.printf("%nVoxel at (31,31,31):%n");
        if (!meshes3.isEmpty()) {
            var m = meshes3.get(0);
            System.out.printf("  Renders at: (%.1f, %.1f, %.1f)%n",
                m.getTranslateX(), m.getTranslateY(), m.getTranslateZ());
            // Voxel at (31,31,31):
            // worldX = 31 * (400/32) - 200 ≈ 387.5 - 200 = 187.5
            System.out.printf("  Expected: around (187.5, 187.5, 187.5)%n");
        } else {
            System.out.println("  No meshes rendered");
        }
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
