package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import java.util.HashSet;
import java.util.Set;

/**
 * Converts triangle meshes into voxel representation.
 * Uses Separating Axis Theorem for accurate triangle-voxel overlap testing.
 */
public class TriangleVoxelizer {
    
    private static final float EPSILON = 1e-6f;
    
    /**
     * Voxelize a single triangle
     */
    public VoxelizationResult voxelizeTriangle(float[] v0, float[] v1, float[] v2, 
                                               VoxelizationConfig config) {
        VoxelizationResult result = new VoxelizationResult();
        
        float[] bounds = config.getBounds();
        float voxelSize = config.getVoxelSize();
        int resolution = config.getResolution();
        
        // Compute triangle AABB
        float[] triMin = new float[3];
        float[] triMax = new float[3];
        computeTriangleAABB(v0, v1, v2, triMin, triMax);
        
        // Convert to voxel coordinates
        int minX = Math.max(0, (int) Math.floor((triMin[0] - bounds[0]) / voxelSize));
        int minY = Math.max(0, (int) Math.floor((triMin[1] - bounds[1]) / voxelSize));
        int minZ = Math.max(0, (int) Math.floor((triMin[2] - bounds[2]) / voxelSize));
        
        int maxX = Math.min(resolution - 1, (int) Math.ceil((triMax[0] - bounds[0]) / voxelSize));
        int maxY = Math.min(resolution - 1, (int) Math.ceil((triMax[1] - bounds[1]) / voxelSize));
        int maxZ = Math.min(resolution - 1, (int) Math.ceil((triMax[2] - bounds[2]) / voxelSize));
        
        // Compute triangle normal if needed
        float[] normal = null;
        if (config.isComputeNormals()) {
            normal = computeTriangleNormal(v0, v1, v2);
        }
        
        // Test each voxel in AABB
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    float[] voxelCenter = {
                        bounds[0] + (x + 0.5f) * voxelSize,
                        bounds[1] + (y + 0.5f) * voxelSize,
                        bounds[2] + (z + 0.5f) * voxelSize
                    };
                    
                    boolean overlaps = config.isConservative() ?
                        triangleOverlapsVoxelConservative(v0, v1, v2, voxelCenter, voxelSize) :
                        triangleOverlapsVoxel(v0, v1, v2, voxelCenter, voxelSize);
                    
                    if (overlaps) {
                        Voxel voxel = new Voxel(x, y, z);
                        if (normal != null) {
                            voxel.setNormal(normal.clone());
                        }
                        result.addVoxel(voxel);
                    }
                }
            }
        }
        
        // Generate octree if requested
        if (config.isGenerateOctree() && result.getVoxelCount() > 0) {
            result.setOctree(generateOctreeFromVoxels(result, config));
        }
        
        return result;
    }
    
    /**
     * Conservative voxelization - includes all voxels touched by triangle
     */
    private boolean triangleOverlapsVoxelConservative(float[] v0, float[] v1, float[] v2,
                                                      float[] voxelCenter, float voxelSize) {
        // Expand voxel bounds slightly for conservative rasterization
        float expandedSize = voxelSize * 1.01f;
        return triangleOverlapsVoxel(v0, v1, v2, voxelCenter, expandedSize);
    }
    
    /**
     * Test if triangle overlaps voxel using Separating Axis Theorem
     */
    public boolean triangleOverlapsVoxel(float[] v0, float[] v1, float[] v2,
                                         float[] voxelCenter, float voxelSize) {
        // Translate triangle so voxel center is at origin
        float halfSize = voxelSize * 0.5f;
        float[] tv0 = {v0[0] - voxelCenter[0], v0[1] - voxelCenter[1], v0[2] - voxelCenter[2]};
        float[] tv1 = {v1[0] - voxelCenter[0], v1[1] - voxelCenter[1], v1[2] - voxelCenter[2]};
        float[] tv2 = {v2[0] - voxelCenter[0], v2[1] - voxelCenter[1], v2[2] - voxelCenter[2]};
        
        // Test AABB axes
        float[] triMin = new float[3];
        float[] triMax = new float[3];
        computeTriangleAABB(tv0, tv1, tv2, triMin, triMax);
        
        for (int i = 0; i < 3; i++) {
            if (triMin[i] > halfSize || triMax[i] < -halfSize) {
                return false;
            }
        }
        
        // Test triangle normal
        float[] normal = computeTriangleNormal(tv0, tv1, tv2);
        float d = Math.abs(dot(normal, tv0));
        float r = halfSize * (Math.abs(normal[0]) + Math.abs(normal[1]) + Math.abs(normal[2]));
        if (d > r) {
            return false;
        }
        
        // Test edge cross products
        float[][] edges = {
            {tv1[0] - tv0[0], tv1[1] - tv0[1], tv1[2] - tv0[2]},
            {tv2[0] - tv1[0], tv2[1] - tv1[1], tv2[2] - tv1[2]},
            {tv0[0] - tv2[0], tv0[1] - tv2[1], tv0[2] - tv2[2]}
        };
        
        float[][] boxNormals = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        
        for (float[] edge : edges) {
            for (float[] axis : boxNormals) {
                float[] crossAxis = cross(edge, axis);
                float len = length(crossAxis);
                if (len < EPSILON) continue;
                
                normalize(crossAxis);
                
                float p0 = dot(tv0, crossAxis);
                float p1 = dot(tv1, crossAxis);
                float p2 = dot(tv2, crossAxis);
                
                float triProjection = Math.max(Math.max(p0, p1), p2) - Math.min(Math.min(p0, p1), p2);
                float boxProjection = halfSize * (Math.abs(crossAxis[0]) + 
                                                 Math.abs(crossAxis[1]) + 
                                                 Math.abs(crossAxis[2]));
                
                if (Math.min(Math.min(p0, p1), p2) > boxProjection ||
                    Math.max(Math.max(p0, p1), p2) < -boxProjection) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    
    /**
     * Voxelize a complete mesh
     */
    public VoxelizationResult voxelizeMesh(TriangleMesh mesh, VoxelizationConfig config) {
        VoxelizationResult result = new VoxelizationResult();
        Set<Integer> uniqueVoxels = new HashSet<>();
        
        // Process each triangle
        for (int[] triangle : mesh.getTriangles()) {
            float[] v0 = mesh.getVertex(triangle[0]);
            float[] v1 = mesh.getVertex(triangle[1]);
            float[] v2 = mesh.getVertex(triangle[2]);
            
            VoxelizationResult triResult = voxelizeTriangle(v0, v1, v2, config);
            
            // Merge voxels, avoiding duplicates
            for (Voxel voxel : triResult.getVoxels()) {
                int key = voxel.x * 1000000 + voxel.y * 1000 + voxel.z;
                if (uniqueVoxels.add(key)) {
                    result.addVoxel(voxel);
                }
            }
        }
        
        result.setTriangleCount(mesh.getTriangleCount());
        
        // Generate octree if requested
        if (config.isGenerateOctree() && result.getVoxelCount() > 0) {
            result.setOctree(generateOctreeFromVoxels(result, config));
        }
        
        return result;
    }
    
    // Helper methods
    
    private void computeTriangleAABB(float[] v0, float[] v1, float[] v2,
                                     float[] min, float[] max) {
        for (int i = 0; i < 3; i++) {
            min[i] = Math.min(Math.min(v0[i], v1[i]), v2[i]);
            max[i] = Math.max(Math.max(v0[i], v1[i]), v2[i]);
        }
    }
    
    private float[] computeTriangleNormal(float[] v0, float[] v1, float[] v2) {
        float[] e1 = {v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]};
        float[] e2 = {v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]};
        float[] normal = cross(e1, e2);
        normalize(normal);
        return normal;
    }
    
    private float[] cross(float[] a, float[] b) {
        return new float[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }
    
    private float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
    
    private float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
    
    private void normalize(float[] v) {
        float len = length(v);
        if (len > EPSILON) {
            v[0] /= len;
            v[1] /= len;
            v[2] /= len;
        }
    }
    
    private Octree generateOctreeFromVoxels(VoxelizationResult result, VoxelizationConfig config) {
        // Placeholder for octree generation
        Octree octree = new Octree();
        octree.setNodeCount(result.getVoxelCount());
        octree.setLeafCount(result.getVoxelCount());
        octree.setMaxDepth(Math.min(config.getMaxOctreeDepth(), 
                                    (int) (Math.log(config.getResolution()) / Math.log(2))));
        return octree;
    }
}