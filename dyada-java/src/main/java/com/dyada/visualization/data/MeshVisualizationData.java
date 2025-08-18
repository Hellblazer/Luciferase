package com.dyada.visualization.data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Visualization data for adaptive meshes, containing geometry,
 * refinement levels, and rendering information.
 */
public record MeshVisualizationData(
    String id,
    Instant timestamp,
    int dimensions,
    Bounds bounds,
    Map<String, Object> metadata,
    List<MeshVertex> vertices,
    List<MeshCell> cells,
    Map<String, double[]> scalarFields,
    Map<String, double[][]> vectorFields
) implements VisualizationDataType {
    
    public MeshVisualizationData {
        if (vertices == null) {
            vertices = List.of();
        }
        if (cells == null) {
            cells = List.of();
        }
        if (scalarFields == null) {
            scalarFields = Map.of();
        }
        if (vectorFields == null) {
            vectorFields = Map.of();
        }
        
        // Validate vertex dimensions
        for (var vertex : vertices) {
            if (vertex.coordinates().length != dimensions) {
                throw new IllegalArgumentException(
                    "Vertex dimension mismatch: expected " + dimensions + 
                    ", got " + vertex.coordinates().length
                );
            }
        }
    }
    
    /**
     * Creates MeshVisualizationData from base VisualizationData.
     */
    public static MeshVisualizationData from(
        VisualizationData base,
        List<MeshVertex> vertices,
        List<MeshCell> cells
    ) {
        return new MeshVisualizationData(
            base.id(),
            base.timestamp(),
            base.dimensions(),
            base.bounds(),
            base.metadata(),
            vertices,
            cells,
            Map.of(),
            Map.of()
        );
    }
    
    /**
     * Adds scalar field data to the visualization.
     */
    public MeshVisualizationData withScalarField(String name, double[] values) {
        var newFields = new java.util.HashMap<>(scalarFields);
        newFields.put(name, values.clone()); // Defensive copy
        return new MeshVisualizationData(
            id, timestamp, dimensions, bounds, metadata,
            vertices, cells, newFields, vectorFields
        );
    }
    
    /**
     * Adds vector field data to the visualization.
     */
    public MeshVisualizationData withVectorField(String name, double[][] vectors) {
        var newFields = new java.util.HashMap<>(vectorFields);
        // Deep copy of 2D array
        double[][] vectorsCopy = new double[vectors.length][];
        for (int i = 0; i < vectors.length; i++) {
            vectorsCopy[i] = vectors[i].clone();
        }
        newFields.put(name, vectorsCopy);
        return new MeshVisualizationData(
            id, timestamp, dimensions, bounds, metadata,
            vertices, cells, scalarFields, newFields
        );
    }
    
    /**
     * Gets the number of mesh vertices.
     */
    public int vertexCount() {
        return vertices.size();
    }
    
    /**
     * Gets the number of mesh cells.
     */
    public int cellCount() {
        return cells.size();
    }
    
    /**
     * Finds vertices within a specified region.
     */
    public List<MeshVertex> getVerticesInRegion(Bounds region) {
        return vertices.stream()
            .filter(v -> region.contains(v.coordinates()))
            .toList();
    }
    
    @Override
    public VisualizationData asVisualizationData() {
        return new VisualizationData(id, timestamp, dimensions, bounds, metadata);
    }
}