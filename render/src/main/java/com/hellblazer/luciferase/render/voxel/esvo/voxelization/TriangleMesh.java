package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

/**
 * Simple triangle mesh representation.
 * Contains vertices, triangles, and optional attributes.
 */
public class TriangleMesh {
    private final float[][] vertices;
    private final int[][] triangles;
    private float[][] vertexColors;
    private float[][] vertexNormals;
    private float[][] textureCoords;
    
    public TriangleMesh(float[][] vertices, int[][] triangles) {
        this.vertices = vertices;
        this.triangles = triangles;
    }
    
    public float[][] getVertices() {
        return vertices;
    }
    
    public int[][] getTriangles() {
        return triangles;
    }
    
    public int getVertexCount() {
        return vertices.length;
    }
    
    public int getTriangleCount() {
        return triangles.length;
    }
    
    public float[] getVertex(int index) {
        return vertices[index];
    }
    
    public int[] getTriangle(int index) {
        return triangles[index];
    }
    
    public void setVertexColors(float[][] vertexColors) {
        this.vertexColors = vertexColors;
    }
    
    public float[][] getVertexColors() {
        return vertexColors;
    }
    
    public void setVertexNormals(float[][] vertexNormals) {
        this.vertexNormals = vertexNormals;
    }
    
    public float[][] getVertexNormals() {
        return vertexNormals;
    }
    
    public void setTextureCoords(float[][] textureCoords) {
        this.textureCoords = textureCoords;
    }
    
    public float[][] getTextureCoords() {
        return textureCoords;
    }
}