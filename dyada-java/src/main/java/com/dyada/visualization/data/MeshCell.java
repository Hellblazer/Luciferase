package com.dyada.visualization.data;

import java.util.Arrays;
import java.util.Map;

/**
 * Represents a cell in a mesh visualization with vertex connectivity and attributes.
 * Supports various cell types: triangles, quads, tetrahedra, hexahedra.
 */
public record MeshCell(
    int id,
    CellType type,
    int[] vertexIndices,
    int refinementLevel,
    Map<String, Object> attributes
) {
    
    public MeshCell {
        if (type == null) {
            throw new IllegalArgumentException("Cell type cannot be null");
        }
        if (vertexIndices == null) {
            throw new IllegalArgumentException("Vertex indices cannot be null");
        }
        if (vertexIndices.length != type.vertexCount()) {
            throw new IllegalArgumentException(
                "Vertex count mismatch: " + type + " requires " + type.vertexCount() + 
                " vertices, got " + vertexIndices.length
            );
        }
        if (refinementLevel < 0) {
            throw new IllegalArgumentException("Refinement level cannot be negative");
        }
        
        // Defensive copy
        vertexIndices = Arrays.copyOf(vertexIndices, vertexIndices.length);
        
        if (attributes == null) {
            attributes = Map.of();
        }
    }
    
    /**
     * Cell types supported in mesh visualization.
     */
    public enum CellType {
        TRIANGLE(3, 2),
        QUAD(4, 2),
        TETRAHEDRON(4, 3),
        HEXAHEDRON(8, 3);
        
        private final int vertexCount;
        private final int dimension;
        
        CellType(int vertexCount, int dimension) {
            this.vertexCount = vertexCount;
            this.dimension = dimension;
        }
        
        public int vertexCount() { return vertexCount; }
        public int dimension() { return dimension; }
    }
    
    /**
     * Creates a triangular cell.
     */
    public static MeshCell triangle(int id, int v1, int v2, int v3, int level) {
        return new MeshCell(id, CellType.TRIANGLE, new int[]{v1, v2, v3}, level, Map.of());
    }
    
    /**
     * Creates a quad cell.
     */
    public static MeshCell quad(int id, int v1, int v2, int v3, int v4, int level) {
        return new MeshCell(id, CellType.QUAD, new int[]{v1, v2, v3, v4}, level, Map.of());
    }
    
    /**
     * Creates a tetrahedral cell.
     */
    public static MeshCell tetrahedron(int id, int v1, int v2, int v3, int v4, int level) {
        return new MeshCell(id, CellType.TETRAHEDRON, new int[]{v1, v2, v3, v4}, level, Map.of());
    }
    
    /**
     * Creates a hexahedral cell.
     */
    public static MeshCell hexahedron(int id, int[] vertices, int level) {
        if (vertices.length != 8) {
            throw new IllegalArgumentException("Hexahedron requires 8 vertices");
        }
        return new MeshCell(id, CellType.HEXAHEDRON, vertices, level, Map.of());
    }
    
    /**
     * Creates cell with attributes.
     */
    public static MeshCell withAttributes(
        int id, CellType type, int[] vertices, int level, Map<String, Object> attributes
    ) {
        return new MeshCell(id, type, vertices, level, attributes);
    }
    
    /**
     * Gets the number of vertices in this cell.
     */
    public int getVertexCount() {
        return vertexIndices.length;
    }
    
    /**
     * Gets the spatial dimension of this cell type.
     */
    public int getDimension() {
        return type.dimension();
    }
    
    /**
     * Checks if this cell contains a specific vertex.
     */
    public boolean containsVertex(int vertexIndex) {
        for (int index : vertexIndices) {
            if (index == vertexIndex) {
                return true;
            }
        }
        return false;
    }
}