package com.dyada.visualization.data;

import java.util.Arrays;
import java.util.Map;

/**
 * Represents a vertex in a mesh visualization with coordinates and attributes.
 */
public record MeshVertex(
    int id,
    double[] coordinates,
    Map<String, Object> attributes
) {
    
    public MeshVertex {
        if (coordinates == null) {
            throw new IllegalArgumentException("Coordinates cannot be null");
        }
        if (coordinates.length < 2 || coordinates.length > 3) {
            throw new IllegalArgumentException("Coordinates must be 2D or 3D");
        }
        
        // Defensive copy
        coordinates = Arrays.copyOf(coordinates, coordinates.length);
        
        if (attributes == null) {
            attributes = Map.of();
        }
    }
    
    /**
     * Creates a 2D vertex.
     */
    public static MeshVertex create2D(int id, double x, double y) {
        return new MeshVertex(id, new double[]{x, y}, Map.of());
    }
    
    /**
     * Creates a 3D vertex.
     */
    public static MeshVertex create3D(int id, double x, double y, double z) {
        return new MeshVertex(id, new double[]{x, y, z}, Map.of());
    }
    
    /**
     * Creates vertex with attributes.
     */
    public static MeshVertex withAttributes(int id, double[] coordinates, Map<String, Object> attributes) {
        return new MeshVertex(id, coordinates, attributes);
    }
    
    /**
     * Gets the number of dimensions.
     */
    public int dimensions() {
        return coordinates.length;
    }
    
    /**
     * Gets X coordinate.
     */
    public double x() {
        return coordinates[0];
    }
    
    /**
     * Gets Y coordinate.
     */
    public double y() {
        return coordinates[1];
    }
    
    /**
     * Gets Z coordinate (3D only).
     */
    public double z() {
        if (coordinates.length < 3) {
            throw new IllegalStateException("Z coordinate not available for 2D vertex");
        }
        return coordinates[2];
    }
    
    /**
     * Calculates distance to another vertex.
     */
    public double distanceTo(MeshVertex other) {
        if (this.dimensions() != other.dimensions()) {
            throw new IllegalArgumentException("Cannot calculate distance between vertices of different dimensions");
        }
        
        double sum = 0.0;
        for (int i = 0; i < coordinates.length; i++) {
            double diff = coordinates[i] - other.coordinates[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}