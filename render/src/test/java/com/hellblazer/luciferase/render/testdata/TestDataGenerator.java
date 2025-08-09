package com.hellblazer.luciferase.render.testdata;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates test data for rendering pipeline validation.
 * Includes standard test objects like cubes, spheres, and procedural meshes.
 */
public class TestDataGenerator {
    
    /**
     * Generates vertex data for a simple cube.
     * @param size Size of the cube
     * @return Float buffer containing vertex positions (x, y, z)
     */
    public static FloatBuffer generateCubeVertices(float size) {
        var halfSize = size / 2.0f;
        
        float[] vertices = {
            // Front face
            -halfSize, -halfSize,  halfSize,
             halfSize, -halfSize,  halfSize,
             halfSize,  halfSize,  halfSize,
            -halfSize, -halfSize,  halfSize,
             halfSize,  halfSize,  halfSize,
            -halfSize,  halfSize,  halfSize,
            
            // Back face
            -halfSize, -halfSize, -halfSize,
            -halfSize,  halfSize, -halfSize,
             halfSize,  halfSize, -halfSize,
            -halfSize, -halfSize, -halfSize,
             halfSize,  halfSize, -halfSize,
             halfSize, -halfSize, -halfSize,
            
            // Top face
            -halfSize,  halfSize, -halfSize,
            -halfSize,  halfSize,  halfSize,
             halfSize,  halfSize,  halfSize,
            -halfSize,  halfSize, -halfSize,
             halfSize,  halfSize,  halfSize,
             halfSize,  halfSize, -halfSize,
            
            // Bottom face
            -halfSize, -halfSize, -halfSize,
             halfSize, -halfSize, -halfSize,
             halfSize, -halfSize,  halfSize,
            -halfSize, -halfSize, -halfSize,
             halfSize, -halfSize,  halfSize,
            -halfSize, -halfSize,  halfSize,
            
            // Right face
             halfSize, -halfSize, -halfSize,
             halfSize,  halfSize, -halfSize,
             halfSize,  halfSize,  halfSize,
             halfSize, -halfSize, -halfSize,
             halfSize,  halfSize,  halfSize,
             halfSize, -halfSize,  halfSize,
            
            // Left face
            -halfSize, -halfSize, -halfSize,
            -halfSize, -halfSize,  halfSize,
            -halfSize,  halfSize,  halfSize,
            -halfSize, -halfSize, -halfSize,
            -halfSize,  halfSize,  halfSize,
            -halfSize,  halfSize, -halfSize
        };
        
        var buffer = FloatBuffer.allocate(vertices.length);
        buffer.put(vertices);
        buffer.flip();
        return buffer;
    }
    
