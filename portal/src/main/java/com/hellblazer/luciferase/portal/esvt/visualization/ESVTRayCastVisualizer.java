/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.esvt.visualization;

import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;

/**
 * Visualizes ray casting through ESVT (Efficient Sparse Voxel Tetrahedra), showing:
 * <ul>
 *   <li>Ray path through the scene</li>
 *   <li>Hit point (if any)</li>
 *   <li>Surface normal at hit point</li>
 *   <li>Entry face highlighting (triangular faces)</li>
 *   <li>Tet type annotation (S0-S5)</li>
 *   <li>Traversal statistics</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTRayCastVisualizer {

    private static final Logger log = LoggerFactory.getLogger(ESVTRayCastVisualizer.class);

    /** Tet type colors for S0-S5 */
    private static final Color[] TET_TYPE_COLORS = {
        Color.RED,        // S0
        Color.GREEN,      // S1
        Color.BLUE,       // S2
        Color.YELLOW,     // S3
        Color.MAGENTA,    // S4
        Color.CYAN        // S5
    };

    /** Face names for reference */
    private static final String[] FACE_NAMES = { "F0 (v1,v2,v3)", "F1 (v0,v3,v2)", "F2 (v0,v1,v3)", "F3 (v0,v2,v1)" };

    /**
     * Visualization mode for ray casting display
     */
    public enum VisualizationMode {
        /** Show only the ray path */
        RAY_ONLY,
        /** Show ray path and hit point */
        RAY_AND_HIT,
        /** Show ray, hit point, and surface normal */
        RAY_HIT_NORMAL,
        /** Show ray, hit point, entry face */
        RAY_HIT_FACE,
        /** Show full traversal with tet type coloring */
        FULL_TRAVERSAL
    }

    private final VisualizationMode mode;
    private final Color rayColor;
    private final Color hitColor;
    private final Color normalColor;
    private final double rayThickness;
    private final double hitPointSize;

    /**
     * Create visualizer with default settings.
     */
    public ESVTRayCastVisualizer() {
        this(VisualizationMode.RAY_HIT_FACE,
             Color.YELLOW,
             Color.RED,
             Color.CYAN,
             1.0,
             3.0);
    }

    /**
     * Create visualizer with custom settings.
     */
    public ESVTRayCastVisualizer(VisualizationMode mode,
                                  Color rayColor,
                                  Color hitColor,
                                  Color normalColor,
                                  double rayThickness,
                                  double hitPointSize) {
        this.mode = mode;
        this.rayColor = rayColor;
        this.hitColor = hitColor;
        this.normalColor = normalColor;
        this.rayThickness = rayThickness;
        this.hitPointSize = hitPointSize;
    }

    /**
     * Visualize a ray cast result.
     *
     * @param origin Ray origin in normalized space [0,1]
     * @param direction Ray direction (will be normalized)
     * @param result Traversal result from ESVTBridge
     * @param maxDistance Maximum distance to show ray (used if no hit)
     * @return Group containing all visualization geometry
     */
    public Group visualize(Vector3f origin, Vector3f direction,
                           ESVTResult result, float maxDistance) {
        var group = new Group();

        if (result == null) {
            log.warn("Cannot visualize null traversal result");
            return group;
        }

        // Scale coordinates to match JavaFX scene (100x for better visibility)
        float scale = 100.0f;

        // Convert to JavaFX coordinates
        var rayOrigin = toPoint3D(origin, scale);
        var rayDir = toPoint3D(direction, 1.0f);
        if (rayDir.magnitude() > 0.001) {
            rayDir = rayDir.normalize();
        }

        // Determine ray end point
        Point3D rayEnd;
        if (result.isHit()) {
            rayEnd = new Point3D(result.x * scale, result.y * scale, result.z * scale);
        } else {
            rayEnd = rayOrigin.add(rayDir.multiply(maxDistance * scale));
        }

        // Add ray visualization
        addRayLine(group, rayOrigin, rayEnd, rayColor, rayThickness);

        // Add hit point visualization
        if (result.isHit()) {
            addHitVisualization(group, result, rayEnd, scale);
        }

        // Log traversal statistics
        if (mode == VisualizationMode.FULL_TRAVERSAL) {
            log.debug("ESVT ray traversal: hit={}, t={}, tetType={}, entryFace={}, iterations={}",
                     result.hit, result.t, result.tetType, result.entryFace, result.iterations);
        }

        return group;
    }

    /**
     * Add hit-related visualizations based on current mode.
     */
    private void addHitVisualization(Group group, ESVTResult result, Point3D hitPoint, float scale) {
        // Add hit point sphere
        var hitMaterial = new PhongMaterial(hitColor);
        if (result.tetType >= 0 && result.tetType < TET_TYPE_COLORS.length) {
            hitMaterial.setDiffuseColor(TET_TYPE_COLORS[result.tetType]);
        }
        addHitPoint(group, hitPoint, hitMaterial, hitPointSize);

        // Add surface normal
        if (result.normal != null &&
            (mode == VisualizationMode.RAY_HIT_NORMAL ||
             mode == VisualizationMode.FULL_TRAVERSAL)) {
            var normalDir = toPoint3D(result.normal, 1.0f);
            var normalEnd = hitPoint.add(normalDir.multiply(20.0));
            addRayLine(group, hitPoint, normalEnd, normalColor, rayThickness * 0.8);
        }

        // Add entry face visualization
        if ((mode == VisualizationMode.RAY_HIT_FACE || mode == VisualizationMode.FULL_TRAVERSAL) &&
            result.entryFace >= 0) {
            addEntryFaceIndicator(group, hitPoint, result.entryFace, result.tetType, scale);
        }
    }

    /**
     * Add an indicator for the entry face of the tetrahedron.
     */
    private void addEntryFaceIndicator(Group group, Point3D hitPoint, int entryFace, int tetType, float scale) {
        // Create a small triangle to indicate entry face
        var size = 5.0;

        // Entry face normal vectors (approximate, pointing outward from unit tet)
        var faceNormals = new Point3D[] {
            new Point3D(-1, 1, 1).normalize(),   // F0 (opposite v0)
            new Point3D(1, -1, 1).normalize(),   // F1 (opposite v1)
            new Point3D(1, 1, -1).normalize(),   // F2 (opposite v2)
            new Point3D(1, 1, 1).normalize()     // F3 (opposite v3)
        };

        if (entryFace >= 0 && entryFace < faceNormals.length) {
            var normal = faceNormals[entryFace];
            var indicatorPos = hitPoint.add(normal.multiply(size * 2));

            // Create small sphere at indicator position
            var indicator = new Sphere(size * 0.5);
            var material = new PhongMaterial(Color.ORANGE);
            material.setSpecularColor(Color.WHITE);
            indicator.setMaterial(material);

            indicator.setTranslateX(indicatorPos.getX());
            indicator.setTranslateY(indicatorPos.getY());
            indicator.setTranslateZ(indicatorPos.getZ());

            group.getChildren().add(indicator);

            // Add line from hit point to indicator
            addRayLine(group, hitPoint, indicatorPos, Color.ORANGE, rayThickness * 0.5);
        }
    }

    /**
     * Add a line representing a ray segment.
     */
    private void addRayLine(Group group, Point3D start, Point3D end, Color color, double thickness) {
        var direction = end.subtract(start);
        double length = direction.magnitude();

        if (length < 0.001) {
            return;
        }

        var ray = new Cylinder(thickness, length);

        var material = new PhongMaterial(color);
        material.setSpecularColor(Color.WHITE);
        material.setSpecularPower(10);
        ray.setMaterial(material);

        // Position at midpoint
        var midpoint = start.add(end).multiply(0.5);
        ray.setTranslateX(midpoint.getX());
        ray.setTranslateY(midpoint.getY());
        ray.setTranslateZ(midpoint.getZ());

        // Rotate to align with direction
        var yAxis = new Point3D(0, 1, 0);
        var normalizedDir = direction.normalize();

        var rotationAxis = yAxis.crossProduct(normalizedDir);
        double rotationAngle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, yAxis.dotProduct(normalizedDir)))));

        if (rotationAxis.magnitude() > 0.001) {
            var rotate = new Rotate(rotationAngle, rotationAxis);
            ray.getTransforms().add(rotate);
        }

        group.getChildren().add(ray);
    }

    /**
     * Add a sphere representing the hit point.
     */
    private void addHitPoint(Group group, Point3D position, PhongMaterial material, double size) {
        var hitSphere = new Sphere(size);
        material.setSpecularColor(Color.WHITE);
        material.setSpecularPower(20);
        hitSphere.setMaterial(material);

        hitSphere.setTranslateX(position.getX());
        hitSphere.setTranslateY(position.getY());
        hitSphere.setTranslateZ(position.getZ());

        group.getChildren().add(hitSphere);
    }

    /**
     * Convert Vector3f to JavaFX Point3D with scaling.
     */
    private Point3D toPoint3D(Vector3f vec, float scale) {
        return new Point3D(vec.x * scale, vec.y * scale, vec.z * scale);
    }

    /**
     * Get the color for a tetrahedron type.
     *
     * @param tetType Tetrahedron type (0-5 for S0-S5)
     * @return The assigned color
     */
    public static Color getTetTypeColor(int tetType) {
        if (tetType >= 0 && tetType < TET_TYPE_COLORS.length) {
            return TET_TYPE_COLORS[tetType];
        }
        return Color.GRAY;
    }

    /**
     * Get the face name for logging.
     *
     * @param faceIndex Face index (0-3)
     * @return Human-readable face name
     */
    public static String getFaceName(int faceIndex) {
        if (faceIndex >= 0 && faceIndex < FACE_NAMES.length) {
            return FACE_NAMES[faceIndex];
        }
        return "Unknown";
    }

    /**
     * Create a statistics summary string.
     *
     * @param result Traversal result
     * @return Formatted statistics string
     */
    public String getStatisticsSummary(ESVTResult result) {
        if (result == null) {
            return "No result";
        }

        var sb = new StringBuilder();
        sb.append("=== ESVT Ray Cast ===\n");
        sb.append(String.format("Hit: %s\n", result.hit ? "YES" : "NO"));

        if (result.isHit()) {
            sb.append(String.format("Distance (t): %.4f\n", result.t));
            sb.append(String.format("Hit Point: (%.3f, %.3f, %.3f)\n", result.x, result.y, result.z));
            sb.append(String.format("Tet Type: S%d\n", result.tetType));
            sb.append(String.format("Entry Face: %s\n", getFaceName(result.entryFace)));
            sb.append(String.format("Node Index: %d\n", result.nodeIndex));
            sb.append(String.format("Scale/Depth: %d\n", result.scale));
        }

        sb.append(String.format("Iterations: %d\n", result.iterations));

        return sb.toString();
    }

    /**
     * Get current visualization mode.
     */
    public VisualizationMode getMode() {
        return mode;
    }

    /**
     * Create a new visualizer with different mode.
     */
    public ESVTRayCastVisualizer withMode(VisualizationMode newMode) {
        return new ESVTRayCastVisualizer(newMode, rayColor, hitColor, normalColor,
                                          rayThickness, hitPointSize);
    }

    /**
     * Create a new visualizer with different colors.
     */
    public ESVTRayCastVisualizer withColors(Color ray, Color hit, Color normal) {
        return new ESVTRayCastVisualizer(mode, ray, hit, normal, rayThickness, hitPointSize);
    }

    /**
     * Create a new visualizer with different sizes.
     */
    public ESVTRayCastVisualizer withSizes(double thickness, double pointSize) {
        return new ESVTRayCastVisualizer(mode, rayColor, hitColor, normalColor, thickness, pointSize);
    }
}
