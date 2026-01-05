package com.hellblazer.luciferase.simulation.viz.dto;

import java.util.List;
import java.util.UUID;

/**
 * Data Transfer Object for bubble bounds visualization.
 * <p>
 * Provides tetrahedral bounds in Cartesian coordinates for Three.js rendering.
 * <p>
 * Structure:
 * - bubbleId: Unique bubble identifier
 * - tetrahedralBounds: 4 vertices of the tetrahedron in Cartesian [x,y,z] format
 * - neighbors: List of neighbor bubble IDs
 * - centroid: Centroid of the tetrahedron [x,y,z]
 */
public record BubbleBoundsDTO(
    UUID bubbleId,
    List<CartesianPoint> tetrahedralBounds,
    List<UUID> neighbors,
    CartesianPoint centroid
) {
    /**
     * Cartesian coordinate point for JSON serialization.
     */
    public record CartesianPoint(double x, double y, double z) {
    }
}