    /**
     * Generates vertex data for a sphere using icosphere subdivision.
     * @param radius Radius of the sphere
     * @param subdivisions Number of subdivision levels (0-4 recommended)
     * @return Float buffer containing vertex positions
     */
    public static FloatBuffer generateSphereVertices(float radius, int subdivisions) {
        List<float[]> vertices = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        
        // Create initial icosahedron
        var phi = (1.0f + (float)Math.sqrt(5.0)) / 2.0f; // Golden ratio
        var invLen = 1.0f / (float)Math.sqrt(1 + phi * phi);
        
        // Initial vertices of icosahedron
        float[][] initialVertices = {
            { -1,  phi, 0}, { 1,  phi, 0}, { -1, -phi, 0}, { 1, -phi, 0},
            { 0, -1,  phi}, {0,  1,  phi}, { 0, -1, -phi}, {0,  1, -phi},
            { phi, 0, -1}, {phi, 0,  1}, {-phi, 0, -1}, {-phi, 0,  1}
        };
        
        // Normalize and scale initial vertices
        for (var vertex : initialVertices) {
            var len = (float)Math.sqrt(vertex[0]*vertex[0] + vertex[1]*vertex[1] + vertex[2]*vertex[2]);
            vertices.add(new float[]{
                vertex[0] / len * radius,
                vertex[1] / len * radius, 
                vertex[2] / len * radius
            });
        }
        
        // Initial faces of icosahedron
        int[][] initialFaces = {
            {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
            {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
            {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
            {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        
        for (var face : initialFaces) {
            faces.add(face);
        }
        
        // Subdivide faces
        for (int sub = 0; sub < subdivisions; sub++) {
            List<int[]> newFaces = new ArrayList<>();
            
            for (var face : faces) {
                // Calculate midpoints and add to vertices
                int mid01 = addMidpoint(vertices, face[0], face[1], radius);
                int mid12 = addMidpoint(vertices, face[1], face[2], radius);  
                int mid20 = addMidpoint(vertices, face[2], face[0], radius);
                
                // Create 4 new faces
                newFaces.add(new int[]{face[0], mid01, mid20});
                newFaces.add(new int[]{face[1], mid12, mid01});
                newFaces.add(new int[]{face[2], mid20, mid12});
                newFaces.add(new int[]{mid01, mid12, mid20});
            }
            faces = newFaces;
        }
        
        // Convert to float buffer
        var buffer = FloatBuffer.allocate(faces.size() * 9); // 3 vertices * 3 components per face
        for (var face : faces) {
            for (int i = 0; i < 3; i++) {
                var vertex = vertices.get(face[i]);
                buffer.put(vertex[0]).put(vertex[1]).put(vertex[2]);
            }
        }
        buffer.flip();
        return buffer;
    }
    
    private static int addMidpoint(List<float[]> vertices, int i1, int i2, float radius) {
        var v1 = vertices.get(i1);
        var v2 = vertices.get(i2);
        
        var mid = new float[]{
            (v1[0] + v2[0]) / 2.0f,
            (v1[1] + v2[1]) / 2.0f,
            (v1[2] + v2[2]) / 2.0f
        };
        
        // Normalize to sphere surface
        var len = (float)Math.sqrt(mid[0]*mid[0] + mid[1]*mid[1] + mid[2]*mid[2]);
        mid[0] = mid[0] / len * radius;
        mid[1] = mid[1] / len * radius;
        mid[2] = mid[2] / len * radius;
        
        vertices.add(mid);
        return vertices.size() - 1;
    }
    
    /**
     * Generates a simple Stanford Bunny approximation using mathematical functions.
     * This is a procedural approximation, not the actual bunny mesh.
     * @param resolution Number of vertices per axis
     * @return Float buffer containing vertex positions
     */
    public static FloatBuffer generateBunnyApproximation(int resolution) {
        List<float[]> vertices = new ArrayList<>();
        var step = 2.0f / resolution;
        
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    var px = -1.0f + x * step;
                    var py = -1.0f + y * step; 
                    var pz = -1.0f + z * step;
                    
                    // Bunny-like shape using mathematical functions
                    var bodyRadius = 0.6f;
                    var headRadius = 0.4f;
                    var earHeight = 0.3f;
                    
                    // Body (ellipsoid)
                    var bodyDist = (px*px)/(bodyRadius*bodyRadius) + 
                                  ((py+0.2f)*(py+0.2f))/(bodyRadius*bodyRadius) + 
                                  (pz*pz)/((bodyRadius*0.8f)*(bodyRadius*0.8f));
                    
                    // Head (sphere offset upward)
                    var headCenterY = py - 0.5f;
                    var headDist = (px*px + headCenterY*headCenterY + pz*pz) / (headRadius*headRadius);
                    
                    // Ears (elongated ellipsoids)
                    var ear1CenterX = px - 0.15f;
                    var ear1CenterY = py - 0.7f;
                    var ear1Dist = (ear1CenterX*ear1CenterX)/(0.08f) + 
                                  (ear1CenterY*ear1CenterY)/(earHeight*earHeight) + 
                                  (pz*pz)/(0.05f);
                    
                    var ear2CenterX = px + 0.15f;
                    var ear2CenterY = py - 0.7f;
                    var ear2Dist = (ear2CenterX*ear2CenterX)/(0.08f) + 
                                  (ear2CenterY*ear2CenterY)/(earHeight*earHeight) + 
                                  (pz*pz)/(0.05f);
                    
                    // Include point if inside any part of the bunny
                    if (bodyDist <= 1.0f || headDist <= 1.0f || ear1Dist <= 1.0f || ear2Dist <= 1.0f) {
                        vertices.add(new float[]{px, py, pz});
                    }
                }
            }
        }
        
        // Convert to triangles (simple point cloud to mesh approximation)
        List<float[]> triangles = new ArrayList<>();
        for (int i = 0; i < vertices.size() - 2; i += 3) {
            triangles.add(vertices.get(i));
            triangles.add(vertices.get(i + 1));
            triangles.add(vertices.get(i + 2));
        }
        
        var buffer = FloatBuffer.allocate(triangles.size() * 3);
        for (var vertex : triangles) {
            buffer.put(vertex[0]).put(vertex[1]).put(vertex[2]);
        }
        buffer.flip();
        return buffer;
    }
    
    /**
     * Generates voxel data for testing compression and I/O systems.
     * @param resolution Voxel grid resolution (e.g., 64, 128, 256)
     * @param fillRatio Percentage of voxels to fill (0.0 to 1.0)
     * @return Byte buffer containing voxel data
     */
    public static ByteBuffer generateTestVoxelData(int resolution, float fillRatio) {
        var totalVoxels = resolution * resolution * resolution;
        var buffer = ByteBuffer.allocateDirect(totalVoxels);
        
        // Generate procedural voxel data with interesting patterns
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    var nx = (x - resolution/2.0f) / (resolution/2.0f);
                    var ny = (y - resolution/2.0f) / (resolution/2.0f);
                    var nz = (z - resolution/2.0f) / (resolution/2.0f);
                    
                    // Create interesting patterns
                    var sphereDist = nx*nx + ny*ny + nz*nz;
                    var noise = Math.sin(nx * 5) * Math.cos(ny * 5) * Math.sin(nz * 5);
                    var pattern = Math.sin(nx * 10) + Math.cos(ny * 10) + Math.sin(nz * 10);
                    
                    // Combine patterns to create complex structure
                    var density = 0.0;
                    
                    // Central sphere
                    if (sphereDist < 0.8 * 0.8) {
                        density += 0.5;
                    }
                    
                    // Add noise
                    density += noise * 0.2;
                    
                    // Add geometric patterns
                    if (pattern > 1.5) {
                        density += 0.3;
                    }
                    
                    // Random factor based on fill ratio
                    if (Math.random() < fillRatio) {
                        density += 0.2;
                    }
                    
                    // Convert density to voxel value (0-255)
                    var voxelValue = (int)(Math.max(0, Math.min(1, density)) * 255);
                    buffer.put((byte)voxelValue);
                }
            }
        }
        
