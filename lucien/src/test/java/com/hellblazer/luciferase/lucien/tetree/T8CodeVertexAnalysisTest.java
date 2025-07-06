package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.HashSet;
import java.util.Set;

/**
 * Analyze t8code vertex generation algorithm to understand partitioning issues.
 */
public class T8CodeVertexAnalysisTest {
    
    @Test
    void analyzeT8CodeVertices() {
        System.out.println("=== T8Code Vertex Analysis ===\n");
        
        // Create unit tetrahedra at level 21 (size 1)
        System.out.println("Unit Tetrahedra Vertices (t8code algorithm):");
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 21, type);
            Point3i[] vertices = tet.coordinates();
            
            System.out.printf("\nType %d (ei=%d, ej=%d):\n", type, type/2, getEj(type));
            for (int i = 0; i < 4; i++) {
                System.out.printf("  v%d: (%d, %d, %d)\n", i, 
                    vertices[i].x, vertices[i].y, vertices[i].z);
            }
            
            // Analyze which cube vertices are used
            Set<String> cubeVertices = new HashSet<>();
            for (Point3i v : vertices) {
                String cubeVertex = getCubeVertexName(v);
                cubeVertices.add(cubeVertex);
            }
            System.out.println("  Cube vertices used: " + cubeVertices);
            
            // Calculate volume
            double volume = computeTetrahedronVolume(vertices);
            System.out.printf("  Volume: %.6f (expected: %.6f)\n", volume, 1.0/6.0);
            
            // Check orientation
            Vector3f normal = computeFaceNormal(vertices[0], vertices[1], vertices[2]);
            System.out.printf("  Face 0-1-2 normal: (%.2f, %.2f, %.2f)\n", 
                normal.x, normal.y, normal.z);
        }
        
        System.out.println("\n=== Vertex Relationships ===");
        analyzeVertexRelationships();
        
        System.out.println("\n=== Face Analysis ===");
        analyzeFaces();
    }
    
    private void analyzeVertexRelationships() {
        // Analyze how vertices are computed for each type
        System.out.println("\nVertex computation patterns:");
        System.out.println("Type | ei | ej | v0 | v1 | v2 | v3");
        System.out.println("-----|----|----|----|----|----|----|");
        
        for (byte type = 0; type < 6; type++) {
            int ei = type / 2;
            int ej = getEj(type);
            
            System.out.printf(" %d   | %d  | %d  | ", type, ei, ej);
            
            // v0 is always anchor
            System.out.print("anc | ");
            
            // v1 adds h to ei dimension
            System.out.printf("+%s | ", getDimName(ei));
            
            // v2 adds h to ei and ej dimensions
            System.out.printf("+%s%s | ", getDimName(ei), getDimName(ej));
            
            // v3 adds h to (ei+1)%3 and (ei+2)%3 dimensions
            int d1 = (ei + 1) % 3;
            int d2 = (ei + 2) % 3;
            System.out.printf("+%s%s\n", getDimName(Math.min(d1, d2)), getDimName(Math.max(d1, d2)));
        }
    }
    
    private void analyzeFaces() {
        // Analyze shared faces between tetrahedra
        System.out.println("\nShared faces between types:");
        
        for (byte t1 = 0; t1 < 6; t1++) {
            for (byte t2 = (byte)(t1 + 1); t2 < 6; t2++) {
                Tet tet1 = new Tet(0, 0, 0, (byte) 21, t1);
                Tet tet2 = new Tet(0, 0, 0, (byte) 21, t2);
                
                Point3i[] v1 = tet1.coordinates();
                Point3i[] v2 = tet2.coordinates();
                
                // Check for shared triangular faces
                Set<Set<String>> faces1 = getAllFaces(v1);
                Set<Set<String>> faces2 = getAllFaces(v2);
                
                // Find intersection
                faces1.retainAll(faces2);
                
                if (!faces1.isEmpty()) {
                    System.out.printf("Types %d and %d share %d face(s):\n", t1, t2, faces1.size());
                    for (Set<String> face : faces1) {
                        System.out.println("  " + face);
                    }
                }
            }
        }
    }
    
    private Set<Set<String>> getAllFaces(Point3i[] vertices) {
        Set<Set<String>> faces = new HashSet<>();
        
        // 4 faces of a tetrahedron
        int[][] faceIndices = {{0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3}};
        
        for (int[] face : faceIndices) {
            Set<String> faceVertices = new HashSet<>();
            for (int i : face) {
                faceVertices.add(vertexToString(vertices[i]));
            }
            faces.add(faceVertices);
        }
        
        return faces;
    }
    
    private String vertexToString(Point3i v) {
        return String.format("(%d,%d,%d)", v.x, v.y, v.z);
    }
    
    private int getEj(int type) {
        int ei = type / 2;
        return (ei + ((type % 2 == 0) ? 2 : 1)) % 3;
    }
    
    private String getDimName(int dim) {
        return dim == 0 ? "x" : dim == 1 ? "y" : "z";
    }
    
    private String getCubeVertexName(Point3i v) {
        // Map to standard cube vertex names C0-C7
        int index = (v.x > 0 ? 1 : 0) | (v.y > 0 ? 2 : 0) | (v.z > 0 ? 4 : 0);
        return "C" + index;
    }
    
    private double computeTetrahedronVolume(Point3i[] vertices) {
        // Volume = |det(v1-v0, v2-v0, v3-v0)| / 6
        int dx1 = vertices[1].x - vertices[0].x;
        int dy1 = vertices[1].y - vertices[0].y;
        int dz1 = vertices[1].z - vertices[0].z;
        
        int dx2 = vertices[2].x - vertices[0].x;
        int dy2 = vertices[2].y - vertices[0].y;
        int dz2 = vertices[2].z - vertices[0].z;
        
        int dx3 = vertices[3].x - vertices[0].x;
        int dy3 = vertices[3].y - vertices[0].y;
        int dz3 = vertices[3].z - vertices[0].z;
        
        int det = dx1 * (dy2 * dz3 - dz2 * dy3) -
                  dy1 * (dx2 * dz3 - dz2 * dx3) +
                  dz1 * (dx2 * dy3 - dy2 * dx3);
        
        return Math.abs(det) / 6.0;
    }
    
    private Vector3f computeFaceNormal(Point3i v0, Point3i v1, Point3i v2) {
        // Compute normal using cross product
        Vector3f e1 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3f e2 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);
        
        Vector3f normal = new Vector3f();
        normal.cross(e1, e2);
        normal.normalize();
        
        return normal;
    }
}