        buffer.flip();
        return buffer;
    }
    
    /**
     * Generates test octree data with known structure for validation.
     * @param depth Maximum octree depth
     * @return Structured octree data for testing
     */
    public static ByteBuffer generateTestOctreeData(int depth) {
        var nodesCount = (int)((Math.pow(8, depth + 1) - 1) / 7); // Geometric series sum
        var buffer = ByteBuffer.allocateDirect(nodesCount * 32); // 32 bytes per node
        
        // Generate octree with predictable pattern
        for (int level = 0; level <= depth; level++) {
            var nodesAtLevel = (int)Math.pow(8, level);
            
            for (int i = 0; i < nodesAtLevel; i++) {
                // Node header (8 bytes)
                buffer.putInt(level); // Level
                buffer.putInt(i);     // Index at level
                
                // Bounding box (24 bytes)
                var size = 1.0f / (1 << level); // Size decreases with level
                var x = (i % 2) * size - 0.5f;
                var y = ((i / 2) % 2) * size - 0.5f;
                var z = ((i / 4) % 2) * size - 0.5f;
                
                buffer.putFloat(x);           // min_x
                buffer.putFloat(y);           // min_y  
                buffer.putFloat(z);           // min_z
                buffer.putFloat(x + size);    // max_x
                buffer.putFloat(y + size);    // max_y
                buffer.putFloat(z + size);    // max_z
            }
        }
        
        buffer.flip();
        return buffer;
    }
}